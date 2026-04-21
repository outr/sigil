package sigil.dispatcher

import sigil.event.{AgentState, Event, Message, ModeChange, TitleChange, ToolResults}
import sigil.participant.Participant

/**
 * Decides which Events count as triggers for which participants. Used by:
 *
 *   - The framework dispatcher's fan-out path — to decide whether an
 *     incoming Event should wake a given participant.
 *   - The dispatcher's self-loop — to decide whether new Events that
 *     arrived during an agent's turn warrant another iteration.
 *
 * Default rules:
 *
 *   - [[AgentState]] events never trigger anyone — they're lifecycle
 *     markers, not new content.
 *   - Messages from `participant.id` itself never re-trigger that
 *     participant (no self-talking-loops).
 *   - [[Message]], [[ModeChange]], [[TitleChange]], and [[ToolResults]]
 *     are valid triggers when not from self. These represent new content
 *     or state changes a participant might need to react to.
 *   - All other Event types are not triggers by default. Apps with
 *     custom Event subtypes can extend this rule by replacing or
 *     wrapping `TriggerFilter`.
 */
object TriggerFilter {
  def isTriggerFor(p: Participant, e: Event): Boolean = e match {
    case _: AgentState                                                => false
    case m: Message if m.participantId == p.id                        => false
    case _: Message | _: ModeChange | _: TitleChange | _: ToolResults => true
    case _                                                            => false
  }
}
