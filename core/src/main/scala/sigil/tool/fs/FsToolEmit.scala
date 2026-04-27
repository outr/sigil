package sigil.tool.fs

import fabric.Json
import fabric.io.JsonFormatter
import sigil.TurnContext
import sigil.event.{Event, Message, MessageRole}
import sigil.signal.EventState
import sigil.tool.model.ResponseContent

/**
 * Internal helper for the `sigil.tool.fs` family — emits a tool
 * result as a `MessageRole.Tool` Message whose content is the JSON
 * rendering of `payload`. Centralizes the message-construction
 * boilerplate.
 */
private[fs] object FsToolEmit {
  def apply(payload: Json, ctx: TurnContext): Message = Message(
    participantId  = ctx.caller,
    conversationId = ctx.conversation.id,
    topicId        = ctx.conversation.currentTopicId,
    content        = Vector(ResponseContent.Text(JsonFormatter.Compact(payload))),
    state          = EventState.Complete,
    role           = MessageRole.Tool
  )
}
