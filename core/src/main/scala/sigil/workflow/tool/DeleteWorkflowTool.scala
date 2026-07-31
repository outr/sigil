package sigil.workflow.tool

import fabric.rw.*
import lightdb.id.Id
import rapid.Task
import sigil.tool.ToolContext
import sigil.tool.{DiscoverySpec, Effect, MutationTargeting, Resolution, TextToolOutput, Tool, ToolExample, ToolIO, ToolInput, ToolName, ToolProfile, ToolResult, ToolSpec}
import sigil.workflow.WorkflowTemplate

case class DeleteWorkflowInput(workflowId: String) extends ToolInput derives RW

/**
 * Delete a workflow template by id. Subject to `accessibleSpaces`
 * authz. A delete that can't land — the template doesn't exist, or the
 * caller's chain isn't authorized for its space — is a recoverable
 * failure (nothing was removed), not a silent success.
 *
 * Active runs of the deleted template continue to completion;
 * future runs (cron / trigger fires) won't find the template and
 * will fail to schedule. Apps that want strict cancellation cancel
 * runs first via `cancel_workflow`.
 */
final class DeleteWorkflowTool extends Tool with WorkflowToolSupport {
  type Input  = DeleteWorkflowInput
  type Output = TextToolOutput
  val io: ToolIO[DeleteWorkflowInput, TextToolOutput] = ToolIO.derived[DeleteWorkflowInput, TextToolOutput].withExamples(
ToolExample("delete by id", DeleteWorkflowInput(workflowId = "wf-abc"))
  )
  override val name = ToolName("delete_workflow")
  override val description =
    """Delete a workflow template by id.
      |
      |`workflowId` is the template's id. Returns whether the deletion happened.
      |Active runs continue; future runs will fail to schedule.""".stripMargin
  val spec: ToolSpec = ToolSpec(
    name = name,
    description = description,
    profile = ToolProfile(effect = Effect.Mutating(MutationTargeting.none)),
    discovery = DiscoverySpec(keywords = Set("workflow", "delete", "remove"))
  )

  protected def resolve: Resolution[Input, Output] = Resolution.Explicit(executeResult)

  private def executeResult(input: DeleteWorkflowInput, ctx: ToolContext): Task[ToolResult[TextToolOutput]] = withHostTyped(ctx) { host =>
    val id = Id[WorkflowTemplate](input.workflowId)
    host.withDB(_.workflowTemplates.transaction(_.get(id))).flatMap {
      case None => Task.pure(ToolResult.failure(s"Workflow '${input.workflowId}' not found."))
      case Some(template) =>
        authorizeAccess(host, template, ctx.chain).flatMap {
          case Left(_) => Task.pure(ToolResult.failure(s"Workflow '${input.workflowId}' not found."))
          case Right(_) =>
            host.withDB(_.workflowTemplates.transaction(_.delete(id))).map(_ =>
              ToolResult.success(TextToolOutput(s"Workflow '${template.name}' deleted (id=${input.workflowId})."))
            )
        }
    }
  }
}
