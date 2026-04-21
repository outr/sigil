package sigil.conversation

import fabric.rw.*
import lightdb.id.Id
import sigil.information.Information
import sigil.participant.ParticipantId

/**
 * The render-ready input for a single provider call — what the curator
 * produces per turn and hands to the provider. Transient; never
 * persisted.
 *
 * Contains:
 *   - `conversationView` — the materialized frames + participant
 *     projections for this conversation (from [[sigil.db.SigilDB.views]])
 *   - `criticalMemories` / `memories` — ids of [[ContextMemory]] records
 *     the curator selected; provider resolves at render time
 *   - `summaries` — ids of [[ContextSummary]] records the curator chose
 *     to surface; provider resolves at render time
 *   - `information` — referenced catalog entries; app-resolved content
 *   - `extraContext` — app-keyed conversation-wide overlays (not
 *     persisted; supplied per turn by the app's curator)
 *
 * Per-participant `extraContext` overlays live in the
 * [[ConversationView]]'s `participantProjections` since they're a
 * materialized derivation of the event log and participant state.
 */
case class TurnInput(conversationView: ConversationView,
                     criticalMemories: Vector[Id[ContextMemory]] = Vector.empty,
                     memories: Vector[Id[ContextMemory]] = Vector.empty,
                     summaries: Vector[Id[ContextSummary]] = Vector.empty,
                     information: Vector[Information] = Vector.empty,
                     extraContext: Map[ContextKey, String] = Map.empty) derives RW {

  /** Shortcut: aggregate active skills across a chain via the view. */
  def aggregatedSkills(chain: List[ParticipantId]): Vector[ActiveSkillSlot] =
    conversationView.aggregatedSkills(chain)
}
