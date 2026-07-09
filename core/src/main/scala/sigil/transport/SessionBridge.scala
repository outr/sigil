package sigil.transport

import fabric.Json
import fabric.rw.RW
import lightdb.id.Id
import rapid.Task
import sigil.Sigil
import sigil.conversation.Conversation
import sigil.participant.ParticipantId
import sigil.signal.{Notice, Signal}
import spice.http.durable.{DurableSession, ProtocolState}

import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}

/**
 * Bridges a single [[spice.http.durable.DurableSession]] to a [[Sigil]]:
 *
 *   - **Outbound (server → client)** — attaches a
 *     [[DurableSocketSink]] subscribed to the session's `channelId`,
 *     viewer-filtered through `signalsFor`. Every Signal subtype
 *     (Event, Delta, Notice) rides the durable channel via
 *     `protocol.push` — sequenced, ack-tracked, and replayable from
 *     spice's outbound ring on within-session reconnect.
 *   - **Inbound (client → server)** — wires `protocol.onEvent` so
 *     client-pushed Signals flow into `sigil.publish`. (Sigil's
 *     publish pipeline accepts any Signal and routes per subtype.)
 *     Failures are logged at WARN and skipped; one bad signal
 *     doesn't tear down the channel.
 *   - **Ephemeral inbound** — by default warn-logged because
 *     Sigil's wire vocabulary is Signals only. Apps that want to
 *     handle non-Signal ephemeral traffic (heartbeats, ping/pong,
 *     debug telemetry) supply their own `onEphemeral` handler.
 *   - **Optional session-start hook** — `onSessionStart(channelId)`
 *     runs after the sink is attached. Apps use this to lazy-create
 *     a [[Conversation]] on first contact, perform auth checks, etc.
 *   - **Viewer rebind (sigil #298)** — returns a
 *     [[SessionRebindHandle]] whose `rebindViewer(newViewer)` lets
 *     the app swap the session's identity mid-flight (auth-complete
 *     transitions, multi-device sync). The underlying wire stays
 *     open; only the framework-side `signalsFor` subscription
 *     re-attaches under the new viewer.
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
 */
object SessionBridge {

  /** Sigil bug #282 — spice's `DurableSocketServer.onSession` fires on
    * BOTH fresh connects AND resume re-attachments (`onSession @=
    * existing` in the resume handler). Without a guard here, every
    * resume re-registers another `protocol.onEvent` listener on the
    * SAME protocol instance, so N reconnects make every subsequent
    * inbound Message publish N times — each browser sees every event
    * duplicated, EventLogger writes duplicate rows, the agent fires
    * N times for one user turn.
    *
    * We track which protocol instances have already been wired (by
    * object identity — same protocol survives resume; a brand-new
    * protocol is a genuinely fresh session that needs wiring).
    * Identity-keyed because clientId can repeat across the
    * spice-session-reap → fresh-protocol path, and equals-based
    * tracking would mistakenly skip the new wiring. */
  private val wiredProtocols: java.util.Set[AnyRef] = java.util.Collections.synchronizedSet(
    java.util.Collections.newSetFromMap(new java.util.IdentityHashMap[AnyRef, java.lang.Boolean]())
  )

  /** Default ephemeral handler: try to deserialize the payload as a
    * [[Notice]] (the framework's wire vocabulary for client→server
    * pulses). If it parses, dispatch to [[Sigil.handleNotice]]; if it
    * doesn't, warn-log. Apps can override for non-Notice ephemeral
    * traffic (heartbeats, ping/pong, debug telemetry). */
  def noticeOrWarn(sigil: Sigil, viewer: ParticipantId): Json => Task[Unit] =
    noticeOrWarnLive(sigil, () => viewer)

  /** Sigil #298 — variant of [[noticeOrWarn]] that reads the viewer
    * via a callback on every invocation rather than capturing a
    * snapshot at attach time. Used internally so a session's
    * post-rebind viewer drives Notice dispatch. */
  private def noticeOrWarnLive(sigil: Sigil, viewerRef: () => ParticipantId): Json => Task[Unit] = json =>
    // Sigil #409 — await the polymorphic registry BEFORE decoding. App-defined
    // `Notice` subtypes (e.g. a client→server request pulse like
    // `RequestThemeThumbnail`) register into the process-global `RW[Signal]`
    // PolyType at `sigil.instance`; an ephemeral payload that arrives before
    // that completes can't have its discriminator resolved, so `rw.write` throws
    // and the payload is silently dropped — intermittently, since the same bytes
    // decode fine a moment later. `polymorphicRegistrations` is `.singleton`, so
    // this awaits the one-time registration and is a no-op thereafter.
    sigil.polymorphicRegistrations.flatMap { _ =>
      Task.defer {
        val rw = summon[RW[Signal]]
        scala.util.Try(rw.write(json)) match {
          case scala.util.Success(n: Notice) =>
            val v = viewerRef()
            sigil.handleNotice(n, v)
              .handleError(t => Task {
                scribe.warn(s"SessionBridge: handleNotice failed for $v: ${t.getMessage}", t)
              })
          // Sigil #409 — split the old collapsed `case _` so the REASON a payload
          // didn't dispatch is visible: a Signal that isn't a Notice vs. a genuine
          // decode failure (the latter logs the exception, which distinguishes a
          // registry/init-order miss from a real decode bug or an unknown type).
          case scala.util.Success(other) =>
            Task(scribe.warn(
              s"SessionBridge: ephemeral payload deserialized to a non-Notice ${other.getClass.getName}: $json"))
          case scala.util.Failure(err) =>
            Task(scribe.warn(
              s"SessionBridge: could not decode ephemeral payload as a Signal: ${err.getMessage} — $json", err))
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

  /** Wire a fresh session to `sigil`. Returns a `Task[SessionRebindHandle]`
    * that completes once the outbound sink is attached and the inbound
    * listeners are registered. The handle exposes `rebindViewer` for
    * mid-session identity changes (sigil #298) and `detach` for
    * teardown.
    *
    * Apps typically call `.start()` on the returned task inside their
    * `onSession` callback so the session-handler doesn't block — but
    * keeping the handle reachable (storing in a per-session map keyed
    * by `clientId` or similar) is the path to invoke `rebindViewer`
    * from the app's auth-complete flow. */
  def attach[Info: RW](sigil: Sigil,
                       session: DurableSession[Id[Conversation], Signal, Info],
                       viewer: ParticipantId,
                       onSessionStart: Id[Conversation] => Task[Unit] = (_: Id[Conversation]) => Task.unit,
                       onSessionEnd: (String, Id[Conversation]) => Task[Unit] = (_: String, _: Id[Conversation]) => Task.unit,
                       onEphemeral: Option[Json => Task[Unit]] = None,
                       resume: ResumeRequest = DefaultResume): Task[SessionRebindHandle] = {
    val convId        = session.channelId
    val sink          = new DurableSocketSink[Id[Conversation], Info](session)
    val conversations: Some[Set[Id[Conversation]]] = Some(Set(convId))
    val viewerRef     = new AtomicReference[ParticipantId](viewer)
    val handleRef     = new AtomicReference[SinkHandle](null)
    val ephemeralFn   = onEphemeral.getOrElse(noticeOrWarnLive(sigil, () => viewerRef.get()))

    // Sigil bug #282 — guard inbound listener registration against
    // resume re-attachment. `protocol.eq` is the identity key — same
    // protocol survives resume (spice re-binds the new WS listener
    // onto the existing DurableSocket); a brand-new protocol is a
    // genuinely fresh session that needs wiring.
    val protocolKey: AnyRef = session.protocol
    val firstAttachForThisProtocol: Boolean = wiredProtocols.add(protocolKey)

    val attached: Task[SessionRebindHandle] = sigil.signalTransport.attach(
      viewer = viewer,
      sink = sink,
      resume = resume,
      conversations = conversations
    ).flatMap { firstHandle =>
      handleRef.set(firstHandle)
      onSessionStart(convId)
    }.flatMap { _ =>
      Task {
        if (firstAttachForThisProtocol) {
          // Inbound: client-pushed Signals → sigil.publish. The
          // durable channel's onEvent receives Signal (the channel is
          // typed over the full sum), and `Sigil.publish` accepts the
          // full Signal sum and dispatches per subtype internally.
          session.protocol.onEvent.attach { case (seq, signal) =>
            sigil
              .publish(signal)
              .handleError(t => Task {
                scribe.warn(
                  s"SessionBridge: publish failed for inbound signal seq=$seq on ${convId}: ${t.getMessage}", t
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
          // Sigil #402 — session-end hook. Fire `onSessionEnd` exactly once
          // when the protocol reaches its terminal `Closed` state, so a host
          // can release per-session resources (headless browsers, identity
          // caches, screencast streams). `Closed` — not `Disconnected` — is the
          // signal: a transient drop goes Disconnected and may RESUME (firing
          // here would tear down a session the client reconnects to). A hard
          // drop reaches `Closed` when the server's session-expiry reaper
          // (`WsServer` starts it) collects the stale, never-resumed session.
          val ended = new AtomicBoolean(false)
          session.protocol.state.attach { st =>
            if (st == ProtocolState.Closed && ended.compareAndSet(false, true)) {
              onSessionEnd(session.clientId, convId)
                .handleError(t => Task {
                  scribe.warn(s"SessionBridge: onSessionEnd failed for clientId=${session.clientId} ${convId.value}: ${t.getMessage}", t)
                })
                .start()
            }
          }
        }
        // else: resume re-attachment — listeners already on the
        // existing protocol; spice's per-protocol re-binding handles
        // the new WS listener for outbound delivery.
        new SessionRebindHandle {
          override def currentViewer: ParticipantId = viewerRef.get()

          override def rebindViewer(newViewer: ParticipantId,
                                    resume: ResumeRequest = ResumeRequest.None): Task[Unit] = Task.defer {
            val current = viewerRef.get()
            if (current == newViewer) Task.unit
            else {
              val oldHandle = handleRef.get()
              val oldDetach: Task[Unit] =
                if (oldHandle == null) Task.unit
                else oldHandle.detach.handleError(t => Task {
                  scribe.warn(s"SessionBridge: rebind detach failed for ${convId.value}: ${t.getMessage}", t)
                })
              oldDetach.flatMap { _ =>
                sigil.signalTransport.attach(
                  viewer = newViewer,
                  sink = sink,
                  resume = resume,
                  conversations = conversations
                )
              }.map { fresh =>
                handleRef.set(fresh)
                viewerRef.set(newViewer)
              }
            }
          }

          override def detach: Task[Unit] = Task.defer {
            val h = handleRef.getAndSet(null)
            if (h == null) Task.unit else h.detach
          }
        }
      }
    }

    // BUGS.md #16 — never let attach failures vanish. Without this guard,
    // a failure in `onSessionStart` (typical: a fabric `RWException` on a
    // schema-drifted record) leaves the session in "active but inert"
    // state — WebSocket open, no inbound handlers — and produces zero
    // logging because the typical caller invokes `.start()` on the
    // returned Task and discards both result and error. We log at ERROR
    // and best-effort close the session so the client transitions to
    // Disconnected and the user sees a real failure state.
    attached.handleError { t =>
      Task {
        scribe.error(
          s"SessionBridge: attach failed for conversation=${convId.value} viewer=${viewer.value}: ${t.getMessage}",
          t
        )
        try session.protocol.close() catch { case _: Throwable => () }
        // Return an inert handle — rebind / detach are no-ops on a
        // session whose attach already failed and whose protocol is
        // closed.
        new SessionRebindHandle {
          override def currentViewer: ParticipantId = viewer
          override def rebindViewer(newViewer: ParticipantId, resume: ResumeRequest): Task[Unit] = Task.unit
          override def detach: Task[Unit] = Task.unit
        }
      }
    }
  }
}
