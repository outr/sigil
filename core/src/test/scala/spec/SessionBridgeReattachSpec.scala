package spec

import fabric.rw.*
import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.conversation.{Conversation, Topic, TopicEntry}
import sigil.event.Message
import sigil.signal.{EventState, Signal}
import sigil.tool.model.ResponseContent
import sigil.transport.{ResumeRequest, SessionBridge}
import spice.http.WebSocketListener
import spice.http.durable.{DurableSocket, DurableSocketConfig, DurableSession, InMemoryEventLog}

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*

/**
 * Regression for sigil bug #282 — spice's
 * `DurableSocketServer.onSession` fires on BOTH fresh connects AND
 * resume re-attachments (`onSession @= existing` inside the resume
 * handler). Without a guard in [[SessionBridge.attach]], every
 * resume re-registers another `protocol.onEvent` listener on the
 * SAME protocol, so N reconnects make every inbound publish fire N
 * times. Field symptom: each browser sees every Message duplicated,
 * EventLogger writes duplicate rows, the agent loop fires N times
 * for one user turn.
 */
class SessionBridgeReattachSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {

  TestSigil.initFor(getClass.getSimpleName)

  override implicit val testTimeout: FiniteDuration = 30.seconds

  private def buildSession(convId: Id[Conversation]): DurableSession[Id[Conversation], Signal, String] = {
    val log = new InMemoryEventLog[Id[Conversation], Signal]
    val protocol = new DurableSocket[Id[Conversation], Signal, String](
      config           = DurableSocketConfig(),
      outboundLog      = log,
      initialChannelId = convId
    )
    val listener = reactify.Var[WebSocketListener](new WebSocketListener)
    DurableSession[Id[Conversation], Signal, String](
      clientId = s"client-${rapid.Unique()}",
      info     = "info",
      protocol = protocol,
      listener = listener
    )
  }

  private def freshConversation(suffix: String): Task[Conversation] = {
    val convId = Conversation.id(s"bridge-$suffix-${rapid.Unique()}")
    val topic  = TopicEntry(Topic.id(s"topic-${convId.value}"), "test", "test")
    val conv   = Conversation(_id = convId, topics = List(topic))
    TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
  }

  "SessionBridge (sigil #282)" should {
    "register the inbound listener exactly once per protocol — re-attach is a no-op" in {
      val received = new AtomicInteger(0)

      // Subscribe a counter scoped to this test's marker text BEFORE
      // attaching, so we observe every emission the SignalHub
      // broadcasts after persist. Pre-fix: 3 attaches → 3 onEvent
      // listeners → 3 sigil.publish calls → 3 broadcasts → counter = 3.
      // Post-fix: counter = 1.
      val counter = TestSigil.signals.collect {
        case m: Message if m.content.exists {
          case t: ResponseContent.Text => t.text == "re-attach-marker"
          case _ => false
        } => m
      }.evalMap(_ => Task { received.incrementAndGet(); () })
      counter.drain.startUnit()

      for {
        conv <- freshConversation("idempotent")
        session = buildSession(conv._id)
        // Three back-to-back attaches against the SAME session —
        // simulates spice firing `onSession @= existing` on resumes.
        _ <- SessionBridge.attach(TestSigil, session, TestUser, resume = ResumeRequest.None)
        _ <- SessionBridge.attach(TestSigil, session, TestUser, resume = ResumeRequest.None)
        _ <- SessionBridge.attach(TestSigil, session, TestUser, resume = ResumeRequest.None)
        // Drive ONE inbound signal directly via the protocol's
        // Channel — this is exactly what `handleRawMessage` does
        // after parsing a `"type": "event"` frame off the wire, so
        // every listener SessionBridge wired runs.
        msg = Message(
          participantId  = TestUser,
          conversationId = conv._id,
          topicId        = conv.currentTopicId,
          content        = Vector(ResponseContent.Text("re-attach-marker")),
          state          = EventState.Complete
        )
        _ = session.protocol.onEvent @= (1L, msg)
        // Brief settle window for the async publish path.
        _ <- Task.sleep(500.millis)
      } yield {
        received.get() shouldBe 1
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
