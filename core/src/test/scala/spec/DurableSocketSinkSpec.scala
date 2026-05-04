package spec

import lightdb.id.Id
import lightdb.time.Timestamp
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.AsyncTaskSpec
import sigil.conversation.Conversation
import sigil.event.Message
import sigil.signal.{ContentKind, EventState, MessageContentDelta, MessageDelta, Signal}
import sigil.tool.model.ResponseContent
import sigil.transport.DurableSocketSink
import fabric.rw.*
import spice.http.WebSocketListener
import spice.http.durable.{DurableSocket, DurableSocketConfig, DurableSession, InMemoryEventLog}

/**
 * Coverage for the spice [[DurableSocketSink]] after the
 * widen-channel-to-Signal refactor:
 *
 *   - Every Signal subtype (Event, Delta, Notice) goes through
 *     `protocol.push`, which appends to the outbound event log.
 *     `sendEphemeral` is no longer used by Sigil's outbound path —
 *     it's reserved for non-Signal wire-protocol housekeeping
 *     (ping/pong) outside the framework's purview.
 *
 * No real WebSocket is bound — the underlying `sendRaw` is a no-op
 * when no socket is attached, so we observe behavior through the
 * event-log side of the protocol.
 */
class DurableSocketSinkSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val convId: Id[Conversation] = Conversation.id("dss-spec")

  private def makeSink: (DurableSocketSink[String, String], InMemoryEventLog[String, Signal]) = {
    val log = new InMemoryEventLog[String, Signal]
    val protocol = new DurableSocket[String, Signal, String](
      config = DurableSocketConfig(),
      outboundLog = log,
      initialChannelId = "channel-a"
    )
    val listener = reactify.Var[WebSocketListener](new WebSocketListener)
    val session = DurableSession[String, Signal, String](
      clientId = "test-client",
      info = "info",
      protocol = protocol,
      listener = listener
    )
    (new DurableSocketSink[String, String](session), log)
  }

  private def msg(text: String, ts: Long): Message =
    Message(
      participantId = TestUser,
      conversationId = convId,
      topicId = TestTopicId,
      content = Vector(ResponseContent.Text(text)),
      state = EventState.Complete,
      timestamp = Timestamp(ts)
    )

  "DurableSocketSink" should {

    "push Events to the protocol's outbound log" in {
      val (sink, log) = makeSink
      val ev = msg("durable", 100L)
      for {
        _ <- sink.push(ev)
        replayed <- log.replay("channel-a", afterSeq = 0L)
      } yield {
        replayed should have size 1
        replayed.head._1 should be > 0L
        replayed.head._2 shouldBe ev
      }
    }

    "push Deltas to the same outbound log (no longer ephemeral)" in {
      val (sink, log) = makeSink
      val ev = msg("anchor", 200L)
      val delta = MessageDelta(
        target = ev._id,
        conversationId = convId,
        content = Some(MessageContentDelta(kind = ContentKind.Text, arg = None, complete = false, delta = "tk"))
      )
      for {
        _ <- sink.push(ev)
        _ <- sink.push(delta)
        replayed <- log.replay("channel-a", afterSeq = 0L)
      } yield {
        replayed.map(_._2) should contain theSameElementsAs Seq[Signal](ev, delta)
        // Strictly monotonic seqs — required by SequenceTracker on the receiving end.
        val seqs = replayed.map(_._1)
        seqs shouldBe seqs.sorted
        seqs.distinct.size shouldBe seqs.size
      }
    }

    "be safe to close" in {
      val (sink, _) = makeSink
      for {
        _ <- sink.close
      } yield succeed
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
