package sigil.event

import fabric.rw.*
import lightdb.id.Id
import lightdb.time.Timestamp
import lightdb.util.Nowish
import sigil.conversation.{Conversation, Topic}
import sigil.participant.ParticipantId
import sigil.signal.EventState

/**
 * Per-`(conversation, participant)` read cursor. Bug #62.
 *
 * The participant's last-read position in a conversation,
 * expressed as a server-stamped `Timestamp` — "I've consumed
 * every event whose timestamp is ≤ this." Updates flow via
 * [[sigil.signal.ReadStateDelta]]; the underlying event row is
 * mutated in place rather than duplicated, so a per-scroll wire
 * Notice doesn't grow `db.events` linearly.
 *
 * Pattern matches [[AgentState]]: deterministic `_id` derived
 * from `(conversationId, participantId)` so the same row gets
 * upserted on every read advance, and snapshot replay
 * automatically delivers the current state to a fresh-connect
 * client.
 *
 * Why timestamps over ordinals: Sigil events don't carry
 * monotonic ordinals; every event has a server-stamped
 * `timestamp` already. Clients that want to mark "I've read up
 * to event X" call `Sigil.markRead(convId, participantId, X._id)`
 * — the framework resolves to X's authoritative server timestamp,
 * sidestepping any client-clock drift.
 *
 * **Not load-bearing in the prompt.** `contextFrame = None` —
 * read receipts are UI signal, not curator-visible context. The
 * curator never sees them; the FrameBuilder skips them.
 */
case class ReadState(participantId: ParticipantId,
                     conversationId: Id[Conversation],
                     topicId: Id[Topic],
                     lastReadAt: Timestamp,
                     state: EventState = EventState.Active,
                     timestamp: Timestamp = Timestamp(Nowish()),
                     role: MessageRole = MessageRole.Standard,
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

object ReadState {
  /** Deterministic id for a `(conversationId, participantId)`
    * pair. The same id gets upserted on every read advance, so
    * `db.events` carries one row per pair regardless of how many
    * times the participant scrolls. Mirrors AgentState's per-claim
    * lock-id convention. */
  def idFor(conversationId: Id[Conversation], participantId: ParticipantId): Id[Event] =
    Id(s"read:${conversationId.value}:${participantId.value}")
}
