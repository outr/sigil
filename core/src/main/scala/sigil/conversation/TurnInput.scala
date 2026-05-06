package sigil.conversation

import fabric.rw.*
import lightdb.id.Id
import sigil.information.InformationSummary
import sigil.participant.ParticipantId

/**
 * The render-ready input for a single provider call — what the curator
 * produces per turn and hands to the provider. Transient; never
 * persisted.
 *
 * Bug #26 — replaces the prior `ConversationView` projection on this
 * struct. The curator now materializes frames + per-participant
 * projections per turn (from `db.events` and `db.participantProjections`)
 * and packs them onto this DTO directly.
 *
 * Contains:
 *   - `conversationId` — scope for downstream resolvers (provider,
 *     compressor, settled effects)
 *   - `frames` — [[ContextFrame]]s for this turn's prompt history
 *   - `participantProjections` — per-participant materialized state
 *     (active skills, recent tools, suggested tools, extra context)
 *     for the chain currently driving this turn
 *   - `criticalMemories` / `memories` — ids of [[ContextMemory]] records
 *     the curator selected; provider resolves at render time
 *   - `summaries` — ids of [[ContextSummary]] records the curator chose
 *     to surface; provider resolves at render time
 *   - `information` — referenced catalog entries; app-resolved content
 *   - `extraContext` — app-keyed conversation-wide overlays (not
 *     persisted; supplied per turn by the app's curator)
 */
case class TurnInput(conversationId: Id[Conversation],
                     frames: Vector[ContextFrame] = Vector.empty,
                     participantProjections: Map[ParticipantId, ParticipantProjection] = Map.empty,
                     criticalMemories: Vector[Id[ContextMemory]] = Vector.empty,
                     memories: Vector[Id[ContextMemory]] = Vector.empty,
                     summaries: Vector[Id[ContextSummary]] = Vector.empty,
                     information: Vector[InformationSummary] = Vector.empty,
                     extraContext: Map[ContextKey, String] = Map.empty)
  derives RW {

  /** The projection for `id` within this turn's snapshot — empty if
    * the chain participant has no recorded projection yet. */
  def projectionFor(id: ParticipantId): ParticipantProjection =
    participantProjections.getOrElse(id, ParticipantProjection.empty(id, conversationId))

  /** Flat list of active skills across `chain`. */
  def aggregatedSkills(chain: List[ParticipantId]): Vector[ActiveSkillSlot] =
    chain.flatMap(id => projectionFor(id).activeSkills.values).toVector

  /** Re-pack the curator-controlled fields as a transient
    * [[ConversationView]] for callers that prefer that DTO. */
  def conversationView: ConversationView =
    ConversationView(conversationId, frames, participantProjections)
}

object TurnInput {
  /** Convenience factory that lifts a transient [[ConversationView]]
    * onto a fresh `TurnInput`. Test fixtures use this to keep their
    * old construction shape; production paths build `TurnInput`
    * directly. */
  def apply(view: ConversationView): TurnInput =
    TurnInput(
      conversationId = view.conversationId,
      frames = view.frames,
      participantProjections = view.participantProjections
    )

  /** Convenience factory: lift a [[ConversationView]] plus optional
    * memory / summary / information references. */
  def apply(view: ConversationView,
            criticalMemories: Vector[Id[ContextMemory]],
            memories: Vector[Id[ContextMemory]],
            summaries: Vector[Id[ContextSummary]],
            information: Vector[sigil.information.InformationSummary],
            extraContext: Map[ContextKey, String]): TurnInput =
    TurnInput(
      conversationId = view.conversationId,
      frames = view.frames,
      participantProjections = view.participantProjections,
      criticalMemories = criticalMemories,
      memories = memories,
      summaries = summaries,
      information = information,
      extraContext = extraContext
    )
}
