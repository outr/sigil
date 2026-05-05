package spec

import rapid.Stream
import sigil.TurnContext
import sigil.event.{Event, Message, MessageRole}
import sigil.signal.EventState
import sigil.tool.model.ResponseContent
import sigil.tool.{ToolExample, ToolName, TypedTool}

/** Test-only tool that echoes its `text` input back as a tool-role
  * Message reading `Echo: <text>`. Used by [[LlamaCppWorkerSpec]] to
  * verify the worker's tool-dispatch path: when the LLM calls
  * `echo_back` with a known text, the spec inspects the worker's
  * settle payload and prior reasoning to confirm the tool ran and the
  * result was folded into the next iteration. */
case object EchoBackTool extends TypedTool[EchoBackInput](
  name = ToolName("echo_back"),
  description =
    """Echoes the supplied text back. Test-only tool: call with `text`
      |to verify a tool-call round-trip works.""".stripMargin,
  examples = List(
    ToolExample("Echo a marker string", EchoBackInput("hello-from-tool"))
  ),
  keywords = Set("echo", "test")
) {
  override protected def executeTyped(input: EchoBackInput, ctx: TurnContext): Stream[Event] =
    Stream.emit[Event](Message(
      participantId  = ctx.caller,
      conversationId = ctx.conversation.id,
      topicId        = ctx.conversation.currentTopicId,
      content        = Vector(ResponseContent.Text(s"Echo: ${input.text}")),
      state          = EventState.Complete,
      role           = MessageRole.Tool
    ))
}
