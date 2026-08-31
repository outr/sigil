package sigil.transport

import fabric.rw.RW
import lightdb.id.Id
import rapid.Task
import sigil.Sigil
import sigil.conversation.Conversation
import sigil.participant.ParticipantId
import sigil.signal.Signal
import spice.http.durable.{DurableSocketConfig, DurableSocketServer, ReconnectStrategy}
import spice.http.server.MutableHttpServer
import spice.http.server.config.HttpServerListener
import spice.http.server.dsl.*
import spice.http.server.dsl.given
import spice.net.path

import scala.concurrent.duration.*
import scala.language.implicitConversions

/**
 * Standard WebSocket front-door for a [[Sigil]] instance. Wires the
 * pieces every Sigil-on-WebSocket consumer needs together:
 *
 *   - a [[DurableSocketServer]] keyed off the connect-time `Info`
 *     payload via the supplied `resolveChannel`
 *   - per-session [[SessionBridge.attach]] (close-on-attach-failure,
 *     viewer-filtered outbound, inbound `publish`, ephemeral
 *     [[sigil.signal.Notice]] dispatch)
 *   - an HTTP server bound to the configured listener mounting the
 *     durable server at `/ws`
 *   - the Sigil-shipped event log ([[Sigil.eventLog]]) so durable
 *     replay reads from `SigilDB.events` rather than spice's
 *     in-memory log
 *   - the framework-blessed defaults for `ackBatchDelay` and
 *     `reconnectStrategy`
 *
 * Apps construct one per Sigil instance and call [[start]] / [[stop]]
 * for lifecycle. Reach into [[durableServer]] / [[httpServer]] for
 * advanced cases (custom resume flows, additional HTTP routes
 * mounted on the same listener, mounting `/ws` at a different path)
 * without abandoning the wrapper.
 *
 * Typical wiring inside an app server class:
 *
 * {{{
 *   final class MyServer(port: Int) {
 *     val ws = new WsServer[MyInfo](
 *       sigil = MySigil,
 *       viewer = MyUser,
 *       port = port,
 *       resolveChannel = (_, info) => Task.pure(Conversation.id(info.conversationId)),
 *       onSessionStart = ensureConversation
 *     )
 *
 *     def start(): Task[Unit] = ws.start()
 *     def stop():  Task[Unit] = ws.stop()
 *
 *     private def ensureConversation(convId: Id[Conversation]): Task[Unit] =
 *       MySigil.getOrCreateConversation(
 *         conversationId = convId,
 *         createdBy      = MyUser,
 *         label          = "MyApp",
 *         participants   = List(defaultAgent)
 *       ).unit
 *   }
 * }}}
 *
 * The `resolveChannel` parameter is intentionally explicit (no
 * structural-type default) — apps know the shape of their `Info`
 * payload and pulling `conversationId` off it is one line, while
 * structural-type defaults compile slowly and fail confusingly when
 * the field name differs.
 *
 * @param sigil          the Sigil instance whose [[Sigil.eventLog]]
 *                       backs durable replay and whose
 *                       [[Sigil.signalsFor]] backs outbound delivery
 * @param viewer         the default [[ParticipantId]] whose
 *                       `signalsFor` filter + viewer transforms apply
 *                       to outbound delivery on every accepted session.
 *                       Used when `resolveViewer` is `None`, OR when
 *                       the resolver fails. Apps wanting per-session
 *                       resolution at handshake supply `resolveViewer`
 *                       (sigil #298).
 * @param port           HTTP listener port. Pass `0` for an ephemeral
 *                       port; read [[serverPort]] after [[start]] to
 *                       learn what was assigned.
 * @param host           HTTP listener host; default `127.0.0.1`
 * @param resolveChannel maps `(clientId, info)` to the
 *                       [[Conversation]] id this session subscribes to
 * @param resolveViewer  sigil #298 — optional per-session viewer
 *                       resolver. Called once per session at handshake
 *                       with `(clientId, info)`. Apps that establish
 *                       identity from the auth token on the `Info`
 *                       payload return `user/<userId>`; sessions
 *                       without auth fall back to the static `viewer`.
 *                       `None` (default) keeps the legacy behavior of
 *                       binding every session to `viewer` directly.
 * @param onSessionStart per-session hook fired after the
 *                       [[SessionBridge]] attaches; typical use is
 *                       lazy-creating the conversation row via
 *                       [[Sigil.getOrCreateConversation]]
 * @param config         spice's [[DurableSocketConfig]]; defaults
 *                       match the framework's standard tuning (50ms
 *                       ack batch, no automatic server-side reconnect)
 */
final class WsServer[Info: RW](sigil: Sigil,
                               viewer: ParticipantId,
                               port: Int,
                               host: String = "127.0.0.1",
                               resolveChannel: (String, Info) => Task[Id[Conversation]],
                               resolveViewer: Option[(String, Info) => Task[ParticipantId]] = None,
                               onSessionStart: Id[Conversation] => Task[Unit] = (_: Id[Conversation]) => Task.unit,
                               // Sigil #402 — fired once when a session reaches its terminal Closed
                               // state (the session-expiry reaper collects a never-resumed drop, or
                               // an explicit close). Hosts release per-session resources here
                               // (headless browsers, identity caches, screencast streams). `clientId`
                               // is passed so identity-keyed caches can be cleared too.
                               onSessionEnd: (String, Id[Conversation]) => Task[Unit] = (_: String, _: Id[Conversation]) => Task.unit,
                               // How long a disconnected (un-resumed) session lingers before the
                               // reaper closes it — which is what fires `onSessionEnd`. Resource-heavy
                               // hosts shorten this for prompter teardown (a network blip longer than
                               // this forces a fresh re-attach rather than a resume).
                               sessionTimeout: FiniteDuration = 30.minutes,
                               sessionExpiryInterval: FiniteDuration = 1.minute,
                               config: DurableSocketConfig = DurableSocketConfig(
                                 ackBatchDelay = 50.millis,
                                 reconnectStrategy = ReconnectStrategy.none
                               )) {

  /**
   * The DurableSocket server. Public so apps can poke at sessions /
   * channels for advanced scenarios (custom resume flows, channel
   * introspection).
   */
  val durableServer: DurableSocketServer[Id[Conversation], Signal, Info] =
    new DurableSocketServer[Id[Conversation], Signal, Info](
      config = config,
      eventLog = sigil.eventLog,
      resolveChannel = resolveChannel,
      sessionTimeout = sessionTimeout
    )

  /**
   * The HTTP server. Public so apps can mount additional handlers
   * (`/storage`, `/health`, custom REST endpoints) on the same
   * listener without standing up a second one.
   */
  val httpServer: MutableHttpServer = {
    val s = new MutableHttpServer
    val listenerPort: Option[Int] = if (port <= 0) None else Some(port)
    s.config.clearListeners().addListeners(HttpServerListener(host = host, port = listenerPort))
    s.handler(List(path"/ws" / durableServer))
    durableServer.onSession.attach { session =>
      // Sigil #298 — per-session viewer resolution. When the app
      // supplied `resolveViewer`, call it with the session's
      // `(clientId, info)`; otherwise fall back to the static
      // `viewer` (legacy single-identity behavior). A resolver
      // failure also falls back so a transient lookup error doesn't
      // sever the session entirely — the app can still rebind via
      // the returned handle once it's resolved identity downstream.
      val resolveTask: Task[ParticipantId] = resolveViewer match {
        case None => Task.pure(viewer)
        case Some(fn) => fn(session.clientId, session.info).handleError { t =>
            Task {
              scribe.warn(
                s"WsServer: resolveViewer failed for clientId=${session.clientId}; " +
                  s"falling back to default viewer=${viewer.value}: ${t.getMessage}",
                t
              )
              viewer
            }
          }
      }
      resolveTask.flatMap { resolved =>
        SessionBridge.attach(
          sigil = sigil,
          session = session,
          viewer = resolved,
          onSessionStart = onSessionStart,
          onSessionEnd = onSessionEnd
        )
      }.start()
      ()
    }
    s
  }

  /**
   * The actual bound port. After [[start]] this returns the
   * assigned port (useful when `port = 0` requested an ephemeral
   * one).
   */
  def serverPort: Int = httpServer.config.listeners().head.port.getOrElse(0)

  /**
   * Boot the HTTP server. Idempotent — repeat calls after the
   * server is already up are no-ops. Also starts the session-expiry
   * reaper (#402) so disconnected, never-resumed sessions reach their
   * terminal `Closed` state and fire `onSessionEnd`.
   */
  def start(): Task[Unit] = httpServer.start().map { _ =>
    durableServer.startSessionExpiry(sessionExpiryInterval)
  }

  /**
   * Stop the HTTP server. Best-effort — failures are swallowed
   * since the typical caller is a `finally`-shaped shutdown path.
   */
  def stop(): Task[Unit] = Task {
    try httpServer.stop().sync()
    catch { case _: Throwable => () }
  }
}
