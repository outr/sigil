package sigil.conversation.compression

import sigil.conversation.ContextFrame

/**
 * Cheap, per-turn frame cleanup that runs before any budget check.
 * Never drops information — only compacts redundant shapes that the
 * event log accumulates over time:
 *
 *   - stale / dangling tool-call+result pairs that contribute nothing
 *   - consecutive Text frames from the same participant with identical
 *     content (UI retries, duplicate delta flushes)
 *   - whitespace-only Text frames
 *
 * Stateless — pure function from frames to frames.
 */
trait ContextOptimizer {
  def optimize(frames: Vector[ContextFrame]): Vector[ContextFrame]
}
