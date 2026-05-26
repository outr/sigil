package sigil.tool.core

import fabric.rw.*
import sigil.tool.ToolOutput
import sigil.tool.discovery.{CapabilityMatch, TaskShapeHint}

/**
 * Typed result of a `find_capability` discovery call. Carries the
 * matches across every capability kind the framework surfaces (Tool,
 * Mode, Skill) ranked by relevance, plus the normalised query that
 * produced them.
 *
 * Sigil bug #283 — `taskShapeHints` augment the raw matches with
 * composition-derived recommendations: when the SET of returned
 * tools fits a recognised task-shape pattern (multi-file
 * transformation, semantic navigation, …) the framework surfaces
 * the better-fit primitive even if BM25 ranks a textual tool higher.
 * Empty when no recognised shape applies. See
 * [[sigil.tool.discovery.TaskShapeHints]] for the synthesis rules.
 *
 * The framework folds this output onto the originating
 * [[sigil.event.ToolInvoke]] via the settling
 * [[sigil.signal.ToolDelta]]; the per-loop discovery cache is updated
 * separately via [[sigil.TurnContext.recordDiscovery]].
 */
case class FindCapabilityOutput(query: String,
                                matches: List[CapabilityMatch],
                                taskShapeHints: List[TaskShapeHint] = Nil)
  extends ToolOutput derives RW
