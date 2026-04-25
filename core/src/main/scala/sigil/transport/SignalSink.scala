package sigil.transport

import rapid.Task
import sigil.signal.Signal

/**
 * A wire-side destination for [[Signal]]s. Implementations serialize and
 * frame as their transport requires — SSE writes a `data:` line per
 * signal, a DurableSocket session sends a JSON payload over its
 * WebSocket, etc.
 *
 * Sinks are policy-free: they don't know about viewers, conversations,
 * or replay. [[SignalTransport]] owns the subscribe / replay / forward
 * pipeline and pushes a fully-resolved (already-redacted) signal to the
 * sink.
 *
 * Errors from `push` should propagate; the transport detaches the sink
 * and stops forwarding. `close` is idempotent — second close is a
 * no-op, and pushes after close silently drop.
 */
trait SignalSink {
  def push(signal: Signal): Task[Unit]
  def close: Task[Unit]
}
