package sigil.signal

import fabric.rw.*

/**
 * Lifecycle state of an [[sigil.event.Event]].
 *
 *   - `Active`   — the event is in flight; updates via [[Delta]]s may still
 *                  mutate its persisted state.
 *   - `Complete` — the event has reached its terminal state. No further
 *                  [[Delta]]s should target it.
 *
 * Atomic events (those produced by a single tool execution, like a mode
 * change) are created directly as `Complete`. Streaming events (like a
 * Message being built up by multiple content deltas) start `Active` and
 * transition to `Complete` when the producer signals the end of the stream.
 *
 * Failure information rides inside the Event's own fields (e.g.
 * [[sigil.event.ErrorOccurred]] or a `Failure` content block on a Message)
 * rather than being encoded as a lifecycle state.
 */
enum EventState derives RW {
  case Active
  case Complete
}
