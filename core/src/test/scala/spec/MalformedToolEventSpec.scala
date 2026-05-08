package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.conversation.{Conversation, ContextFrame, FrameBuilder, Topic, TopicEntry}
import sigil.event.{Event, Message, MessageRole, MessageVisibility}
import sigil.signal.EventState
import sigil.tool.model.ResponseContent

/**
 * Coverage for sigil bug #64 — malformed Tool-role events
 * (missing `origin`) are now:
 *   1. Refused at write time by `Sigil.publish` /
 *      `publishHistoricalSilent` with a clear stack trace
 *      pointing at the offending caller.
 *   2. Surfaced as a synthetic agents-only Text frame at read
 *      time (FrameBuilder.computeFrame), so a single historical
 *      bad event can't permanently brick a conversation.
 */
class MalformedToolEventSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private def freshConversation(label: String): Task[Conversation] = {
    val convId = Conversation.id(s"malformed-$label-${rapid.Unique()}")
    val topic  = TopicEntry(id = Topic.id(s"topic-$convId"), label = "test", summary = "test")
    val conv   = Conversation(_id = convId, topics = List(topic))
    TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
  }

  private def buildBadToolMessage(conv: Conversation): Message =
    Message(
      participantId  = TestAgent,
      conversationId = conv._id,
      topicId        = conv.currentTopicId,
      content        = Vector(ResponseContent.Text("rogue")),
      role           = MessageRole.Tool,
      state          = EventState.Complete,
      visibility     = MessageVisibility.Agents
      // origin = None — this is the invariant violation.
    )

  "Sigil.publish — write-side gate" should {

    "reject a Tool-role event without origin, with diagnostic stack trace" in {
      for {
        conv <- freshConversation("publish")
        bad  = buildBadToolMessage(conv)
        result <- TestSigil.publish(bad).map(_ => Right(())).handleError(t => Task.pure(Left(t)))
        listed <- TestSigil.withDB(_.events.transaction(_.list))
      } yield {
        result match {
          case Left(t) =>
            t shouldBe an[IllegalStateException]
            t.getMessage should include("Refusing to publish")
            t.getMessage should include("origin")
          case Right(_) => fail("expected IllegalStateException, got success")
        }
        // Critically: the bad event MUST NOT have landed in the
        // event store. The DB stays clean.
        listed.exists(_._id == bad._id) shouldBe false
      }
    }

    "accept a Tool-role event WITH origin (regression guard for the happy path)" in {
      for {
        conv <- freshConversation("publish-happy")
        ok = buildBadToolMessage(conv).copy(origin = Some(Event.id()))
        _ <- TestSigil.publish(ok)
        listed <- TestSigil.withDB(_.events.transaction(_.list))
      } yield listed.exists(_._id == ok._id) shouldBe true
    }
  }

  "Sigil.publishHistoricalSilent — write-side gate" should {

    "reject a batch containing any malformed Tool-role event" in {
      for {
        conv <- freshConversation("hist")
        bad   = buildBadToolMessage(conv)
        good  = Message(
                  participantId  = TestUser,
                  conversationId = conv._id,
                  topicId        = conv.currentTopicId,
                  content        = Vector(ResponseContent.Text("hi")),
                  state          = EventState.Complete
                )
        result <- TestSigil.publishHistoricalSilent(Seq(good, bad), conv._id)
                    .map(_ => Right(())).handleError(t => Task.pure(Left(t)))
        listed <- TestSigil.withDB(_.events.transaction(_.list))
      } yield {
        result.isLeft shouldBe true
        // Whole-batch reject — neither event lands.
        listed.exists(_._id == bad._id)  shouldBe false
        listed.exists(_._id == good._id) shouldBe false
      }
    }
  }

  "FrameBuilder — read-side recovery" should {

    "emit a synthetic agents-only frame for a malformed Tool-role event instead of throwing" in Task {
      val convId = Conversation.id("framebuilder-recover")
      val topicId = Topic.id("topic-recover")
      val bad = Message(
        participantId  = TestAgent,
        conversationId = convId,
        topicId        = topicId,
        content        = Vector(ResponseContent.Text("rogue")),
        role           = MessageRole.Tool,
        state          = EventState.Complete,
        visibility     = MessageVisibility.Agents
      )
      val frame = FrameBuilder.computeFrame(bad)
      // Doesn't throw.
      frame should not be empty
      frame.get shouldBe a[ContextFrame.Text]
      val text = frame.get.asInstanceOf[ContextFrame.Text]
      text.content should include("skipped malformed Tool-role event")
      text.visibility shouldBe MessageVisibility.Agents
    }

    "still emit the proper ToolResult frame when origin IS set (happy path)" in Task {
      val convId = Conversation.id("framebuilder-happy")
      val topicId = Topic.id("topic-happy")
      val parent = Event.id()
      val ok = Message(
        participantId  = TestAgent,
        conversationId = convId,
        topicId        = topicId,
        content        = Vector(ResponseContent.Text("real result")),
        role           = MessageRole.Tool,
        state          = EventState.Complete,
        visibility     = MessageVisibility.Agents,
        origin         = Some(parent)
      )
      val frame = FrameBuilder.computeFrame(ok)
      frame.get shouldBe a[ContextFrame.ToolResult]
      val tr = frame.get.asInstanceOf[ContextFrame.ToolResult]
      tr.callId shouldBe parent
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
