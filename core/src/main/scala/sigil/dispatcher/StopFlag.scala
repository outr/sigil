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
   * Cooperative cancellation seam for IN-FLIGHT TOOL executions.
   * Cancelled by `applyStop` on ANY stop (graceful included — a user's
   * Stop shouldn't wait out a multi-minute sweep) and threaded to tool
   * bodies via [[sigil.tool.ToolContext.checkpoint]]. Long-running
   * tools call the checkpoint at natural boundaries (per file, per
   * batch item) and exit early with a visible cancellation failure;
   * tools that ignore it finish naturally and the stop path settles
   * their invoke.
   */
  val cancellation: sigil.CancellationToken = new sigil.CancellationToken("agent-turn")

  /**
   * True when either flag is set — short-circuit for "should the loop exit?"
   */
  def requested: Boolean = force.get() || graceful.get()
}
