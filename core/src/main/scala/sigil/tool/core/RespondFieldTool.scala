package sigil.tool.core

import fabric.rw.*
import rapid.Task
import sigil.tool.ToolContext
import sigil.event.Message
import sigil.signal.EventState
import sigil.tool.{TextToolOutput, ToolName, ToolResult}
import sigil.tool.model.{RespondFieldInput, ResponseContent}

/**
 * Emit a labeled key/value field as part of the agent's reply. Use for
 * compact card-shaped content (status summaries, news metadata, product
 * attributes) where the renderer needs label/value/icon structure.
 *
 * **Not in the default `CoreTools.all` roster.** The unified `respond`
 * tool accepts a `> [!Field icon="…"]\n> Label: Value` markdown
 * callout that emits the same Field block via
 * [[sigil.tool.model.MarkdownContentParser]]. This standalone tool
 * stays in core for apps that prefer typed-emission paths.
 */
case object RespondFieldTool extends RespondFamilyTool {
  type Input  = RespondFieldInput
  type Output = TextToolOutput
  val inputRW  = summon[RW[RespondFieldInput]]
  val outputRW = summon[RW[TextToolOutput]]

  val name = ToolName("respond_field")
  val description =
    """Emit a labeled key/value field — for compact metadata (status, source, timestamp). `icon`
      |is an optional semantic hint.""".stripMargin

  override def executeResult(input: RespondFieldInput, context: ToolContext): Task[ToolResult[TextToolOutput]] = {
    val block = ResponseContent.Field(label = input.label, value = input.value, icon = input.icon)
    context.emit(Message(
      participantId = context.caller,
      conversationId = context.conversation.id,
      topicId = context.conversation.currentTopicId,
      content = Vector(block),
      state = EventState.Complete,
      modelId = context.modelId
    )).map(_ => ToolResult.Success(TextToolOutput("")))
  }
}
