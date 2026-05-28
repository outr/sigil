package sigil.transport

import rapid.Task
import sigil.participant.ParticipantId

/**
 * Sigil #298 — handle returned by [[SessionBridge.attach]] that lets
 * the app swap a session's `viewer` mid-flight. Closes the multi-
 * device sync gap: an unauthenticated session that completes auth
 * later can rebind to the user's authenticated participant id, and
 * subsequent `Sigil.publishTo(user/X, signal)` calls fan out to
 * every device sharing that viewer.
 *
 * The session's underlying [[spice.http.durable.DurableSession]] stays
 * the same across rebinds — the WebSocket / SSE wire isn't touched.
 * Only the framework-side [[SignalTransport]] subscription (which
 * applies the viewer's `signalsFor` filter + transforms) detaches and
 * re-attaches under the new identity. Inbound handlers and the
 * session's outbound sink survive the rebind unchanged.
 *
 * `rebindViewer` is idempotent on the same viewer (no-op) — apps can
 * call it from every auth-notice handler without tracking whether
 * identity actually changed.
 */
trait SessionRebindHandle {
  /** The participant id this session is currently bound to. */
  def currentViewer: ParticipantId

  /** Re-subscribe this session under `newViewer`. Detaches the prior
    * `signalsFor` stream, then attaches a fresh one for the new
    * viewer. `resume` controls whether the new subscription replays
    * history under the new viewer's `canSee` / viewer-transforms
    * (typical default: [[ResumeRequest.None]] — the client already
    * has the prior history, no need to re-deliver).
    *
    * No-op when `newViewer == currentViewer`. */
  def rebindViewer(newViewer: ParticipantId,
                   resume: ResumeRequest = ResumeRequest.None): Task[Unit]

  /** Detach the session entirely. Closes the underlying
    * [[SignalTransport]] subscription and the sink. The session's
    * WebSocket / SSE transport stays open — apps wanting a full
    * teardown close the protocol separately. */
  def detach: Task[Unit]
}
