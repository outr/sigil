package sigil.workflow.tool

import fabric.rw.*
import lightdb.id.Id
import rapid.Task
import sigil.tool.ToolContext
import sigil.tool.{TextToolOutput, Tool, ToolExample, ToolInput, ToolName, ToolResult}
import strider.Workflow

case class CancelWorkflowInput(runId: String) extends ToolInput derives RW

/**
 * Cancel a running or scheduled workflow run by id. Subject to
 * `accessibleSpaces` authz on the run's `space` field.
 *
 * Cancelling a finished or already-cancelled run is a no-op with a
 * clear message — idempotent semantics match Strider's
 * underlying `cancel` API.
 */
final class CancelWorkflowTool extends Tool with WorkflowToolSupport {
  type Input  = CancelWorkflowInput
  type Output = TextToolOutput
  val inputRW  = summon[RW[CancelWorkflowInput]]
  val outputRW = summon[RW[TextToolOutput]]
  val name = ToolName("cancel_workflow")
  val description =
    """Cancel a running or scheduled workflow run.
      |
      |`runId` is the run id. The run's current step finishes if mid-execution, then
      |no further steps run. Idempotent — cancelling a finished run is a no-op.""".stripMargin
  override val examples = List(ToolExample("cancel by run id", CancelWorkflowInput(runId = "run-abc")))
  override val keywords = Set("workflow", "cancel", "stop", "abort")

  override def executeResult(input: CancelWorkflowInput, ctx: ToolContext): Task[ToolResult[TextToolOutput]] = withHostTyped(ctx) { host =>
    val workflowId = Id[Workflow](input.runId)
    host.withDB(_.workflows.transaction(_.get(workflowId))).flatMap {
      case None => Task.pure(ToolResult.failure(s"Workflow run '${input.runId}' not found."))
      case Some(wf) =>
        authorizeRun(host, wf, ctx.chain).flatMap {
          case Left(_) => Task.pure(ToolResult.failure(s"Workflow run '${input.runId}' not found."))
          case Right(_) =>
            host.workflowManager.cancel(workflowId)
              .map(_ => ToolResult.success(TextToolOutput(s"Workflow run '${input.runId}' cancelled.")))
              .handleError(e => Task.pure(ToolResult.failure(s"Cancel failed: ${e.getMessage}")))
        }
    }
  }
}
