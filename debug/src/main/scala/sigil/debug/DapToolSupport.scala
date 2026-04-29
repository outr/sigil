package sigil.debug

import rapid.{Stream, Task}
import sigil.TurnContext
import sigil.event.{Event, Message, MessageRole, MessageVisibility}
import sigil.signal.EventState
import sigil.tool.model.ResponseContent

/**
 * Shared plumbing for the agent-facing DAP tools. Mirrors
 * `LspToolSupport` / `BspToolSupport`: a `withSession` block that
 * looks up the active session by id and surfaces a clear error
 * when none exists, plus a `reply` helper that emits the result as
 * a `Role.Tool` Message.
 */
trait DapToolSupport {
  protected def manager: DapManager

  /** Run `body` against the named session. If no session with that
    * id is active, reply with an error. */
  protected def withSession(sessionId: String, context: TurnContext)
                           (body: DapSession => Task[String]): Stream[Event] = {
    val task = manager.get(sessionId) match {
      case None =>
        Task.pure(reply(context, s"No active debug session '$sessionId'. Launch one with `dap_launch` first.", isError = true))
      case Some(session) =>
        body(session).map(text => reply(context, text, isError = false))
          .handleError(e => Task.pure(reply(context, s"DAP error: ${e.getMessage}", isError = true)))
    }
    Stream.force(task.map(Stream.emit))
  }

  protected def reply(context: TurnContext, text: String, isError: Boolean): Event =
    Message(
      participantId = context.caller,
      conversationId = context.conversation.id,
      topicId = context.conversation.currentTopicId,
      content = Vector(ResponseContent.Text(text)),
      state = EventState.Complete,
      role = MessageRole.Tool,
      visibility = MessageVisibility.All
    )
}
