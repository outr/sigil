package sigil.signal

import fabric.rw.*
import lightdb.id.Id
import sigil.conversation.{Conversation, Topic}
import sigil.event.Event

/**
 * Re-homes an already-persisted [[Event]] onto a different topic —
 * both `topicId` and `topicIndex` are replaced via
 * [[Event.withTopic]], so the delta applies uniformly to every event
 * type.
 *
 * Emitted by the framework's founding-turn sweep: when a turn's
 * respond lands a [[sigil.event.TopicChange]] Switch, the events of
 * the turn that CAUSED the change (the triggering user message, the
 * tool calls, the reply) were stamped with the previous topic — the
 * classifier only decides at the end of the turn, so correct
 * attribution is necessarily retroactive. The sweep folds the new
 * topic onto those events at turn settle; clients apply the same
 * delta to their in-memory rows so live views match the persisted
 * (and replayed) stamps.
 */
case class TopicDelta(target: Id[Event],
                      conversationId: Id[Conversation],
                      topicId: Id[Topic],
                      topicIndex: Int)
  extends Delta derives RW {

  override def apply(target: Event): Event = target.withTopic(topicId, topicIndex)
}
