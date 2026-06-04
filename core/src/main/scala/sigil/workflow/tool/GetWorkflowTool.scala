package sigil.workflow.tool

import fabric.rw.*
import lightdb.id.Id
import rapid.Task
import sigil.tool.ToolContext
import sigil.tool.{Tool, ToolExample, ToolInput, ToolName, ToolResult}
import sigil.workflow.WorkflowTemplate

case class GetWorkflowInput(workflowId: String) extends ToolInput derives RW

/**
 * Fetch a workflow template by id. Subject to `accessibleSpaces`
 * authz — returns `NotFound` when the template exists but the
 * caller's chain isn't authorized for its space (avoids leaking
 * existence across tenant boundaries).
 */
final class GetWorkflowTool extends Tool with WorkflowToolSupport {
  type Input = GetWorkflowInput
  type Output = GetWorkflowOutput
  val inputRW = summon[RW[GetWorkflowInput]]
  val outputRW = summon[RW[GetWorkflowOutput]]
  val name = ToolName("get_workflow")
  val description =
    """Fetch a workflow template by id.
      |
      |`workflowId` is the template's id. Returns the full template — name, description,
      |step list, triggers, variable defs.""".stripMargin
  override val examples = List(ToolExample("fetch by id", GetWorkflowInput(workflowId = "wf-abc")))
  override val keywords = Set("workflow", "get", "describe")

  override def executeResult(input: GetWorkflowInput, ctx: ToolContext): Task[ToolResult[GetWorkflowOutput]] =
    workflowHost(ctx) match {
      case Left(err) => Task.pure(ToolResult.failure(err))
      case Right(host) =>
        host.withDB(_.workflowTemplates.transaction(_.get(Id[WorkflowTemplate](input.workflowId)))).flatMap {
          case None => Task.pure(ToolResult.success(GetWorkflowOutput.NotFound(input.workflowId)))
          case Some(template) =>
            authorizeAccess(host, template, ctx.chain).map {
              case Left(_) => ToolResult.success(GetWorkflowOutput.NotFound(input.workflowId)) // hide cross-space existence
              case Right(t) => ToolResult.success(project(t))
            }
        }
    }

  private def project(t: WorkflowTemplate): GetWorkflowOutput.Found =
    GetWorkflowOutput.Found(
      workflowId = t._id.value,
      name = t.name,
      enabled = t.enabled,
      description = t.description,
      space = t.space.value,
      stepIds = t.steps.map(_.id),
      triggerKinds = t.triggers.map(_.kind),
      variables = t.variableDefs.map(v => GetWorkflowVariable(v.name, v.required)),
      tags = t.tags.toList
    )
}
