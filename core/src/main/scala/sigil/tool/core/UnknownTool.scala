package sigil.tool.core

import fabric.rw.*
import rapid.Task
import sigil.event.{Message, MessageDisposition, MessageRole, MessageVisibility}
import sigil.signal.EventState
import sigil.tool.{JsonInput, RefusalPayload, TextToolOutput, ToolContext, ToolName, ToolResult}
import sigil.tool.model.ResponseContent

/**
 * Framework sentinel for tool dispatch — substituted by the orchestrator when a
 * model emits a `tool_use` block whose name doesn't resolve to any registered
* tool.
 *
 * `UnknownTool` is NOT registered into the static roster, NEVER discoverable
 * via `find_capability`, and never advertised to the model. It exists only as
 * the dispatch fallback so every model-emitted tool call lands on the same
 * uniform pipeline (typed `ToolInvoke` + paired Tool-role Failure Message)
 * regardless of whether the name resolves. The pre-fix path emitted a
 * `ProviderEvent.Error` that the agent loop never re-triggered off of,
 * eventually throwing `AgentRunawayException`.
 *
 * The model's invoked name is read from [[ToolContext.toolName]] at execution
 * time so a single singleton suffices — no per-call allocation. The failure
 * message includes the invoked name explicitly so the model can self-correct
 * (typically by calling `find_capability` or `respond`) on its next iteration.
 */
case object UnknownTool extends sigil.tool.Tool {
  type Input  = JsonInput
  type Output = TextToolOutput

  val inputRW  = summon[RW[JsonInput]]
  val outputRW = summon[RW[TextToolOutput]]

  val name = ToolName("_unknown_tool")
  val description =
    "Framework-internal sentinel — never advertised to the model. Substituted by the orchestrator when a " +
      "tool name doesn't resolve, so the call lands a typed Failure the agent can act on instead of aborting the turn."

  override def executeResult(input: JsonInput, context: ToolContext): Task[ToolResult[TextToolOutput]] = {
    val invokedName = context.toolName.value
    // The refusal carries the closest-name match from the offered roster
    // plus that match's schema + example so the agent can retry against
    // a real tool on its next iteration without re-running discovery.
    // The model's args are preserved verbatim in the refusal body so the
    // agent can carry them over to the corrected call.
    val sentArgs: Option[String] = input.json match {
      case fabric.Obj(map) if map.isEmpty => None
      case fabric.Str(s, _)               => Some(s)
      case other                          => Some(fabric.io.JsonFormatter.Default(other))
    }
    val failure = RefusalPayload.unknownTool(
      invokedName = invokedName,
      offered     = context.turn.offeredTools,
      sentArgs    = sentArgs
    )
    context.emit(Message(
      participantId  = context.caller,
      conversationId = context.conversation.id,
      topicId        = context.conversation.currentTopicId,
      role           = MessageRole.Tool,
      content        = Vector(ResponseContent.Text(failure.message)),
      state          = EventState.Complete,
      disposition    = MessageDisposition.Failure(recoverable = true),
      visibility     = MessageVisibility.Agents,
      origin         = Some(context.invokeId)
    )).map(_ => failure)
  }
}
