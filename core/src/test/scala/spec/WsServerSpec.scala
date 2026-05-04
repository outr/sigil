package spec

import fabric.rw.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.transport.WsServer
import sigil.conversation.Conversation
import spice.http.{HttpExchange, HttpRequest, HttpStatus}
import spice.net.url

import scala.language.implicitConversions

/**
 * Coverage for [[WsServer]]: construct one in-process and verify the
 * `/ws` route is mounted and matches; non-`/ws` paths fall through to
 * the framework's default NotFound. We don't open a real listener
 * (the factory itself only mounts the listener on [[WsServer.start]])
 * — the assertion is purely on routing, exercised through
 * `httpServer.handle(HttpExchange(...))`.
 *
 * Mirrors the StorageRouteFilterSpec pattern.
 */
class WsServerSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  // The `Info` shape every Sage-style consumer uses — a single
  // `conversationId` string ferried at connect time.
  private case class TestInfo(conversationId: String) derives RW

  // Construct the factory but do NOT start the listener; we exercise
  // routing through `handle(HttpExchange(...))`.
  private val ws = new WsServer[TestInfo](
    sigil = TestSigil,
    viewer = TestUser,
    port = 0,
    resolveChannel = (_, info) => Task.pure(Conversation.id(info.conversationId))
  )

  private def get(path: String): Task[HttpExchange] =
    ws.httpServer.handle(HttpExchange(HttpRequest(url = url"http://localhost".withPath(path))))

  "WsServer" should {

    "expose the underlying durableServer" in Task {
      ws.durableServer should not be null
    }

    "expose the underlying httpServer" in Task {
      ws.httpServer should not be null
    }

    "fall through (404) for non-/ws paths" in {
      // The `/ws` handler is a WebSocket upgrade handler — a plain
      // HTTP GET to a different path falls through to the framework's
      // default NotFound.
      for {
        exchange <- get("/somewhere-else")
      } yield exchange.response.status shouldBe HttpStatus.NotFound
    }

    "route /ws requests through the durable server (no upgrade headers → handled, not 404)" in {
      // Without the `Upgrade: websocket` header the durable server
      // returns BadRequest rather than NotFound. The exact status is
      // spice's call; what we're verifying is that the route MATCHES
      // (i.e. a status other than the default-NotFound that
      // unmatched paths get).
      for {
        exchange <- get("/ws")
      } yield exchange.response.status should not be HttpStatus.NotFound
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
