package sigil.dispatcher

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Per-agent stop flag registered by the dispatcher when it claims an
 * [[sigil.event.AgentState]] and inspected by the agent's runtime loop.
 *
 *   - `force` — a [[sigil.event.Stop]] with `force = true` was published
 *     for this agent (or globally for the conversation). The agent's
 *     signal stream should terminate immediately.
 *   - `graceful` — a `Stop` with `force = false` was published. The
 *     current iteration finishes; no further iterations start.
 *
 * Both flags are sticky (never reset within a claim). Released when the
 * dispatcher releases the claim and removes the flag from its registry.
 */
final class StopFlag {
  val force: AtomicBoolean = new AtomicBoolean(false)
  val graceful: AtomicBoolean = new AtomicBoolean(false)

  /**
   * True when either flag is set — short-circuit for "should the loop exit?"
   */
  def requested: Boolean = force.get() || graceful.get()
}
