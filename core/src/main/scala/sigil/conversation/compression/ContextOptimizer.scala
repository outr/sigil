package sigil.conversation.compression

import sigil.conversation.ContextFrame

/**
 * Cheap, per-turn frame cleanup that runs before any budget check.
 * Never drops information — only compacts redundant shapes that the
 * event log accumulates over time:
 *
 *   - tool-call+result pairs whose results are already represented
 *     elsewhere (suggested-tools section, current-mode line, etc.)
 *   - consecutive Text frames from the same participant with identical
 *     content (UI retries, duplicate delta flushes)
 *   - whitespace-only Text frames
 *
 * Stateless — pure function from frames + the curator-resolved set
 * of "elide-me" tool names to frames.
 */
trait ContextOptimizer {

  /** Optimize the given frame vector.
    *
    * `elideToolNames` is the set of tool names whose ToolCall /
    * ToolResult pairs should be dropped — typically derived by the
    * curator from each [[sigil.tool.Tool.resultTtl]]. Defaults to
    * empty so callers that don't pass a set behave like a pure
    * "consecutive cleanup" pass with no pair-stripping. */
  def optimize(frames: Vector[ContextFrame],
               elideToolNames: Set[String] = Set.empty): Vector[ContextFrame]
}
