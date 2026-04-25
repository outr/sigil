package spec

import lightdb.id.Id
import lightdb.time.Timestamp
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.AsyncTaskSpec
import sigil.conversation.Conversation
import sigil.event.{Event, Message}
import sigil.signal.{ContentDelta, ContentKind, EventState, MessageDelta}
import sigil.tool.model.ResponseContent
import sigil.transport.DurableSocketSink
import fabric.rw.*
import spice.http.WebSocketListener
import spice.http.durable.{DurableSocket, DurableSocketConfig, DurableSession, InMemoryEventLog}

/**
 * Coverage for the spice [[DurableSocketSink]]:
 *
 *   - Events go through `protocol.push`, which appends to the
 *     outbound event log (so they're resume-able).
 *   - Deltas go through `protocol.sendEphemeral`, which bypasses the
 *     event log (in-flight state isn't replayed).
 *
 * No real WebSocket is bound — the underlying `sendRaw` is a no-op
 * when no socket is attached, so we observe behavior through the
 * event-log side of the protocol.
 */
class DurableSocketSinkSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val convId: Id[Conversation] = Conversation.id("dss-spec")

  private def makeSink: (DurableSocketSink[String, String], InMemoryEventLog[String, Event]) = {
    val log = new InMemoryEventLog[String, Event]
    val protocol = new DurableSocket[String, Event, String](
      config = DurableSocketConfig(),
      outboundLog = log,
      initialChannelId = "channel-a"
    )
    val listener = reactify.Var[WebSocketListener](new WebSocketListener)
    val session = DurableSession[String, Event, String](
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

    "append Events to the protocol's outbound event log via push" in {
      val (sink, log) = makeSink
      val ev = msg("durable", 100L)
      for {
        _ <- sink.push(ev)
        replayed <- log.replay("channel-a", afterSeq = 0L)
      } yield {
        replayed should have size 1
        val (seq, replayedEvent) = replayed.head
        seq should be > 0L
        replayedEvent shouldBe ev
      }
    }

    "send Deltas via sendEphemeral so they do NOT land in the durable event log" in {
      val (sink, log) = makeSink
      val ev = msg("anchor", 200L)
      val delta = MessageDelta(
        target = ev._id,
        conversationId = convId,
        content = Some(ContentDelta(kind = ContentKind.Text, arg = None, complete = false, delta = "tk"))
      )
      for {
        _ <- sink.push(ev)    // durable
        _ <- sink.push(delta) // ephemeral — should NOT add a log entry
        replayed <- log.replay("channel-a", afterSeq = 0L)
      } yield {
        replayed should have size 1
        replayed.head._2 shouldBe ev
      }
    }

    "be safe to close" in {
      val (sink, _) = makeSink
      for {
        _ <- sink.close
      } yield succeed
    }
  }
}
