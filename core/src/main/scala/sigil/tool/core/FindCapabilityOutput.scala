package sigil.tool.core

import fabric.rw.*
import sigil.tool.ToolOutput
import sigil.tool.discovery.CapabilityMatch

/**
 * Typed result of a `find_capability` discovery call. Carries the
 * matches across every capability kind the framework surfaces (Tool,
 * Mode, Skill) ranked by relevance, plus the normalised query that
 * produced them.
 *
 * The framework builds the paired `ToolResults` event from this output;
 * the per-loop discovery cache is updated separately via
 * [[sigil.TurnContext.recordDiscovery]].
 */
case class FindCapabilityOutput(query: String,
                                matches: List[CapabilityMatch]) extends ToolOutput derives RW
