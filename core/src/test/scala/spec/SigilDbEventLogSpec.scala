package spec

import lightdb.id.Id
import lightdb.time.Timestamp
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.AsyncTaskSpec
import sigil.conversation.Conversation
import sigil.event.Message
import sigil.signal.{ContentKind, EventState, MessageContentDelta, MessageDelta}
import sigil.tool.model.ResponseContent
import sigil.transport.SigilDbEventLog

/**
 * Coverage for the `EventLog` adapter that surfaces `SigilDB.events`
 * to spice's `DurableSocketServer`. The wire channel is typed over
 * the full [[sigil.signal.Signal]] sum, so this log accepts every
 * subtype on `append` even though only Events durably persist.
 *
 *   - `append` returns a strictly-monotonic per-channel seq, biased
 *     toward `event.timestamp.value` for Events and
 *     `System.currentTimeMillis()` for Deltas / Notices. Persistence
 *     is a no-op (Events already reach `SigilDB.events` through
 *     `Sigil.publish` ahead of this layer).
 *   - `replay` filters by conversationId + `timestamp > afterSeq`
 *     and returns Events only — Deltas / Notices are not durably
 *     replayable across server restart.
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
    "use event.timestamp.value as the seq without writing" in {
      val convId = freshConv("append")
      val event = msg(convId, 4242L, "doesnt matter")
      for {
        seq <- log.append(convId, event)
        existing <- TestSigil.withDB(_.events.transaction(_.list)).map { all =>
          all.exists(_._id == event._id)
        }
      } yield {
        seq shouldBe 4242L
        existing shouldBe false
      }
    }

    "produce a strictly monotonic seq for Deltas and Notices on the same channel" in {
      val convId = freshConv("monotonic")
      val ev = msg(convId, 1000L, "anchor")
      val delta = MessageDelta(
        target = ev._id,
        conversationId = convId,
        content = Some(MessageContentDelta(kind = ContentKind.Text, arg = None, complete = false, delta = "x"))
      )
      for {
        s1 <- log.append(convId, ev)
        s2 <- log.append(convId, delta)
        s3 <- log.append(convId, delta)
      } yield {
        s1 shouldBe 1000L
        s2 should be > s1
        s3 should be > s2
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

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
