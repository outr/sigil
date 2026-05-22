package sigil.tool.util

import fabric.rw.*
import rapid.Task
import sigil.TurnContext
import sigil.tool.model.CompleteTaskInput
import sigil.tool.{TextToolOutput, Tool, ToolExample, ToolName, ToolResult}

/**
 * `complete_task` — the worker's typed terminator. Workers call
 * this with a one-paragraph summary to settle their workflow run;
 * [[sigil.workflow.SigilAgentDecisionStep]]'s execute observes the
 * tool call via the provider event stream and terminates the
 * iteration with the supplied summary.
 *
 * Companion to the `Complete:` line marker. The marker remains as
 * a fallback for providers / models that don't surface
 * structured tool calls cleanly; the tool form is the canonical
 * shape going forward — typed args, schema-validated, no
 * LLM-format-drift risk.
 *
 * The agent-loop step intercepts the tool call from the provider
 * event stream BEFORE the orchestrator dispatches; `executeResult`
 * is reached only if the tool was registered globally and called
 * from outside an AgentDecisionStep context. In that case it
 * resolves a benign acknowledgement so the call still pairs cleanly.
 */
case object CompleteTaskTool extends Tool {
  type Input  = CompleteTaskInput
  type Output = TextToolOutput
  val inputRW  = summon[RW[CompleteTaskInput]]
  val outputRW = summon[RW[TextToolOutput]]
  val name = ToolName("complete_task")
  val description =
    """Worker terminator: call this when you've finished the work the user briefed you on.
      |Pass a one-paragraph `summary` describing what you did and what the result is.
      |The framework settles your run; the parent agent picks up the summary from the
      |TaskExecuted event and decides how to surface it to the user.""".stripMargin
  override val examples = List(
    ToolExample("Worker reports a research result",
      CompleteTaskInput(summary = "Found 3 RAG papers from 2026; cited the strongest 2 in /tmp/papers.md."))
  )
  override val keywords = Set("complete", "done", "finish", "terminate", "settle", "result")

  override def executeResult(input: CompleteTaskInput,
                             ctx: TurnContext): Task[ToolResult[TextToolOutput]] =
    Task.pure(ToolResult.Success(TextToolOutput(input.summary)))
}
