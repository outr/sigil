package spec

import fabric.rw.*
import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.conversation.Conversation
import sigil.signal.Signal
import sigil.transport.SessionBridge
import spice.http.WebSocketListener
import spice.http.durable.{DurableSession, DurableSocket, DurableSocketConfig, InMemoryEventLog}

import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}

/**
 * Sigil #402 — `SessionBridge.attach` fires `onSessionEnd(clientId,
 * conversationId)` exactly once when the session's protocol reaches its
 * terminal `Closed` state, so a host can release per-session resources.
 * `Closed` is the signal (not a transient `Disconnected`, which may resume);
 * `WsServer`'s session-expiry reaper drives a never-resumed drop to `Closed`.
 */
class SessionBridgeEndHookSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private def freshSession(convId: Id[Conversation], clientId: String): DurableSession[Id[Conversation], Signal, String] = {
    val protocol = new DurableSocket[Id[Conversation], Signal, String](
      config = DurableSocketConfig(),
      outboundLog = new InMemoryEventLog[Id[Conversation], Signal],
      initialChannelId = convId
    )
    DurableSession[Id[Conversation], Signal, String](
      clientId = clientId,
      info = "info",
      protocol = protocol,
      listener = reactify.Var[WebSocketListener](new WebSocketListener)
    )
  }

  "SessionBridge onSessionEnd (#402)" should {

    "fire exactly once with (clientId, conversationId) when the protocol closes" in {
      val convId = Conversation.id(s"bridge-end-${rapid.Unique()}")
      val session = freshSession(convId, "client-1")
      val calls = new AtomicInteger(0)
      val seen = new AtomicReference[(String, Id[Conversation])](null)
      for {
        _ <- SessionBridge.attach(
          sigil = TestSigil,
          session = session,
          viewer = TestUser,
          onSessionEnd = (cid, conv) => Task { calls.incrementAndGet(); seen.set((cid, conv)); () })
        _ = session.protocol.close()
        // A second close must not re-fire (terminal, idempotent).
        _ = session.protocol.close()
        _ <- Task.sleep(scala.concurrent.duration.DurationInt(150).millis)
      } yield {
        calls.get() shouldBe 1
        seen.get() shouldBe ("client-1", convId)
      }
    }

    "not fire while the session is merely open (no close)" in {
      val convId = Conversation.id(s"bridge-open-${rapid.Unique()}")
      val session = freshSession(convId, "client-2")
      val calls = new AtomicInteger(0)
      for {
        _ <- SessionBridge.attach(
          sigil = TestSigil,
          session = session,
          viewer = TestUser,
          onSessionEnd = (_, _) => Task { calls.incrementAndGet(); () })
        _ <- Task.sleep(scala.concurrent.duration.DurationInt(150).millis)
      } yield calls.get() shouldBe 0
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
