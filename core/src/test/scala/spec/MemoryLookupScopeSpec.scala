package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.{GlobalSpace, SpaceId, TurnContext}
import sigil.conversation.{ContextMemory, Conversation, ConversationView, MemorySource, TopicEntry, TurnInput}
import sigil.event.{Event, ToolOutcome}
import sigil.signal.ToolDelta
import sigil.tool.discovery.CapabilityType
import sigil.tool.model.{LookupInput, LookupOutput}
import sigil.tool.util.LookupTool

/**
 * Coverage for `lookup(capabilityType = Memory, name = …)`'s
 * resolution rules. A memory key is a stable slot name, so the same
 * key exists in every tenant's space — resolution has to be scoped by
 * the caller's authorization, has to prefer the CURRENT version of a
 * versioned slot, and has to honour the shared recall gate.
 */
class MemoryLookupScopeSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {

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
      turnInput = TurnInput(ConversationView(conversationId = convId)),
      model = TestSigil.defaultTestModel
    )
  }

  private def reseed(accessible: Set[SpaceId]): Unit = {
    TestSigil.reset()
    TestSigil.setAccessibleSpaces(_ => Task.pure(accessible))
    TestSigil.withDB(_.memories.transaction { tx =>
      tx.list.flatMap(rows => Task.sequence(rows.map(r => tx.delete(r._id))).unit)
    }).sync()
  }

  private def seed(key: String, fact: String, in: SpaceId): Task[ContextMemory] =
    TestSigil.persistMemory(ContextMemory(
      fact = fact,
      label = key,
      summary = fact,
      key = Some(key),
      source = MemorySource.Explicit,
      spaceId = in
    ))

  private def lookup(name: String, ctx: TurnContext): Task[LookupOutput] =
    LookupTool.execute(LookupInput(capabilityType = CapabilityType.Memory, name = name), ctx, Event.id())
      .toList
      .map { signals =>
        signals.collectFirst {
          case d: ToolDelta if d.outcome.contains(ToolOutcome.Success) =>
            d.output.collect { case o: LookupOutput => o }
        }.flatten.getOrElse(
          fail(s"expected a Success ToolDelta carrying LookupOutput; saw: ${signals.map(_.getClass.getSimpleName).mkString(", ")}"))
      }

  private def factOf(out: LookupOutput): Option[String] = out match {
    case LookupOutput.Found(_, _, payload, _) => payload.get("fact").map(_.asString)
    case _                                    => None
  }

  "lookup(Memory)" should {
    "never disclose a record from a space the caller cannot access" in {
      // The same slot exists in two tenants' spaces; only one is ours.
      reseed(Set(MemoryTestSpace))
      for {
        _     <- seed("user.email", "theirs@example.com", TestSpace)
        mine  <- seed("user.email", "mine@example.com", MemoryTestSpace)
        ctx    = makeContext(Conversation.id(s"lookup-scope-${rapid.Unique()}"))
        found <- lookup("user.email", ctx)
      } yield {
        factOf(found) shouldBe Some("mine@example.com")
        // And the id fallback is scoped the same way.
        succeed
      }
    }

    "report NotFound rather than another space's record when the caller has none of its own" in {
      reseed(Set(MemoryTestSpace))
      for {
        theirs <- seed("user.email", "theirs@example.com", TestSpace)
        ctx     = makeContext(Conversation.id(s"lookup-denied-${rapid.Unique()}"))
        byKey  <- lookup("user.email", ctx)
        byId   <- lookup(theirs._id.value, ctx)
      } yield {
        byKey shouldBe a[LookupOutput.NotFound]
        byId shouldBe a[LookupOutput.NotFound]
      }
    }

    "resolve a versioned key to the current version, not an archived one" in {
      reseed(Set(MemoryTestSpace))
      def keyed(fact: String) = ContextMemory(
        fact = fact, label = "deploy", summary = fact, key = Some("ops.deploy"),
        source = MemorySource.Explicit, spaceId = MemoryTestSpace)
      for {
        _       <- TestSigil.upsertMemoryByKey(keyed("The deploy target is us-east-1."))
        _       <- TestSigil.upsertMemoryByKey(keyed("The deploy target is eu-west-2."))
        current <- TestSigil.upsertMemoryByKey(keyed("The deploy target is ap-south-1."))
        ctx      = makeContext(Conversation.id(s"lookup-version-${rapid.Unique()}"))
        found   <- lookup("ops.deploy", ctx)
      } yield {
        factOf(found) shouldBe Some("The deploy target is ap-south-1.")
        found match {
          case LookupOutput.Found(_, _, payload, _) => payload.get("_id").map(_.asString) shouldBe Some(current.memory._id.value)
          case other                                => fail(s"expected Found, got $other")
        }
      }
    }

    "refuse a record the recall gate excludes, even by direct id" in {
      reseed(Set(MemoryTestSpace))
      for {
        m     <- seed("user.nickname", "Call me Ace.", MemoryTestSpace)
        ctx    = makeContext(Conversation.id(s"lookup-gate-${rapid.Unique()}"))
        before<- lookup("user.nickname", ctx)
        _     <- TestSigil.rejectMemory(m._id)
        byKey <- lookup("user.nickname", ctx)
        byId  <- lookup(m._id.value, ctx)
      } yield {
        factOf(before) shouldBe Some("Call me Ace.")
        byKey shouldBe a[LookupOutput.NotFound]
        byId shouldBe a[LookupOutput.NotFound]
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
