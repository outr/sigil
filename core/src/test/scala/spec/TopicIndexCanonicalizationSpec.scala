package spec

import lightdb.id.Id
import lightdb.time.Timestamp
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.conversation.{Conversation, Topic, TopicEntry}
import sigil.event.{Message, ModeChange, ToolInvoke}
import sigil.signal.EventState
import sigil.tool.ToolName
import sigil.tool.model.ResponseContent

/**
 * Coverage for sigil bug #80 — every topic-bearing Event carries a
 * server-canonical `topicIndex: Int` corresponding to its
 * `topicId`'s position in the conversation's topic stack at
 * emission time.
 *
 * Verifies:
 *   1. A Message published with `topicIndex = 0` (default) but a
 *      `topicId` that maps to position 2 in the stack is
 *      canonicalized: persisted record has `topicIndex = 2`.
 *   2. An unrelated/wrong `topicIndex` (e.g. client pushed an old
 *      stamped index) is overwritten by the canonical value.
 *   3. A `topicId` not on the stack falls back to `0` rather than
 *      `-1` (downstream palette indexing stays in-bounds).
 *   4. Events with the SAME conversation but different topic-bearing
 *      types (Message, ToolInvoke, ModeChange) all canonicalize.
 */
class TopicIndexCanonicalizationSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private def freshConv(topicLabels: List[String]): Task[Conversation] = {
    val convId = Conversation.id(s"topic-idx-${rapid.Unique()}")
    val topics = topicLabels.zipWithIndex.map { case (label, i) =>
      TopicEntry(id = Topic.id(s"topic-$convId-$i"), label = label, summary = label)
    }
    val conv = Conversation(_id = convId, topics = topics)
    TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
  }

  private def fetchPersisted(eventId: Id[sigil.event.Event]): Task[Option[sigil.event.Event]] =
    TestSigil.withDB(_.events.transaction(_.get(eventId)))

  "TopicIndexCanonicalizingTransform" should {

    "canonicalize topicIndex from the conversation's topic stack on a Message" in {
      for {
        conv <- freshConv(List("Topic A", "Topic B", "Topic C"))
        msg = Message(
          participantId = TestUser,
          conversationId = conv._id,
          topicId = conv.topics(2).id, // 3rd topic on the stack
          topicIndex = 0, // wrong / stale
          content = Vector(ResponseContent.Text("hello")),
          state = EventState.Complete
        )
        _ <- TestSigil.publish(msg)
        loaded <- fetchPersisted(msg._id)
      } yield {
        loaded shouldBe defined
        loaded.get.topicIndex shouldBe 2
      }
    }

    "overwrite a wrong topicIndex (client pushed a stale value)" in {
      for {
        conv <- freshConv(List("First", "Second"))
        msg = Message(
          participantId = TestUser,
          conversationId = conv._id,
          topicId = conv.topics.head.id, // index 0
          topicIndex = 99, // wildly wrong
          content = Vector(ResponseContent.Text("hi")),
          state = EventState.Complete
        )
        _ <- TestSigil.publish(msg)
        loaded <- fetchPersisted(msg._id)
      } yield loaded.get.topicIndex shouldBe 0
    }

    "fall back to 0 when topicId isn't on the stack (defensive)" in {
      for {
        conv <- freshConv(List("Only"))
        msg = Message(
          participantId = TestUser,
          conversationId = conv._id,
          topicId = Topic.id("orphan-topic-id"),
          topicIndex = 5,
          content = Vector(ResponseContent.Text("orphan")),
          state = EventState.Complete
        )
        _ <- TestSigil.publish(msg)
        loaded <- fetchPersisted(msg._id)
      } yield loaded.get.topicIndex shouldBe 0
    }

    "canonicalize across multiple event types in the same conversation" in {
      for {
        conv <- freshConv(List("Alpha", "Beta", "Gamma"))
        topicIdMid = conv.topics(1).id // index 1
        msg = Message(
          participantId = TestUser,
          conversationId = conv._id,
          topicId = topicIdMid,
          content = Vector(ResponseContent.Text("mid")),
          state = EventState.Complete
        )
        ti = ToolInvoke(
          toolName = ToolName("test"),
          participantId = TestUser,
          conversationId = conv._id,
          topicId = topicIdMid,
          state = EventState.Complete
        )
        mc = ModeChange(
          mode = sigil.provider.ConversationMode,
          participantId = TestUser,
          conversationId = conv._id,
          topicId = topicIdMid,
          timestamp = Timestamp(),
          state = EventState.Complete
        )
        _ <- TestSigil.publish(msg)
        _ <- TestSigil.publish(ti)
        _ <- TestSigil.publish(mc)
        loadedMsg <- fetchPersisted(msg._id)
        loadedTi <- fetchPersisted(ti._id)
        loadedMc <- fetchPersisted(mc._id)
      } yield {
        loadedMsg.get.topicIndex shouldBe 1
        loadedTi.get.topicIndex shouldBe 1
        loadedMc.get.topicIndex shouldBe 1
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
