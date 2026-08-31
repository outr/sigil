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

  /**
   * Optimize the given frame vector.
   *
   * `elideToolNames` is the set of tool names whose ToolCall /
   * ToolResult pairs should be dropped — typically derived by the
   * curator from each the tools' declared [[sigil.tool.Freshness]] (Volatile reads elide). Defaults to
   * empty so callers that don't pass a set behave like a pure
   * "consecutive cleanup" pass with no pair-stripping.
   *
   * `currentTurnSource` — when set to a non-agent participant,
   * narrows the turn boundary to that participant's most-recent Text
   * frame. Otherwise the boundary is the most-recent Text frame from
   * ANY non-agent participant; an agent id is ignored, since agent
   * prose is emitted mid-turn and cannot mark where the turn began.
   * Implementations MUST NOT elide tool-pair frames at or after that
   * boundary, regardless of freshness: the agent's within-turn
   * iteration history — including every sibling of a parallel batch,
   * which shares one tool name — stays fully visible. Elision
   * applies only to pairs from prior turns, and only when a boundary
   * exists to prove a pair belongs to one.
   */
  def optimize(frames: Vector[ContextFrame],
               elideToolNames: Set[String] = Set.empty,
               currentTurnSource: Option[ParticipantId] = None): Vector[ContextFrame]
}
