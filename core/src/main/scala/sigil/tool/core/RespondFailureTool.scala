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
 *
 * **Deprecated** (sigil bug #157) — the unified `respond` tool accepts
 * `RespondContent.Failure(...)` directly. Kept for backwards-
 * compatibility with apps that registered this tool by name; new code
 * should use `respond` with the `Failure` content kind.
 */
@deprecated("Use `RespondTool` with `RespondContent.Failure(...)`. Sigil bug #157.", "0.x")
case object RespondFailureTool extends TypedTool[RespondFailureInput](
  name = ToolName("respond_failure"),
  description =
    """Signal that you can't complete the task. `recoverable` = true if a retry might succeed
      |(transient: rate limits, network); false if permanent (missing permissions, unsupported input).""".stripMargin,
  examples = Nil
) with RespondFamilyTool {
  override protected def executeTyped(input: RespondFailureInput, context: TurnContext): rapid.Stream[Event] = {
    val block = ResponseContent.Failure(reason = input.reason, recoverable = input.recoverable)
    rapid.Stream.emits(List(Message(
      participantId = context.caller,
      conversationId = context.conversation.id,
      topicId = context.conversation.currentTopicId,
      content = Vector(block),
      state = EventState.Complete,
      modelId = context.modelId
    )))
  }
}
