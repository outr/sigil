package sigil.tool.core

import fabric.rw.*
import rapid.Task
import sigil.event.{Message, MessageDisposition, MessageRole, MessageVisibility}
import sigil.signal.EventState
import sigil.tool.{JsonInput, TextToolOutput, ToolContext, ToolName, ToolResult}
import sigil.tool.model.ResponseContent

/**
 * Framework sentinel for tool dispatch — substituted by the orchestrator when a
 * model emits a `tool_use` block whose name doesn't resolve to any registered
 * tool. Sigil bug #271.
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
    val message =
      s"Unknown tool '$invokedName'. The framework didn't dispatch this call because the name isn't " +
        "in this turn's available tool roster. Call `find_capability` to discover the catalog, or call " +
        "`respond` to tell the user what you tried and what's missing."
    context.emit(Message(
      participantId  = context.caller,
      conversationId = context.conversation.id,
      topicId        = context.conversation.currentTopicId,
      role           = MessageRole.Tool,
      content        = Vector(ResponseContent.Text(message)),
      state          = EventState.Complete,
      disposition    = MessageDisposition.Failure(recoverable = true),
      visibility     = MessageVisibility.Agents,
      origin         = Some(context.invokeId)
    )).map(_ => ToolResult.failure(message))
  }
}
