package sigil.event

import fabric.rw.*
import lightdb.id.Id
import lightdb.time.Timestamp
import lightdb.util.Nowish
import sigil.conversation.{Conversation, Topic}
import sigil.participant.ParticipantId
import sigil.provider.Complexity
import sigil.signal.EventState

/**
 * Emitted when the conversation's pinned routing-complexity tier
 * changes. Symmetric with [[ModeChange]] on the mode axis.
 *
 * Born `Complete` (single-shot — there's no `Active`/streaming
 * lifecycle here, unlike `ModeChange`'s pulse). UI consumers reduce
 * directly off this event to maintain a "current tier" indicator
 * (chip in the AppBar, transition divider in the chat) without
 * polling [[Conversation.pinnedComplexity]] or snooping on
 * [[RouteResolved]] (which fires every turn — too noisy for a "the
 * user pinned" UX event).
 *
 *   - `previousTier` — what was pinned before this transition.
 *   - `newTier` — what's pinned after. `None` means the pin was cleared.
 *   - `reason` — which tool transition fired this event. Apps reduce
 *     `Pinned` / `Repinned` / `Unpinned` distinctly without diffing
 *     `previousTier` / `newTier` to figure out which path ran.
 *
 * `ControlPlaneEvent` — matches `ModeChange` / `RouteResolved`. Doesn't
 * enter agent ContextFrames; the agent reads pinned-complexity state via
 * the conversation snapshot. The event is for UI consumers + wire-log
 * forensics. Sigil bug #177.
 */
case class ComplexityChange(participantId: ParticipantId,
                            conversationId: Id[Conversation],
                            topicId: Id[Topic],
                            previousTier: Option[Complexity],
                            newTier: Option[Complexity],
                            reason: ComplexityChange.Reason,
                            topicIndex: Int = 0,
                            state: EventState = EventState.Complete,
                            timestamp: Timestamp = Timestamp(Nowish()),
                            role: MessageRole = MessageRole.Standard,
                            override val visibility: MessageVisibility = MessageVisibility.All,
                            override val origin: Option[Id[Event]] = None,
                            override val source: Option[String] = None,
                            override val contextFrame: Option[sigil.conversation.ContextFrame] = None,
                            _id: Id[Event] = Event.id())
  extends Event with ControlPlaneEvent derives RW {
  override def withState(state: EventState): Event = copy(state = state)
  override def withOrigin(origin: Option[Id[Event]]): Event = copy(origin = origin)
  override def withContextFrame(contextFrame: Option[sigil.conversation.ContextFrame]): Event =
    copy(contextFrame = contextFrame)
  override def withConversationId(conversationId: Id[Conversation]): Event =
    copy(conversationId = conversationId)
}

object ComplexityChange {

  /** Which tool transition fired this event. Reducers key off the
    * enum case rather than diffing `previousTier` / `newTier`. */
  enum Reason derives RW {
    /** `pin_complexity` set a tier where nothing was pinned before. */
    case Pinned

    /** `pin_complexity` replaced an existing pin with another tier
      * (or the same tier — UI can still render a "confirmed" pulse). */
    case Repinned

    /** `unpin_complexity` cleared the pin. */
    case Unpinned
  }
}
