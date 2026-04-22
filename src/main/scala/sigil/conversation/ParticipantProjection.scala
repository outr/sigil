package sigil.conversation

import fabric.rw.*

/**
 * Materialized per-participant state maintained on [[ConversationView]].
 *
 * Derived from the event log as events reach `EventState.Complete`:
 *   - `activeSkills` — updated on `ModeChange` (Mode-source slot) and
 *     app-specific skill activation events
 *   - `recentTools` — pushed onto the head when a `ToolInvoke` from this
 *     participant completes
 *   - `suggestedTools` — replaced when a `ToolResults` from this participant
 *     carries fresh `find_capability` matches
 *   - `extraContext` — app-driven (populated via curator or tool behavior)
 *
 * This is the durable, re-derivable projection. The per-turn
 * [[TurnInput]] may further annotate or filter these when assembling the
 * provider request, but the truth lives on the view.
 */
case class ParticipantProjection(activeSkills: Map[SkillSource, ActiveSkillSlot] = Map.empty,
                                 recentTools: List[String] = Nil,
                                 suggestedTools: List[String] = Nil,
                                 extraContext: Map[ContextKey, String] = Map.empty)
  derives RW
