package sigil.signal

import fabric.rw.*
import lightdb.id.Id
import sigil.conversation.Conversation
import sigil.event.Event

/**
 * Universal state-transition delta. Drives the Active → Complete transition
 * for any [[Event]] by delegating to its `withState` implementation. Works
 * for every Event type without a match expression — adding a new Event adds
 * no load-bearing code here.
 *
 * Typical emission: the orchestrator (or whichever party owns the
 * transition) emits the Event with `state = Active`, any consumers react,
 * and then the same party broadcasts a `StateDelta(target, …, Complete)` so
 * historical readers (replay, late subscribers) see the Complete state and
 * skip reactive side-effects.
 */
case class StateDelta(target: Id[Event],
                      conversationId: Id[Conversation],
                      state: EventState) extends Delta derives RW {
  override def apply(target: Event): Event = target.withState(state)
}
