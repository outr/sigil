package sigil.tool.util

import rapid.Stream
import sigil.TurnContext
import sigil.event.Event
import sigil.tool.model.CompleteTaskInput
import sigil.tool.{ToolExample, ToolName, TypedTool}

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
 * Doesn't have an `executeTyped` body of consequence — the
 * agent-loop step intercepts the tool call from the provider
 * event stream BEFORE the orchestrator dispatches; if for some
 * reason the orchestrator does dispatch (e.g., the tool was
 * registered globally and called from outside an
 * AgentDecisionStep context), the execute is a no-op so the call
 * surfaces cleanly without a synthetic result.
 */
case object CompleteTaskTool
  extends TypedTool[CompleteTaskInput](
    name = ToolName("complete_task"),
    description =
      """Worker terminator: call this when you've finished the work the user briefed you on.
        |Pass a one-paragraph `summary` describing what you did and what the result is.
        |The framework settles your run; the parent agent picks up the summary from the
        |TaskExecuted event and decides how to surface it to the user.""".stripMargin,
    examples = List(
      ToolExample("Worker reports a research result",
        CompleteTaskInput(summary = "Found 3 RAG papers from 2026; cited the strongest 2 in /tmp/papers.md."))
    ),
    keywords = Set("complete", "done", "finish", "terminate", "settle", "result")
  ) {
  override protected def executeTyped(input: CompleteTaskInput, ctx: TurnContext): Stream[Event] = Stream.empty
}
