package sigil.event

import fabric.rw.*
import lightdb.id.Id
import lightdb.time.Timestamp
import lightdb.util.Nowish
import sigil.conversation.{Conversation, Topic}
import sigil.participant.ParticipantId
import sigil.signal.EventState

/**
 * One streaming-progress log line emitted by a tool during its
 * execution. Paired to its parent [[ToolInvoke]] via `origin` so
 * consumers can render the tail of paired logs in the per-tool chat
 * chip while the call is still running.
 *
 * Distinct from [[sigil.signal.ToolProgress]] (a transient Notice
 * carrying ONE replacement status string per chip). ToolLog is
 * persistent + append-only — every line settles into `db.events`,
 * survives reload + replay, gives the agent and audit tooling a
 * faithful record of what a long-running tool actually did.
 *
 * Distinct from [[Message]] with `role = MessageRole.Tool`: ToolLog
 * extends [[ControlPlaneEvent]] so [[sigil.conversation.FrameBuilder]]
 * skips it — logs are diagnostic / UX only and never bloat the
 * agent's prompt context. Settled [[ToolResults]] is what makes it
 * into the agent's frame; logs supplement that surface, they don't
 * replace it.
 *
 * `origin` MUST point to the originating ToolInvoke. The framework
 * orchestrator stamps it automatically for events emitted from
 * `Tool.execute` / `TypedTool.executeTyped` streams; tools using
 * the [[sigil.TurnContext.toolLog]] helper inherit
 * `currentToolInvokeId` without threading the id manually.
 */
case class ToolLog(content: String,
                   level: LogLevel = LogLevel.Info,
                   participantId: ParticipantId,
                   conversationId: Id[Conversation],
                   topicId: Id[Topic],
                   topicIndex: Int = 0,
                   override val visibility: MessageVisibility = MessageVisibility.All,
                   state: EventState = EventState.Complete,
                   timestamp: Timestamp = Timestamp(Nowish()),
                   role: MessageRole = MessageRole.Standard,
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
