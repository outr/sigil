package sigil.workflow.tool

import fabric.rw.*
import lightdb.id.Id
import rapid.{Stream, Task}
import sigil.TurnContext
import sigil.event.Event
import sigil.tool.{ToolExample, ToolInput, ToolName, TypedTool}
import sigil.workflow.{WorkflowScheduler, WorkflowTemplate}

case class RunWorkflowInput(workflowId: String,
                            variables: Map[String, String] = Map.empty) extends ToolInput derives RW

/**
 * Schedule an immediate run of a persisted workflow template.
 * Subject to `accessibleSpaces` authz.
 *
 * `variables` overrides the template's variable defaults. The
 * agent passes string values (LLMs don't natively author JSON);
 * the framework wraps them as fabric `Json.Str`.
 *
 * Returns the resulting `runId` — the agent threads this through
 * subsequent calls (`cancel_workflow`, `resume_workflow`,
 * etc.) to refer to a specific run.
 */
final class RunWorkflowTool extends TypedTool[RunWorkflowInput](
  name = ToolName("run_workflow"),
  description =
    """Schedule a run of a persisted workflow template.
      |
      |`workflowId` is the template id. `variables` (optional) overrides the template's
      |variable defaults — pass any inputs the workflow's `variableDefs` declare.
      |Returns the run id for cancel / resume / inspection.""".stripMargin,
  examples = List(
    ToolExample(
      "run a template with one input",
      RunWorkflowInput(workflowId = "wf-abc", variables = Map("input" -> "today's events"))
    )
  ),
  keywords = Set("workflow", "run", "schedule", "execute", "trigger")
) with WorkflowToolSupport {
  override protected def executeTyped(input: RunWorkflowInput, ctx: TurnContext): Stream[Event] = {
    workflowHost(ctx) match {
      case Left(err) => reply(ctx, err, isError = true)
      case Right(host) =>
        val id = Id[WorkflowTemplate](input.workflowId)
        val task = host.withDB(_.workflowTemplates.transaction(_.get(id))).flatMap {
          case None => Task.pure(s"Workflow '${input.workflowId}' not found.")
          case Some(template) =>
            authorizeAccess(host, template, ctx.chain).flatMap {
              case Left(_) => Task.pure(s"Workflow '${input.workflowId}' not found.")
              case Right(_) =>
                val vars: Map[String, fabric.Json] = input.variables.map { case (k, v) => k -> (fabric.str(v): fabric.Json) }
                WorkflowScheduler.scheduleTemplate(host, host.workflowDb, template, vars, Some(ctx.caller))
                  .map(wf => s"Workflow '${template.name}' scheduled (runId=${wf._id.value}).")
                  .handleError(e => Task.pure(s"Failed to schedule workflow: ${e.getMessage}"))
            }
        }
        Stream.force(task.map(text => reply(ctx, text)))
    }
  }
}
