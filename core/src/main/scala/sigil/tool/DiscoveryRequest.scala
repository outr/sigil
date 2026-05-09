package sigil.tool

import lightdb.id.Id
import sigil.SpaceId
import sigil.conversation.Conversation
import sigil.participant.ParticipantId
import sigil.provider.Mode

/**
 * Bundle of context handed to a [[ToolFinder]] when `find_capability`
 * runs. Carries the keyword query, the caller chain, the active
 * conversation `Mode`, the caller's accessible spaces, and the
 * conversation id.
 *
 * Finders use these to filter the candidate set: by keyword match
 * (name / description / keywords field), by mode affinity (current
 * `mode.id` ∈ `tool.modes`), and by space affinity (`tool.space ==
 * GlobalSpace` OR `tool.space ∈ callerSpaces`).
 *
 * `conversationId` lets [[sigil.Sigil.findCapabilities]] consult
 * [[sigil.Sigil.activeToolchains]] for the per-conversation
 * toolchain-boost path (sigil bug #85).
 */
case class DiscoveryRequest(keywords: String,
                            chain: List[ParticipantId],
                            mode: Mode,
                            callerSpaces: Set[SpaceId],
                            conversationId: Option[Id[Conversation]] = None)
