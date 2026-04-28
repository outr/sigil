package sigil.tool.core

import sigil.TurnContext
import sigil.event.{Event, Message}
import sigil.signal.EventState
import sigil.tool.{ToolExample, ToolName, TypedTool}
import sigil.tool.model.{RespondFailureInput, ResponseContent}

/**
 * Signal that the agent cannot complete the current task. Apps and the
 * orchestrator can pattern-match on `ResponseContent.Failure` to decide
 * whether to retry, alert, or surface an error UI.
 */
case object RespondFailureTool extends TypedTool[RespondFailureInput](
  name = ToolName("respond_failure"),
  description =
    """Signal that you can't complete the user's task. Use this instead of `respond` with prose like
      |"I can't help with that" — the typed Failure block lets the orchestrator and UI react properly.
      |
      |- `reason` — short user-facing explanation.
      |- `recoverable` — true if a retry might succeed (transient: rate limits, network); false if
      |  the failure is permanent for this request (missing permissions, unsupported input).""".stripMargin,
  examples = List(
    ToolExample(
      "Permanent failure",
      RespondFailureInput(reason = "I don't have access to internal pricing data.", recoverable = false)
    ),
    ToolExample(
      "Transient failure",
      RespondFailureInput(reason = "Upstream API timed out — try again in a moment.", recoverable = true)
    )
  )
) {
  override protected def executeTyped(input: RespondFailureInput, context: TurnContext): rapid.Stream[Event] = {
    val block = ResponseContent.Failure(reason = input.reason, recoverable = input.recoverable)
    rapid.Stream.emits(List(Message(
      participantId = context.caller,
      conversationId = context.conversation.id,
      topicId = context.conversation.currentTopicId,
      content = Vector(block),
      state = EventState.Complete
    )))
  }
}
