package sigil.workflow.tool

import fabric.rw.*
import lightdb.id.Id
import lightdb.time.Timestamp
import rapid.{Stream, Task}
import sigil.TurnContext
import sigil.event.Event
import sigil.tool.{ToolExample, ToolInput, ToolName, TypedTool}
import sigil.workflow.{WorkflowStepInput, WorkflowTemplate, WorkflowTrigger}

case class UpdateWorkflowInput(workflowId: String,
                               name: Option[String] = None,
                               description: Option[String] = None,
                               steps: Option[List[WorkflowStepInput]] = None,
                               triggers: Option[List[WorkflowTrigger]] = None,
                               variableDefs: Option[List[strider.WorkflowVariable]] = None,
                               tags: Option[List[String]] = None,
                               enabled: Option[Boolean] = None) extends ToolInput derives RW

/**
 * Update a workflow template in place. Every field is optional —
 * only the supplied values overwrite. Subject to
 * `accessibleSpaces` authz.
 *
 * In-flight runs are unaffected; the update lands for the next
 * scheduling.
 */
final class UpdateWorkflowTool extends TypedTool[UpdateWorkflowInput](
  name = ToolName("update_workflow"),
  description =
    """Update a workflow template's fields. Only set fields are overwritten.
      |
      |Useful for incremental editing — e.g. add a step without resending the full step list.
      |For step-list edits, fetch the current template first via `get_workflow`,
      |modify, then pass the full updated list here.""".stripMargin,
  examples = List(
    ToolExample(
      "disable a workflow",
      UpdateWorkflowInput(workflowId = "wf-abc", enabled = Some(false))
    )
  ),
  keywords = Set("workflow", "update", "edit", "modify")
) with WorkflowToolSupport {
  override protected def executeTyped(input: UpdateWorkflowInput, ctx: TurnContext): Stream[Event] = {
    workflowHost(ctx) match {
      case Left(err) => reply(ctx, err, isError = true)
      case Right(host) =>
        val id = Id[WorkflowTemplate](input.workflowId)
        val task = host.withDB(_.workflowTemplates.transaction(_.get(id))).flatMap {
          case None => Task.pure(s"Workflow '${input.workflowId}' not found.")
          case Some(prior) =>
            authorizeAccess(host, prior, ctx.chain).flatMap {
              case Left(_) => Task.pure(s"Workflow '${input.workflowId}' not found.")
              case Right(_) =>
                val updated = prior.copy(
                  name         = input.name.getOrElse(prior.name),
                  description  = input.description.orElse(prior.description),
                  steps        = input.steps.getOrElse(prior.steps),
                  triggers     = input.triggers.getOrElse(prior.triggers),
                  variableDefs = input.variableDefs.getOrElse(prior.variableDefs),
                  tags         = input.tags.map(_.toSet).getOrElse(prior.tags),
                  enabled      = input.enabled.getOrElse(prior.enabled),
                  modified     = Timestamp()
                )
                host.withDB(_.workflowTemplates.transaction(_.upsert(updated))).map(_ =>
                  s"Workflow '${updated.name}' updated."
                )
            }
        }
        Stream.force(task.map(text => reply(ctx, text)))
    }
  }
}
