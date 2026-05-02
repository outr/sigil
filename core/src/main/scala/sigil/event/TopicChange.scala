package sigil.event

import fabric.rw.*
import lightdb.id.Id
import lightdb.time.Timestamp
import lightdb.util.Nowish
import sigil.conversation.{Conversation, Topic}
import sigil.participant.ParticipantId
import sigil.signal.EventState

/**
 * Emitted when an agent's `respond` call indicates a topic shift — either a
 * [[TopicChangeKind.Switch]] (a new or existing [[Topic]] is now active) or
 * a [[TopicChangeKind.Rename]] (the current topic keeps its identity but
 * changes label). Driven by the LLM via the `topicChangeType` field on
 * [[sigil.tool.model.RespondInput]]; the orchestrator maps
 * `TopicChangeType.Change → Switch` and `TopicChangeType.Update → Rename`,
 * with `NoChange` emitting no event.
 *
 * `topicId` is the topic active AFTER the change (the post-transition
 * topic). On a Switch, `kind.previousTopicId` carries the topic that was
 * active before; on a Rename, `kind.previousLabel` carries the prior label.
 * `newLabel` and `newSummary` carry the active topic's label + summary
 * after the change — both are bundled on the event so clients rendering
 * a topic header / breadcrumb don't need a follow-up `Topic.fromId`
 * round-trip just to display the summary alongside the label.
 *
 * Born `Active` so subscribers can react (UI flashes the topic chip, search
 * index re-scopes). The framework then broadcasts a `StateDelta` transitioning
 * it to `Complete`, at which point `Sigil.updateConversationProjection`
 * writes `Conversation.currentTopicId` (on a Switch). Same-Topic, same-label
 * no-ops and `NoChange` declarations are suppressed upstream so this event
 * fires only on real changes.
 */
case class TopicChange(kind: TopicChangeKind,
                       newLabel: String,
                       newSummary: String,
                       participantId: ParticipantId,
                       conversationId: Id[Conversation],
                       topicId: Id[Topic],
                       state: EventState = EventState.Active,
                       timestamp: Timestamp = Timestamp(Nowish()),
                       role: MessageRole = MessageRole.Standard,
                       override val origin: Option[Id[Event]] = None,
                       _id: Id[Event] = Event.id())
  extends Event derives RW {
  override def withState(state: EventState): Event = copy(state = state)
  override def withOrigin(origin: Option[Id[Event]]): Event = copy(origin = origin)
}
