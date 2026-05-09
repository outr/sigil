package sigil.event

import fabric.rw.*
import lightdb.id.Id
import lightdb.time.Timestamp
import lightdb.util.Nowish
import sigil.conversation.{Conversation, Topic}
import sigil.participant.ParticipantId
import sigil.signal.EventState
import sigil.tool.ToolName

/**
 * Records a consent decision for a [[sigil.tool.Tool]] whose
 * `requiresUserConsent` flag is set. Sigil bug #83.
 *
 * Tools that mutate user state, run expensive imports, or
 * contact external services declare `requiresUserConsent = true`;
 * the orchestrator refuses to dispatch them until a
 * `ToolApproval(toolName, approved = true, conversationId)`
 * record exists. The agent records the decision via
 * [[sigil.tool.core.RecordConsentTool]] after observing the
 * user's reply (typically through a `respond_options` round-
 * trip the agent designs itself).
 *
 * **First-call-per-conversation semantics**: a single approved
 * record covers all subsequent calls to that tool in the same
 * conversation. Apps wanting per-call gating record a fresh
 * decision before each invocation; apps wanting per-(tool,args)
 * gating layer their own logic in `Sigil.recordConsent`.
 *
 * **`approved = false` is sticky**: once the user declines,
 * subsequent calls to the same tool in the same conversation
 * keep refusing until the agent records a fresh
 * `approved = true`. The decline reason flows back to the
 * agent in the refusal Tool-result so it knows why.
 *
 * Extends [[ControlPlaneEvent]] — consent metadata, not
 * conversation history. [[sigil.conversation.FrameBuilder]]
 * skips it so the agent's prompt doesn't carry per-tool
 * approval lines as noise; the orchestrator queries
 * `db.events` directly when it needs the latest decision.
 */
case class ToolApproval(toolName: ToolName,
                        approved: Boolean,
                        reason: Option[String] = None,
                        participantId: ParticipantId,
                        conversationId: Id[Conversation],
                        topicId: Id[Topic],
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
