package spec

import lightdb.id.Id
import lightdb.time.Timestamp
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.AsyncTaskSpec
import sigil.conversation.Conversation
import sigil.event.{Event, Message}
import sigil.signal.EventState
import sigil.tool.model.ResponseContent
import sigil.transport.SigilDbEventLog

/**
 * Coverage for the `EventLog` adapter that surfaces `SigilDB.events`
 * to spice's `DurableSocketServer`. Verifies:
 *
 *   - `append` is a no-op on the persistence side and returns the
 *     event's `timestamp.value` as the seq.
 *   - `replay` filters by conversationId, returns only events newer
 *     than the cursor, and pairs them with their timestamps.
 */
class SigilDbEventLogSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val log = new SigilDbEventLog(TestSigil)

  private def freshConv(suffix: String): Id[Conversation] =
    Conversation.id(s"eventlog-$suffix-${rapid.Unique()}")

  private def msg(convId: Id[Conversation], ts: Long, text: String): Message =
    Message(
      participantId = TestUser,
      conversationId = convId,
      topicId = TestTopicId,
      content = Vector(ResponseContent.Text(text)),
      state = EventState.Complete,
      timestamp = Timestamp(ts)
    )

  "SigilDbEventLog.append" should {
    "return event.timestamp.value as the seq without writing again" in {
      val convId = freshConv("append")
      val event = msg(convId, 4242L, "doesnt matter")
      for {
        // The event is NOT pre-persisted — we want to assert append doesn't
        // accidentally write either.
        seq <- log.append(convId, event)
        // SigilDB.events should still NOT contain the event after append.
        existing <- TestSigil.withDB(_.events.transaction(_.list)).map { all =>
          all.exists(_._id == event._id)
        }
      } yield {
        seq shouldBe 4242L
        existing shouldBe false
      }
    }
  }

  "SigilDbEventLog.replay" should {

    "return only events for the given channel newer than afterSeq, ordered chronologically" in {
      val convA = freshConv("replay-a")
      val convB = freshConv("replay-b")
      for {
        _ <- TestSigil.publish(msg(convA, 100L, "a-old"))
        _ <- TestSigil.publish(msg(convA, 200L, "a-mid"))
        _ <- TestSigil.publish(msg(convA, 300L, "a-new"))
        _ <- TestSigil.publish(msg(convB, 250L, "b-noise"))
        replayed <- log.replay(convA, afterSeq = 150L)
      } yield {
        // Only convA's a-mid (200) and a-new (300) qualify.
        val texts = replayed.collect { case (seq, m: Message) =>
          (seq, m.content.collect { case ResponseContent.Text(t) => t }.mkString)
        }
        texts shouldBe List((200L, "a-mid"), (300L, "a-new"))
      }
    }

    "return an empty list when no events exist for the channel" in {
      val convId = freshConv("empty")
      for {
        replayed <- log.replay(convId, afterSeq = 0L)
      } yield replayed shouldBe List.empty
    }
  }
}
