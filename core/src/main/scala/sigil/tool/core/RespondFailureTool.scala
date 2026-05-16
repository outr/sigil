package sigil.tool.core

import sigil.TurnContext
import sigil.event.{Event, Message, MessageDisposition}
import sigil.signal.EventState
import sigil.tool.{ToolExample, ToolName, TypedTool}
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
case object RespondFailureTool extends TypedTool[RespondFailureInput](
  name = ToolName("respond_failure"),
  description =
    """Signal that you can't complete the task. `recoverable` = true if a retry might succeed
      |(transient: rate limits, network); false if permanent (missing permissions, unsupported input).""".stripMargin,
  examples = Nil
) with RespondFamilyTool {
  override def paginate: Boolean = false

  override protected def executeTyped(input: RespondFailureInput, context: TurnContext): rapid.Stream[Event] = {
    rapid.Stream.emits(List(Message(
      participantId  = context.caller,
      conversationId = context.conversation.id,
      topicId        = context.conversation.currentTopicId,
      content        = Vector(ResponseContent.Text(input.reason)),
      disposition    = MessageDisposition.Failure(recoverable = input.recoverable),
      state          = EventState.Complete,
      modelId        = context.modelId
    )))
  }
}
