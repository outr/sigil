package sigil.dispatcher

import sigil.event.{AgentState, Event, Message, ModeChange, Stop, TitleChange, ToolResults}
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
 *   - [[Stop]] never triggers — it's a control signal consumed by the
 *     dispatcher's stop-handling path, not content the target agent
 *     should act on as a new turn.
 *   - [[Message]] from self never re-triggers (no self-talking-loops).
 *   - [[TitleChange]] from self never re-triggers — naming a
 *     conversation isn't content the agent should act on again.
 *   - [[ModeChange]] DOES re-trigger the emitter: after switching mode,
 *     the agent is expected to respond *in the new mode*.
 *   - [[Message]], [[ModeChange]], [[TitleChange]], and [[ToolResults]]
 *     from others are valid triggers.
 *   - All other Event types are not triggers by default. Apps with
 *     custom Event subtypes can extend this rule by replacing or
 *     wrapping `TriggerFilter`.
 */
object TriggerFilter {
  def isTriggerFor(p: Participant, e: Event): Boolean = e match {
    case _: AgentState                                                => false
    case _: Stop                                                      => false
    case m: Message if m.participantId == p.id                        => false
    case tc: TitleChange if tc.participantId == p.id                  => false
    case _: Message | _: ModeChange | _: TitleChange | _: ToolResults => true
    case _                                                            => false
  }
}
