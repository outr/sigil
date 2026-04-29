package sigil.workflow.tool

import fabric.rw.*
import lightdb.id.Id
import rapid.{Stream, Task}
import sigil.TurnContext
import sigil.event.Event
import sigil.tool.{ToolExample, ToolInput, ToolName, TypedTool}
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
final class CancelWorkflowTool extends TypedTool[CancelWorkflowInput](
  name = ToolName("cancel_workflow"),
  description =
    """Cancel a running or scheduled workflow run.
      |
      |`runId` is the run id (from `run_workflow` or `list_runs`). The run's current
      |step finishes if mid-execution, then no further steps run. Idempotent — cancelling
      |a finished run is a no-op.""".stripMargin,
  examples = List(ToolExample("cancel by run id", CancelWorkflowInput(runId = "run-abc"))),
  keywords = Set("workflow", "cancel", "stop", "abort")
) with WorkflowToolSupport {
  override protected def executeTyped(input: CancelWorkflowInput, ctx: TurnContext): Stream[Event] = {
    workflowHost(ctx) match {
      case Left(err) => reply(ctx, err, isError = true)
      case Right(host) =>
        val workflowId = Id[Workflow](input.runId)
        val task = host.workflowDb.workflows.transaction(_.get(workflowId)).flatMap {
          case None => Task.pure(s"Workflow run '${input.runId}' not found.")
          case Some(wf) =>
            authorizeRun(host, wf, ctx.chain).flatMap {
              case Left(_) => Task.pure(s"Workflow run '${input.runId}' not found.")
              case Right(_) =>
                host.workflowManager.cancel(workflowId)
                  .map(_ => s"Workflow run '${input.runId}' cancelled.")
                  .handleError(e => Task.pure(s"Cancel failed: ${e.getMessage}"))
            }
        }
        Stream.force(task.map(text => reply(ctx, text)))
    }
  }
}
