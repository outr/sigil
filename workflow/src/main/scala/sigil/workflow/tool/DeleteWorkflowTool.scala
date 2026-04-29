package sigil.workflow.tool

import fabric.rw.*
import lightdb.id.Id
import rapid.{Stream, Task}
import sigil.TurnContext
import sigil.event.Event
import sigil.tool.{ToolExample, ToolInput, ToolName, TypedTool}
import sigil.workflow.WorkflowTemplate

case class DeleteWorkflowInput(workflowId: String) extends ToolInput derives RW

/**
 * Delete a workflow template by id. Subject to `accessibleSpaces`
 * authz. Idempotent — deleting a non-existent template is a no-op
 * with a clear message.
 *
 * Active runs of the deleted template continue to completion;
 * future runs (cron / trigger fires) won't find the template and
 * will fail to schedule. Apps that want strict cancellation cancel
 * runs first via `cancel_workflow`.
 */
final class DeleteWorkflowTool extends TypedTool[DeleteWorkflowInput](
  name = ToolName("delete_workflow"),
  description =
    """Delete a workflow template by id.
      |
      |`workflowId` is the template's id. Returns whether the deletion happened.
      |Active runs continue; future runs will fail to schedule.""".stripMargin,
  examples = List(ToolExample("delete by id", DeleteWorkflowInput(workflowId = "wf-abc"))),
  keywords = Set("workflow", "delete", "remove")
) with WorkflowToolSupport {
  override protected def executeTyped(input: DeleteWorkflowInput, ctx: TurnContext): Stream[Event] = {
    workflowHost(ctx) match {
      case Left(err) => reply(ctx, err, isError = true)
      case Right(host) =>
        val id = Id[WorkflowTemplate](input.workflowId)
        val task = host.withDB(_.workflowTemplates.transaction(_.get(id))).flatMap {
          case None => Task.pure(s"Workflow '${input.workflowId}' not found.")
          case Some(template) =>
            authorizeAccess(host, template, ctx.chain).flatMap {
              case Left(reason) => Task.pure(s"Workflow '${input.workflowId}' not found.")
              case Right(_) =>
                host.withDB(_.workflowTemplates.transaction(_.delete(id))).map(_ =>
                  s"Workflow '${template.name}' deleted (id=${input.workflowId})."
                )
            }
        }
        Stream.force(task.map(text => reply(ctx, text)))
    }
  }
}
