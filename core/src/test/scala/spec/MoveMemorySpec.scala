package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.{GlobalSpace, SpaceId, TurnContext}
import sigil.conversation.{Conversation, ContextMemory, ConversationView, MemorySource, TopicEntry, TurnInput}
import sigil.tool.context.{MoveMemoryInput, MoveMemoryTool}

/**
 * Coverage for [[MoveMemoryTool]] — agent re-scoping a memory to a
 * different accessible [[sigil.SpaceId]] post-hoc.
 */
class MoveMemorySpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {

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
      conversationView = ConversationView(
        conversationId = convId,
        _id = ConversationView.idFor(convId)
      ),
      turnInput = TurnInput(ConversationView(
        conversationId = convId,
        _id = ConversationView.idFor(convId)
      ))
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
      key = key,
      source = MemorySource.Explicit,
      spaceId = in
    ))

  private def reload(id: Id[ContextMemory]): Task[Option[ContextMemory]] =
    TestSigil.withDB(_.memories.transaction(_.get(id)))

  "MoveMemoryTool" should {
    "move a memory from one accessible space to another" in {
      reseed(Set(GlobalSpace, TestSpace))
      val convId = Conversation.id(s"move-${rapid.Unique()}")
      val ctx = makeContext(convId)
      for {
        m       <- seed("k.move", "Memory to move.", in = GlobalSpace)
        _        = m.spaceId shouldBe GlobalSpace
        _       <- MoveMemoryTool.execute(MoveMemoryInput(key = "k.move", newSpace = TestSpace), ctx).toList
        after   <- reload(m._id)
      } yield {
        after.map(_.spaceId) shouldBe Some(TestSpace)
      }
    }

    "no-op when the memory is already in the target space" in {
      reseed(Set(GlobalSpace, TestSpace))
      val convId = Conversation.id(s"move-noop-${rapid.Unique()}")
      val ctx = makeContext(convId)
      for {
        m       <- seed("k.same-space", "Already in target.", in = TestSpace)
        events  <- MoveMemoryTool.execute(MoveMemoryInput(key = "k.same-space", newSpace = TestSpace), ctx).toList
        after   <- reload(m._id)
      } yield {
        after.map(_.spaceId) shouldBe Some(TestSpace)
        events should have size 1
      }
    }

    "refuse to move to a space the caller can't access" in {
      reseed(Set(GlobalSpace))  // TestSpace NOT accessible
      val convId = Conversation.id(s"move-noaccess-${rapid.Unique()}")
      val ctx = makeContext(convId)
      for {
        m       <- seed("k.no-access", "Try moving here.", in = GlobalSpace)
        events  <- MoveMemoryTool.execute(MoveMemoryInput(key = "k.no-access", newSpace = TestSpace), ctx).toList
        after   <- reload(m._id)
      } yield {
        after.map(_.spaceId) shouldBe Some(GlobalSpace)  // unchanged
        events should have size 1
      }
    }

    "report when no memory matches the key" in {
      reseed(Set(GlobalSpace, TestSpace))
      val convId = Conversation.id(s"move-miss-${rapid.Unique()}")
      val ctx = makeContext(convId)
      MoveMemoryTool.execute(MoveMemoryInput(key = "k.nothing", newSpace = TestSpace), ctx).toList.map { events =>
        events should have size 1
      }
    }

    "preserve key, _id, and pinned status across the move" in {
      reseed(Set(GlobalSpace, TestSpace))
      val convId = Conversation.id(s"move-preserve-${rapid.Unique()}")
      val ctx = makeContext(convId)
      for {
        m      <- TestSigil.persistMemory(ContextMemory(
          fact = "Pinned memory to move.",
          key = "k.pinned-move",
          source = MemorySource.Explicit,
          spaceId = GlobalSpace,
          pinned = true
        ))
        _      <- MoveMemoryTool.execute(MoveMemoryInput(key = "k.pinned-move", newSpace = TestSpace), ctx).toList
        after  <- reload(m._id)
      } yield {
        after.map(_._id) shouldBe Some(m._id)
        after.map(_.key) shouldBe Some("k.pinned-move")
        after.map(_.pinned) shouldBe Some(true)
        after.map(_.spaceId) shouldBe Some(TestSpace)
      }
    }
  }
}
