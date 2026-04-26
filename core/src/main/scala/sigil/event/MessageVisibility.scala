package sigil.event

import fabric.rw.*
import sigil.participant.ParticipantId

/**
 * Hard scope rule for an [[Event]] — controls who, on the wire and
 * inside per-agent prompt-building, is allowed to see it. Independent
 * of [[sigil.pipeline.ViewerTransform]], which runs after this filter
 * to mutate (redact) signals that pass.
 *
 * Resolved by `Sigil.canSee(signal, viewer)`:
 *   - [[All]] — every viewer (default; existing behavior)
 *   - [[Agents]] — only viewers whose [[ParticipantId]] is an
 *     [[sigil.participant.AgentParticipantId]]. For internal
 *     planner/worker/critic chatter that mustn't reach a user UI.
 *   - [[Users]] — only viewers whose id is NOT an `AgentParticipantId`.
 *     For human-targeted prompts that other agents shouldn't ingest.
 *   - [[Participants]] — explicit allow-list of ids.
 *
 * Both enforcement points use the same predicate:
 *   - Wire delivery — `Sigil.signalsFor(viewer)` drops signals that
 *     fail `canSee`. Deltas pass through unconditionally; client
 *     logic must ignore deltas whose target event was filtered.
 *   - Per-agent prompt — `buildContext` filters
 *     [[sigil.conversation.ContextFrame]]s by the running agent's
 *     id before handing the view to the curator. Frames denormalize
 *     visibility from their source event at projection time.
 */
enum MessageVisibility derives RW {
  case All
  case Agents
  case Users
  case Participants(ids: Set[ParticipantId])
}
