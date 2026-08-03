package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.{GlobalSpace, TurnContext}
import sigil.conversation.{ConversationView, Conversation, ContextMemory, MemorySource, TopicEntry, TurnInput}
import sigil.event.{Event, ToolOutcome}
import sigil.signal.ToolDelta
import sigil.tool.context.{PinMemoryInput, PinMemoryTool, UnpinMemoryInput, UnpinMemoryTool}

/**
 * Symmetry coverage for [[PinMemoryTool]] and [[UnpinMemoryTool]] —
 * the two tools agents use to flip
 * [[sigil.conversation.ContextMemory.pinned]] post-hoc. PinMemoryTool
 * promotes an unpinned memory to render-every-turn; UnpinMemoryTool
 * demotes a pinned one to topical-only. Both are durable but
 * reversible; no record is deleted.
 */
class PinUnpinMemorySpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {

  TestSigil.initFor(getClass.getSimpleName)

  private def makeContext(convId: Id[Conversation]): TurnContext = {
    val topic = TopicEntry(
      id = sigil.conversation.Topic.id(s"topic-$convId"),
      label = "test",
      summary = "test"
    )
    val conv = Conversation(_id = convId, topics = List(topic))
    TestSigil.withDB(_.conversations.transaction(_.upsert(conv))).sync()
    TurnContext(
      sigil = TestSigil,
      chain = List(TestUser, TestAgent),
      conversation = conv,
      turnInput = TurnInput(ConversationView(
        conversationId = convId
      )),
      model = TestSigil.defaultTestModel
    )
  }

  private def reseed(): Unit = {
    TestSigil.reset()
    TestSigil.setAccessibleSpaces(_ => Task.pure(Set(GlobalSpace)))
    TestSigil.withDB(_.memories.transaction { tx =>
      tx.list.flatMap(rows => Task.sequence(rows.map(r => tx.delete(r._id))).unit)
    }).sync()
  }

  private def seed(key: String, fact: String, pinned: Boolean = false): Task[ContextMemory] =
    TestSigil.persistMemory(ContextMemory(
      fact = fact,
      label = key,
      summary = fact,
      key = Some(key),
      source = MemorySource.Explicit,
      spaceId = GlobalSpace,
      pinned = pinned
    ))

  private def reload(id: Id[ContextMemory]): Task[Option[ContextMemory]] =
    TestSigil.withDB(_.memories.transaction(_.get(id)))

  /** The recoverable-failure body off the settling [[ToolDelta]]. */
  private def failureBody(signals: List[sigil.signal.Signal]): String =
    signals.collectFirst {
      case d: ToolDelta => d.outcome.collect { case ToolOutcome.Failure(body, _) => body }
    }.flatten.getOrElse(
      fail(s"expected a Failure ToolDelta; saw: ${signals.map(_.getClass.getSimpleName).mkString(", ")}"))

  "PinMemoryTool" should {
    "promote an unpinned memory to pinned" in {
      reseed()
      val convId = Conversation.id(s"pin-${rapid.Unique()}")
      val ctx = makeContext(convId)
      for {
        m       <- seed("k.color", "User prefers blue.")
        _        = m.pinned shouldBe false
        events  <- PinMemoryTool.execute(PinMemoryInput(key = "k.color"), ctx, Event.id()).toList
        after   <- reload(m._id)
      } yield {
        after.map(_.pinned) shouldBe Some(true)
        events should have size 1
      }
    }

    "be a no-op when the memory is already pinned" in {
      reseed()
      val convId = Conversation.id(s"pin-noop-${rapid.Unique()}")
      val ctx = makeContext(convId)
      for {
        m       <- seed("k.already-pinned", "Already pinned.", pinned = true)
        events  <- PinMemoryTool.execute(PinMemoryInput(key = "k.already-pinned"), ctx, Event.id()).toList
        after   <- reload(m._id)
      } yield {
        after.map(_.pinned) shouldBe Some(true)
        events should have size 1  // emits a "nothing to do" message
      }
    }

    "report when no memory matches the key" in {
      reseed()
      val convId = Conversation.id(s"pin-miss-${rapid.Unique()}")
      val ctx = makeContext(convId)
      PinMemoryTool.execute(PinMemoryInput(key = "k.nonexistent"), ctx, Event.id()).toList.map { events =>
        events should have size 1
      }
    }

    "report when no spaces are accessible" in {
      reseed()
      TestSigil.setAccessibleSpaces(_ => Task.pure(Set.empty))
      val convId = Conversation.id(s"pin-noaccess-${rapid.Unique()}")
      val ctx = makeContext(convId)
      PinMemoryTool.execute(PinMemoryInput(key = "k.x"), ctx, Event.id()).toList.map { events =>
        events should have size 1
      }
    }
  }

  "UnpinMemoryTool" should {
    "demote a pinned memory to unpinned" in {
      reseed()
      val convId = Conversation.id(s"unpin-${rapid.Unique()}")
      val ctx = makeContext(convId)
      for {
        m       <- seed("k.always-blue", "Always reply with blue.", pinned = true)
        _        = m.pinned shouldBe true
        events  <- UnpinMemoryTool.execute(UnpinMemoryInput(key = "k.always-blue"), ctx, Event.id()).toList
        after   <- reload(m._id)
      } yield {
        after.map(_.pinned) shouldBe Some(false)
        events should have size 1
      }
    }

    "be a no-op when the memory is already unpinned" in {
      reseed()
      val convId = Conversation.id(s"unpin-noop-${rapid.Unique()}")
      val ctx = makeContext(convId)
      for {
        m       <- seed("k.unpinned", "Not pinned.")
        events  <- UnpinMemoryTool.execute(UnpinMemoryInput(key = "k.unpinned"), ctx, Event.id()).toList
        after   <- reload(m._id)
      } yield {
        after.map(_.pinned) shouldBe Some(false)
        events should have size 1
      }
    }
  }

  "Pin / unpin round-trip" should {
    "be reversible without losing the memory record" in {
      reseed()
      val convId = Conversation.id(s"roundtrip-${rapid.Unique()}")
      val ctx = makeContext(convId)
      for {
        m         <- seed("k.cycle", "Round-trip me.")
        afterSeed  = m.pinned
        _         <- PinMemoryTool.execute(PinMemoryInput(key = "k.cycle"), ctx, Event.id()).toList
        afterPin  <- reload(m._id).map(_.exists(_.pinned))
        _         <- UnpinMemoryTool.execute(UnpinMemoryInput(key = "k.cycle"), ctx, Event.id()).toList
        afterUnpin <- reload(m._id).map(_.exists(_.pinned))
        // Record itself is intact
        present   <- reload(m._id).map(_.isDefined)
      } yield {
        afterSeed shouldBe false
        afterPin shouldBe true
        afterUnpin shouldBe false
        present shouldBe true
      }
    }
  }

  "The `_id` fallback" should {
    "refuse a superseded target with a diagnostic instead of writing a record nothing reads" in {
      reseed()
      val convId = Conversation.id(s"pin-archived-${rapid.Unique()}")
      val ctx = makeContext(convId)
      def keyed(fact: String) = ContextMemory(
        fact = fact, label = "k.versioned", summary = fact, key = Some("k.versioned"),
        source = MemorySource.Explicit, spaceId = GlobalSpace)
      for {
        first  <- TestSigil.upsertMemoryByKey(keyed("The staging URL is a.example.com."))
        _      <- TestSigil.upsertMemoryByKey(keyed("The staging URL is b.example.com."))
        // The agent passes the archived version's raw id.
        signals <- PinMemoryTool.execute(PinMemoryInput(key = first.memory._id.value), ctx, Event.id()).toList
        after   <- reload(first.memory._id)
      } yield {
        failureBody(signals) should include("superseded")
        // And nothing was written.
        after.map(_.pinned) shouldBe Some(false)
      }
    }

    "refuse a rejected target" in {
      reseed()
      val convId = Conversation.id(s"pin-rejected-${rapid.Unique()}")
      val ctx = makeContext(convId)
      for {
        m       <- seed("k.rejected", "A fact the user disowned.")
        _       <- TestSigil.rejectMemory(m._id)
        signals <- PinMemoryTool.execute(PinMemoryInput(key = m._id.value), ctx, Event.id()).toList
        after   <- reload(m._id)
      } yield {
        failureBody(signals) should include("Rejected")
        after.map(_.pinned) shouldBe Some(false)
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
