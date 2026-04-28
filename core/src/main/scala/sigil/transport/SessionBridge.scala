package sigil.transport

import fabric.Json
import fabric.rw.RW
import lightdb.id.Id
import rapid.Task
import sigil.Sigil
import sigil.conversation.Conversation
import sigil.event.Event
import sigil.participant.ParticipantId
import sigil.signal.{Notice, Signal}
import spice.http.durable.DurableSession

/**
 * Bridges a single [[spice.http.durable.DurableSession]] to a [[Sigil]]:
 *
 *   - **Outbound (server → client)** — attaches a
 *     [[DurableSocketSink]] subscribed to the session's `channelId`,
 *     viewer-filtered through `signalsFor`. Events ride the durable
 *     channel; Deltas ride ephemeral.
 *   - **Inbound (client → server)** — wires `protocol.onEvent` so
 *     client-pushed Events flow into `sigil.publish`. Failures are
 *     logged at WARN and skipped; one bad event doesn't tear down
 *     the channel.
 *   - **Ephemeral inbound** — by default warn-logged because
 *     Sigil's wire vocabulary is Signals only. Apps that want to
 *     handle non-Signal ephemeral traffic (heartbeats, ping/pong,
 *     debug telemetry) supply their own `onEphemeral` handler.
 *   - **Optional session-start hook** — `onSessionStart(channelId)`
 *     runs after the sink is attached. Apps use this to lazy-create
 *     a [[Conversation]] on first contact, perform auth checks, etc.
 *
 * Typical wiring inside a server's `onSession` callback:
 *
 * {{{
 *   durableServer.onSession.attach { session =>
 *     SessionBridge.attach(
 *       sigil = MySigil,
 *       session = session,
 *       viewer = MyUser,
 *       onSessionStart = ensureConversation
 *     ).start()
 *     ()
 *   }
 * }}}
 *
 * The bridge is stateless — it only registers `Channel` listeners on
 * the supplied session. When the session disconnects, spice tears
 * down the underlying channels and any pending `start()`-spawned
 * fibers from this bridge complete naturally.
 */
object SessionBridge {

  /** Default ephemeral handler: try to deserialize the payload as a
    * [[Notice]] (the framework's wire vocabulary for client→server
    * pulses). If it parses, dispatch to [[Sigil.handleNotice]]; if it
    * doesn't, warn-log. Apps can override for non-Notice ephemeral
    * traffic (heartbeats, ping/pong, debug telemetry). */
  def noticeOrWarn(sigil: Sigil, viewer: ParticipantId): Json => Task[Unit] = json => Task.defer {
    val rw = summon[RW[Signal]]
    scala.util.Try(rw.write(json)) match {
      case scala.util.Success(n: Notice) =>
        sigil.handleNotice(n, viewer)
          .handleError(t => Task {
            scribe.warn(s"SessionBridge: handleNotice failed for $viewer: ${t.getMessage}", t)
          })
      case _ =>
        Task {
          scribe.warn(
            s"SessionBridge: unexpected ephemeral payload (not a Notice): $json"
          )
        }
    }
  }

  /** Default replay budget for new sessions. 50 most recent Messages
    * (plus any non-Message events that interleave with them) gives a
    * fresh-connect / reconnect enough context that the user sees what
    * was published while disconnected — including agent greetings
    * fired before the wire connected. Apps tune via the `resume`
    * parameter on [[attach]]. */
  val DefaultResume: ResumeRequest = ResumeRequest.RecentMessages(50)

  /** Wire a fresh session to `sigil`. Returns a `Task[Unit]` that
    * completes once the outbound sink is attached and the inbound
    * listeners are registered. Apps typically call `.start()` on the
    * returned task inside their `onSession` callback so the
    * session-handler doesn't block; the listeners themselves run on
    * spice's reactive channels.
    *
    * @param sigil           the Sigil instance to bridge into
    * @param session         the freshly accepted DurableSocketServer session
    * @param viewer          the [[ParticipantId]] whose `signalsFor` filter +
    *                        viewer transforms apply to outbound delivery
    * @param onSessionStart  app hook invoked once after the sink is
    *                        attached; typical use is lazy-creating
    *                        the conversation record. Default: no-op.
    * @param onEphemeral     handler for inbound ephemeral payloads.
    *                        Default: [[WarnUnexpectedEphemeral]].
    * @param resume          history-replay shape applied when the
    *                        session opens. Default
    *                        [[DefaultResume]] (50 most recent
    *                        Messages plus interleaved non-Message
    *                        events) — gives the client enough context
    *                        on (re)connect that agent greetings and
    *                        prior turns aren't invisible. Pass
    *                        [[ResumeRequest.None]] to skip replay
    *                        entirely (live-only).
    */
  def attach[Info: RW](sigil: Sigil,
                       session: DurableSession[Id[Conversation], Event, Info],
                       viewer: ParticipantId,
                       onSessionStart: Id[Conversation] => Task[Unit] = (_: Id[Conversation]) => Task.unit,
                       onEphemeral: Option[Json => Task[Unit]] = None,
                       resume: ResumeRequest = DefaultResume): Task[Unit] = {
    val convId       = session.channelId
    val sink         = new DurableSocketSink[Id[Conversation], Info](session)
    val ephemeralFn  = onEphemeral.getOrElse(noticeOrWarn(sigil, viewer))

    sigil.signalTransport.attach(
      viewer = viewer,
      sink = sink,
      resume = resume,
      conversations = Some(Set(convId))
    ).flatMap { _ =>
      onSessionStart(convId)
    }.flatMap { _ =>
      Task {
        // Inbound: client-pushed Events → sigil.publish.
        session.protocol.onEvent.attach { case (seq, event) =>
          sigil
            .publish(event)
            .handleError(t => Task {
              scribe.warn(
                s"SessionBridge: publish failed for inbound event seq=$seq on ${convId}: ${t.getMessage}", t
              )
            })
            .start()
          ()
        }
        // Ephemeral: by default, deserialize as Notice and dispatch to
        // sigil.handleNotice. Apps can override with their own handler.
        session.protocol.onEphemeral.attach { json =>
          ephemeralFn(json)
            .handleError(t => Task {
              scribe.warn(s"SessionBridge: onEphemeral handler failed: ${t.getMessage}", t)
            })
            .start()
          ()
        }
      }
    }
  }
}
