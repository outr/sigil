package spec

import fabric.rw.*
import lightdb.id.Id
import lightdb.time.Timestamp
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.AsyncTaskSpec
import sigil.conversation.Conversation
import sigil.event.{Event, Message, ToolInvoke}
import sigil.signal.EventState
import sigil.tool.{ToolName}
import sigil.tool.model.ResponseContent

/**
 * Coverage for sigil bug #22 — `Event.source: Option[String]`. Confirms
 * the field defaults to None on every subtype, round-trips cleanly
 * through the polytype RW, and lets bulk-import callers stamp an
 * attribution string that survives persistence + view rebuild via
 * [[sigil.Sigil.publishHistorical]].
 */
class EventSourceAttributionSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private def freshConvId(suffix: String): Id[Conversation] =
    Conversation.id(s"event-source-$suffix-${rapid.Unique()}")

  "Event.source" should {
    "default to None on framework Event subtypes" in {
      val convId = freshConvId("default")
      val msg = Message(participantId = TestUser, conversationId = convId, topicId = TestTopicId)
      val ti = ToolInvoke(
        toolName = ToolName("noop"),
        participantId = TestUser,
        conversationId = convId,
        topicId = TestTopicId
      )
      msg.source shouldBe None
      ti.source shouldBe None
      rapid.Task.unit.map(_ => succeed)
    }

    "round-trip Some(source) through the Event polytype RW" in {
      val convId = freshConvId("roundtrip")
      val original = Message(
        participantId = TestUser,
        conversationId = convId,
        topicId = TestTopicId,
        content = Vector(ResponseContent.Text("imported")),
        state = EventState.Complete,
        timestamp = Timestamp(1_000_000L),
        source = Some("claude-code")
      )
      val erased: Event = original
      val rw = summon[RW[Event]]
      val json = rw.read(erased)
      val back = rw.write(json)
      back match {
        case m: Message =>
          m.source shouldBe Some("claude-code")
        case other => fail(s"expected Message after round-trip, got $other")
      }
      rapid.Task.unit.map(_ => succeed)
    }

    "persist via publishHistorical and survive view rebuild" in {
      val convId = freshConvId("publishhistorical")
      val events: Seq[Event] = (1 to 5).map { i =>
        Message(
          participantId = TestUser,
          conversationId = convId,
          topicId = TestTopicId,
          content = Vector(ResponseContent.Text(s"line-$i")),
          state = EventState.Complete,
          timestamp = Timestamp(2_000_000L + i),
          source = Some("claude-code")
        )
      }
      for {
        _    <- TestSigil.publishHistorical(events, convId)
        rows <- TestSigil.withDB(_.events.transaction(_.list))
      } yield {
        val mine = rows.collect { case m: Message if m.conversationId == convId => m }
        mine should have size 5
        mine.map(_.source).distinct shouldBe List(Some("claude-code"))
      }
    }

    "leave source = None on Sigil's own publish() path (no auto-stamping)" in {
      val convId = freshConvId("native")
      val msg = Message(
        participantId = TestUser,
        conversationId = convId,
        topicId = TestTopicId,
        content = Vector(ResponseContent.Text("native")),
        state = EventState.Complete
      )
      for {
        _    <- TestSigil.publish(msg)
        rows <- TestSigil.withDB(_.events.transaction(_.list))
      } yield {
        val mine = rows.collect { case m: Message if m.conversationId == convId => m }
        mine should have size 1
        mine.head.source shouldBe None
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
