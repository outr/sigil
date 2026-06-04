package sigil.workflow.tool

import fabric.rw.*
import lightdb.id.Id
import rapid.Task
import sigil.tool.ToolContext
import sigil.tool.{TextToolOutput, Tool, ToolExample, ToolInput, ToolName, ToolResult}
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
final class DeleteWorkflowTool extends Tool with WorkflowToolSupport {
  type Input = DeleteWorkflowInput
  type Output = TextToolOutput
  val inputRW = summon[RW[DeleteWorkflowInput]]
  val outputRW = summon[RW[TextToolOutput]]
  val name = ToolName("delete_workflow")
  val description =
    """Delete a workflow template by id.
      |
      |`workflowId` is the template's id. Returns whether the deletion happened.
      |Active runs continue; future runs will fail to schedule.""".stripMargin
  override val examples = List(ToolExample("delete by id", DeleteWorkflowInput(workflowId = "wf-abc")))
  override val keywords = Set("workflow", "delete", "remove")

  override def executeResult(input: DeleteWorkflowInput, ctx: ToolContext): Task[ToolResult[TextToolOutput]] = withHostResult(ctx) { host =>
    val id = Id[WorkflowTemplate](input.workflowId)
    host.withDB(_.workflowTemplates.transaction(_.get(id))).flatMap {
      case None => Task.pure(s"Workflow '${input.workflowId}' not found.")
      case Some(template) =>
        authorizeAccess(host, template, ctx.chain).flatMap {
          case Left(reason) => Task.pure(s"Workflow '${input.workflowId}' not found.")
          case Right(_) =>
            host.withDB(_.workflowTemplates.transaction(_.delete(id))).map(_ =>
              s"Workflow '${template.name}' deleted (id=${input.workflowId}).")
        }
    }
  }
}
