package sigil.conversation

import fabric.rw.*
import lightdb.id.Id
import sigil.provider.Mode
import sigil.tool.ToolName

/**
 * Materialized per-participant state maintained on [[ConversationView]].
 *
 * Derived from the event log as events reach `EventState.Complete`:
 *   - `activeSkills` — updated on `ModeChange` (Mode-source slot) and
 *     app-specific skill activation events
 *   - `lastDiscoverySkillByMode` — when the agent leaves a mode, the
 *     [[SkillSource.Discovery]] slot active at that moment is archived
 *     under the OUTGOING mode's id; on a later return to that mode,
 *     the slot is restored. Lets agents "remember" the skill they had
 *     loaded for a mode without re-discovering it.
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
                                 lastDiscoverySkillByMode: Map[Id[Mode], ActiveSkillSlot] = Map.empty,
                                 discoverySkillMode: Option[Id[Mode]] = None,
                                 recentTools: List[ToolName] = Nil,
                                 suggestedTools: List[ToolName] = Nil,
                                 extraContext: Map[ContextKey, String] = Map.empty)
  derives RW
