package sigil.signal

import fabric.rw.*
import lightdb.id.Id
import sigil.conversation.Conversation
import sigil.event.{AgentState, Event}

/**
 * Transient update to an [[sigil.event.AgentState]] event. Carries whichever subset of
 * mutations the emitter wants to apply — typically `activity` alone
 * (Thinking → Typing mid-turn) or `activity` + `state` together at the
 * terminal transition (Idle + Complete).
 *
 * Non-target events are passed through unchanged — the typed target guards
 * application.
 */
case class AgentStateDelta(target: Id[Event],
                           conversationId: Id[Conversation],
                           activity: Option[AgentActivity] = None,
                           state: Option[EventState] = None)
  extends Delta derives RW {

  override def apply(target: Event): Event = target match {
    case a: AgentState =>
      a.copy(
        activity = activity.getOrElse(a.activity),
        state = state.getOrElse(a.state)
      )
    case other => other
  }
}
