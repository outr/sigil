package sigil.dispatcher

import sigil.event.{AgentState, Event, Message, ModeChange, MessageRole, Stop, TopicChange}
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
 *   - Any event whose `role` is [[sigil.event.MessageRole.Tool]] always
 *     re-triggers — that's the whole point of `MessageRole.Tool`. The agent
 *     just received a tool's result and needs to read it on the next
 *     iteration. From-self exclusions don't apply: the orchestrator
 *     emits tool results attributed to the calling agent, and we
 *     want those to advance the loop.
 *   - [[AgentState]] events never trigger anyone — they're lifecycle
 *     markers, not new content.
 *   - [[Stop]] never triggers — it's a control signal consumed by the
 *     dispatcher's stop-handling path, not content the target agent
 *     should act on as a new turn.
 *   - [[Message]] from self never re-triggers (no self-talking-loops).
 *   - [[TopicChange]] from self never re-triggers — labeling the
 *     active thread isn't content the agent should act on again.
 *   - [[ModeChange]] DOES re-trigger the emitter: after switching mode,
 *     the agent is expected to respond *in the new mode*.
 *   - [[Message]], [[ModeChange]], and [[TopicChange]] from others
 *     are valid triggers.
 *   - All other Event types are not triggers by default. Apps with
 *     custom Event subtypes can extend this rule by replacing or
 *     wrapping `TriggerFilter`.
 */
object TriggerFilter {
  def isTriggerFor(p: Participant, e: Event): Boolean = e match {
    case e if e.role == MessageRole.Tool                                     => true
    case _: AgentState                                                => false
    case _: Stop                                                      => false
    case m: Message if m.participantId == p.id                        => false
    case tc: TopicChange if tc.participantId == p.id                  => false
    case _: Message | _: ModeChange | _: TopicChange                  => true
    case _                                                            => false
  }
}
