package sigil.tool.core

import fabric.rw.*
import rapid.Task
import sigil.tool.ToolContext
import sigil.event.{Message, MessageDisposition}
import sigil.signal.EventState
import sigil.tool.{TextToolOutput, ToolName, ToolResult}
import sigil.tool.model.{RespondFailureInput, ResponseContent}

/**
 * Signal that the agent cannot complete the current task. Emits a
 * `Failure`-disposition Message carrying the reason as markdown.
 * Orchestrator and apps pattern-match on
 * `Message.disposition match { case Failure(...) => … }`.
 *
 * **Not in the default `CoreTools.all` roster** — the unified
 * `respond` tool accepts a `disposition` field with `Success` /
 * `Failure` values that produces the same `Failure`-disposition
 * Message. This tool is kept in core for apps that prefer the
 * named-tool dispatch path.
 */
case object RespondFailureTool extends RespondFamilyTool {
  type Input  = RespondFailureInput
  type Output = TextToolOutput
  val inputRW  = summon[RW[RespondFailureInput]]
  val outputRW = summon[RW[TextToolOutput]]

  val name = ToolName("respond_failure")
  val description =
    """Signal that you can't complete the task. `recoverable` = true if a retry might succeed
      |(transient: rate limits, network); false if permanent (missing permissions, unsupported input).""".stripMargin

  override def executeResult(input: RespondFailureInput, context: ToolContext): Task[ToolResult[TextToolOutput]] =
    context.emit(Message(
      participantId  = context.caller,
      conversationId = context.conversation.id,
      topicId        = context.conversation.currentTopicId,
      content        = Vector(ResponseContent.Text(input.reason)),
      disposition    = MessageDisposition.Failure(recoverable = input.recoverable),
      state          = EventState.Complete,
      modelId        = context.modelId
    )).map(_ => ToolResult.Success(TextToolOutput("")))
}
