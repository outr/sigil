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
 * Every topic-bearing Event carries the server-canonical
 * `topicId` / `topicIndex` pair — the topic's position in the
 * conversation's topic stack at emission time.
 *
 * Verifies:
 *   1. A Message published with `topicIndex = 0` (default) but a
 *      `topicId` that maps to position 2 in the stack is
 *      canonicalized: persisted record has `topicIndex = 2`.
 *   2. An unrelated/wrong `topicIndex` (e.g. client pushed an old
 *      stamped index) is overwritten by the canonical value.
 *   3. A `topicId` not on the stack (client placeholder — clients
 *      don't track the topic stack) is re-homed onto the current
 *      topic: both id and index are replaced.
 *   4. Events with the SAME conversation but different topic-bearing
 *      types (Message, ToolInvoke, ModeChange, RouteResolved) all
 *      canonicalize — including ControlPlaneEvent subtypes.
 *   5. A TopicChange's own index reflects the post-change stack: a
 *      Switch to a brand-new topic carries the position it is about
 *      to occupy; a switch-back carries the target's existing
 *      position.
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

    "re-home an off-stack topicId (client placeholder) onto the current topic" in {
      // Clients don't track the topic stack — they stamp a placeholder
      // id and trust the server to resolve the real topic. Without the
      // re-home, every client-authored event stays at palette index 0
      // forever, no matter which topic the conversation is on.
      for {
        conv <- freshConv(List("Alpha", "Beta", "Gamma"))
        msg = Message(
          participantId = TestUser,
          conversationId = conv._id,
          topicId = Topic.id(s"topic-${conv._id.value}"),
          topicIndex = 5,
          content = Vector(ResponseContent.Text("placeholder-stamped")),
          state = EventState.Complete
        )
        _ <- TestSigil.publish(msg)
        loaded <- fetchPersisted(msg._id)
      } yield {
        loaded.get.topicId shouldBe conv.topics.last.id
        loaded.get.topicIndex shouldBe 2
      }
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

    "canonicalize ControlPlaneEvent subtypes (RouteResolved)" in {
      for {
        conv <- freshConv(List("Alpha", "Beta"))
        rr = sigil.event.RouteResolved(
          participantId = TestAgent,
          conversationId = conv._id,
          topicId = conv.topics(1).id,
          userMessageId = None,
          inferredWorkType = None,
          inferredComplexity = None,
          candidateChain = Nil,
          chosenModelId = sigil.db.Model.id("test", "route"),
          skipReasons = Map.empty,
          classifierLatencyMs = None,
          escalationCount = 0
        )
        _ <- TestSigil.publish(rr)
        loaded <- fetchPersisted(rr._id)
      } yield loaded.get.topicIndex shouldBe 1
    }

    "stamp a Switch-to-new TopicChange with the position it is about to occupy" in {
      // The stack projection applies AFTER the transform, so the new
      // entry isn't on the stack yet when the index is computed — the
      // divider must still carry the NEW topic's ordinal, not the
      // off-stack fallback.
      for {
        conv <- freshConv(List("Alpha", "Beta"))
        newTopic <- TestSigil.withDB(_.topics.transaction(_.upsert(Topic(
          conversationId = conv._id,
          label = "Gamma",
          summary = "a brand new subject",
          createdBy = TestAgent
        ))))
        tc = sigil.event.TopicChange(
          kind = sigil.event.TopicChangeKind.Switch(previousTopicId = conv.topics.last.id),
          newLabel = newTopic.label,
          newSummary = newTopic.summary,
          participantId = TestAgent,
          conversationId = conv._id,
          topicId = newTopic._id,
          state = EventState.Complete
        )
        _ <- TestSigil.publish(tc)
        loadedTc <- fetchPersisted(tc._id)
        loadedConv <- TestSigil.withDB(_.conversations.transaction(_.get(conv._id)))
      } yield {
        loadedTc.get.topicIndex shouldBe 2
        // ...and the projection did put it exactly there.
        loadedConv.get.topics.indexWhere(_.id == newTopic._id) shouldBe 2
      }
    }

    "stamp a switch-back TopicChange with the target's existing position" in {
      for {
        conv <- freshConv(List("Alpha", "Beta", "Gamma"))
        prior = conv.topics.head
        tc = sigil.event.TopicChange(
          kind = sigil.event.TopicChangeKind.Switch(previousTopicId = conv.topics.last.id),
          newLabel = prior.label,
          newSummary = prior.summary,
          participantId = TestAgent,
          conversationId = conv._id,
          topicId = prior.id,
          topicIndex = 7,
          state = EventState.Complete
        )
        _ <- TestSigil.publish(tc)
        loadedTc <- fetchPersisted(tc._id)
      } yield loadedTc.get.topicIndex shouldBe 0
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
