package sigil.workflow.tool

import fabric.rw.*
import lightdb.id.Id
import rapid.Task
import sigil.TurnContext
import sigil.tool.{TextToolOutput, Tool, ToolExample, ToolInput, ToolName, ToolResult}
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
final class RunWorkflowTool extends Tool with WorkflowToolSupport {
  type Input  = RunWorkflowInput
  type Output = TextToolOutput
  val inputRW  = summon[RW[RunWorkflowInput]]
  val outputRW = summon[RW[TextToolOutput]]
  val name = ToolName("run_workflow")
  val description =
    """Schedule a run of a persisted workflow template.
      |
      |`workflowId` is the template id. `variables` (optional) overrides the template's
      |variable defaults — pass any inputs the workflow's `variableDefs` declare.
      |Returns the run id for cancel / resume / inspection.""".stripMargin
  override val examples = List(
    ToolExample(
      "run a template with one input",
      RunWorkflowInput(workflowId = "wf-abc", variables = Map("input" -> "today's events"))
    )
  )
  override val keywords = Set("workflow", "run", "schedule", "execute", "trigger")

  override def executeResult(input: RunWorkflowInput, ctx: TurnContext): Task[ToolResult[TextToolOutput]] = withHostResult(ctx) { host =>
    val id = Id[WorkflowTemplate](input.workflowId)
    host.withDB(_.workflowTemplates.transaction(_.get(id))).flatMap {
      case None => Task.pure(s"Workflow '${input.workflowId}' not found.")
      case Some(template) =>
        authorizeAccess(host, template, ctx.chain).flatMap {
          case Left(_) => Task.pure(s"Workflow '${input.workflowId}' not found.")
          case Right(_) =>
            val vars: Map[String, fabric.Json] = input.variables.map { case (k, v) => k -> (fabric.str(v): fabric.Json) }
            WorkflowScheduler.scheduleTemplate(host, template, vars, Some(ctx.caller))
              .map(wf => s"Workflow '${template.name}' scheduled (runId=${wf._id.value}).")
              .handleError(e => Task.pure(s"Failed to schedule workflow: ${e.getMessage}"))
        }
    }
  }
}
