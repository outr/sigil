package sigil.event

import fabric.rw.*
import lightdb.id.Id
import lightdb.time.Timestamp
import lightdb.util.Nowish
import sigil.conversation.{Conversation, Topic}
import sigil.participant.ParticipantId
import sigil.signal.EventState

/**
 * Per-message emoji reaction. Bug #61.
 *
 * One participant's reaction toggle on a specific Message: each
 * reaction is its own durable Event, and the agent / app reduces
 * the event tail per `(messageId, participantId, emoji)` to the
 * latest entry — that entry's `removed` decides whether the
 * reaction is currently visible. Last-write-wins; no separate
 * Add/Remove paired protocol.
 *
 * **Not load-bearing in the prompt.** `contextFrame` is always
 * `None` — reactions are UI signal, not context the curator
 * surfaces to the model. Agents that want to know about reactions
 * (e.g. an "if 3+ thumbs-down, escalate" rule) query the event log
 * explicitly.
 *
 * **Not a trigger by default.** Sigil's default `TriggerFilter`
 * ignores `Reaction` events — adding 👍 to an agent's reply
 * shouldn't re-fire the agent. Apps with reaction-driven flows
 * override `TriggerFilter` to opt in.
 *
 * Visibility follows the same `MessageVisibility` contract as
 * other events. A reaction on an agent-only message defaults to
 * `MessageVisibility.All` like any other event; apps that want
 * stricter scoping (a reaction on a private message visible only
 * to the original participants) construct with a tighter
 * visibility.
 */
case class Reaction(participantId: ParticipantId,
                    conversationId: Id[Conversation],
                    topicId: Id[Topic],
                    messageId: Id[Event],
                    emoji: String,
                    removed: Boolean = false,
                    state: EventState = EventState.Complete,
                    timestamp: Timestamp = Timestamp(Nowish()),
                    role: MessageRole = MessageRole.Standard,
                    override val visibility: MessageVisibility = MessageVisibility.All,
                    override val origin: Option[Id[Event]] = None,
                    override val source: Option[String] = None,
                    override val contextFrame: Option[sigil.conversation.ContextFrame] = None,
                    _id: Id[Event] = Event.id())
  extends Event derives RW {
  override def withState(state: EventState): Event = copy(state = state)
  override def withOrigin(origin: Option[Id[Event]]): Event = copy(origin = origin)
  override def withContextFrame(contextFrame: Option[sigil.conversation.ContextFrame]): Event = copy(contextFrame = contextFrame)
  override def withConversationId(conversationId: Id[sigil.conversation.Conversation]): Event = copy(conversationId = conversationId)
}
