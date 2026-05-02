package sigil.conversation.compression

import sigil.conversation.ContextFrame
import sigil.participant.ParticipantId

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
    * "consecutive cleanup" pass with no pair-stripping.
    *
    * `currentTurnSource` (bug #73) — when set, marks the participant
    * whose most-recent Text frame begins the *current* agent turn.
    * Implementations MUST NOT elide tool-pair frames that occurred
    * AFTER that boundary, regardless of `resultTtl`. The agent's
    * within-turn iteration history (multiple `find_capability` /
    * `change_mode` calls during a single user-driven turn) stays
    * fully visible so the model can recognise it's already tried
    * something. Elision only applies to pairs from prior turns.
    *
    * `None` falls back to the legacy "elide every earlier pair per
    * tool name regardless of position" behaviour for backward
    * compatibility with optimizer callers that don't have a notion of
    * turn source. */
  def optimize(frames: Vector[ContextFrame],
               elideToolNames: Set[String] = Set.empty,
               currentTurnSource: Option[ParticipantId] = None): Vector[ContextFrame]
}
