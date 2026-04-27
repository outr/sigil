package sigil.tool

import sigil.SpaceId
import sigil.participant.ParticipantId
import sigil.provider.Mode

/**
 * Bundle of context handed to a [[ToolFinder]] when `find_capability`
 * runs. Carries the keyword query, the caller chain, the active
 * conversation `Mode`, and the caller's accessible spaces.
 *
 * Finders use these to filter the candidate set: by keyword match
 * (name / description / keywords field), by mode affinity (current
 * `mode.id` ∈ `tool.modes`), and by space affinity (`tool.space ==
 * GlobalSpace` OR `tool.space ∈ callerSpaces`).
 */
case class DiscoveryRequest(keywords: String,
                            chain: List[ParticipantId],
                            mode: Mode,
                            callerSpaces: Set[SpaceId])
