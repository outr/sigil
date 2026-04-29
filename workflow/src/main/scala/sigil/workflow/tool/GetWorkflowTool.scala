package sigil.workflow.tool

import fabric.io.JsonFormatter
import fabric.rw.*
import lightdb.id.Id
import rapid.{Stream, Task}
import sigil.TurnContext
import sigil.event.Event
import sigil.tool.{ToolExample, ToolInput, ToolName, TypedTool}
import sigil.workflow.WorkflowTemplate

case class GetWorkflowInput(workflowId: String) extends ToolInput derives RW

/**
 * Fetch a workflow template by id. Subject to `accessibleSpaces`
 * authz — returns "not found" when the template exists but the
 * caller's chain isn't authorized for its space (avoids leaking
 * existence across tenant boundaries).
 */
final class GetWorkflowTool extends TypedTool[GetWorkflowInput](
  name = ToolName("get_workflow"),
  description =
    """Fetch a workflow template by id.
      |
      |`workflowId` is the template's id (from `list_workflows` or `create_workflow`).
      |Returns the full template — name, description, step list, triggers, variable defs.""".stripMargin,
  examples = List(ToolExample("fetch by id", GetWorkflowInput(workflowId = "wf-abc"))),
  keywords = Set("workflow", "get", "describe")
) with WorkflowToolSupport {
  override protected def executeTyped(input: GetWorkflowInput, ctx: TurnContext): Stream[Event] = {
    workflowHost(ctx) match {
      case Left(err) => reply(ctx, err, isError = true)
      case Right(host) =>
        val task = host.withDB(_.workflowTemplates.transaction(_.get(Id[WorkflowTemplate](input.workflowId)))).flatMap {
          case None => Task.pure(s"Workflow '${input.workflowId}' not found.")
          case Some(template) =>
            authorizeAccess(host, template, ctx.chain).map {
              case Left(reason) => s"Workflow '${input.workflowId}' not found."  // hide cross-space existence
              case Right(t)     => render(t)
            }
        }
        Stream.force(task.map(text => reply(ctx, text)))
    }
  }

  private def render(t: WorkflowTemplate): String = {
    val sb = new StringBuilder
    sb.append(s"Workflow '${t.name}' (id=${t._id.value}, enabled=${t.enabled})\n")
    sb.append(s"  description: ${t.description}\n")
    sb.append(s"  space: ${t.space.value}\n")
    sb.append(s"  steps (${t.steps.size}): ${t.steps.map(_.id).mkString(", ")}\n")
    sb.append(s"  triggers (${t.triggers.size}): ${t.triggers.map(_.kind).mkString(", ")}\n")
    if (t.variableDefs.nonEmpty)
      sb.append(s"  variables: ${t.variableDefs.map(v => v.name + (if (v.required) "*" else "")).mkString(", ")}\n")
    if (t.tags.nonEmpty) sb.append(s"  tags: ${t.tags.mkString(", ")}\n")
    sb.toString
  }
}
