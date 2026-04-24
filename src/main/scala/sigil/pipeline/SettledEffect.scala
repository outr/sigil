package sigil.pipeline

import rapid.Task
import sigil.Sigil
import sigil.signal.Signal

/**
 * Post-persist side effect triggered by a signal that has landed in
 * the event store. Effects run in declaration order
 * (`Sigil.settledEffects`) inside [[Sigil.publish]], after projection
 * and view updates, before fan-out.
 *
 * Each effect returns `Task[Unit]`. The framework awaits that task
 * before the next effect runs — individual effects decide whether
 * their work is synchronous (blocks publish) or fire-and-forget
 * (`startUnit` inside the returned Task). Slow or external work
 * should be fire-and-forget; fast or ordering-sensitive work should
 * complete inline.
 *
 * Effect failures should be handled by the effect itself — logging
 * and swallowing is the norm. Unhandled errors propagate through
 * `publish`.
 */
trait SettledEffect {
  def apply(signal: Signal, self: Sigil): Task[Unit]
}
