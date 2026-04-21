package sigil

import rapid.Task
import sigil.signal.Signal

/**
 * The wire transport for [[Signal]]s. Apps implement this to push signals
 * wherever they need to land — WebSocket frames, SSE events, message queues,
 * audit logs, anywhere.
 *
 * From sigil's perspective, this trait IS the wire. The framework's
 * [[Sigil.publish]] calls `handle(signal)` after persisting and before
 * fanning out; what `handle` does with the signal beyond that is the
 * implementer's concern.
 *
 * Errors raised here are logged by `Sigil.publish` and swallowed —
 * a flaky transport must not block persistence or downstream fan-out.
 */
trait SignalBroadcaster {
  def handle(signal: Signal): Task[Unit]
}

object SignalBroadcaster {

  /**
   * Default broadcaster used by sigil when an app doesn't supply one. Drops
   * every signal silently. Suitable for tests, internal services, and any
   * deployment where signals only need to be persisted (not pushed
   * externally).
   */
  object NoOp extends SignalBroadcaster {
    override def handle(signal: Signal): Task[Unit] = Task.unit
  }
}
