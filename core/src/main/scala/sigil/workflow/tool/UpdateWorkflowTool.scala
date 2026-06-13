package sigil.workflow.tool

import fabric.rw.*
import lightdb.id.Id
import lightdb.time.Timestamp
import rapid.Task
import sigil.tool.ToolContext
import sigil.tool.{TextToolOutput, Tool, ToolExample, ToolInput, ToolName, ToolResult}
import sigil.workflow.{WorkflowStepSpec, WorkflowTemplate, WorkflowTrigger}

case class UpdateWorkflowInput(workflowId: String,
                               name: Option[String] = None,
                               description: Option[String] = None,
                               steps: Option[List[WorkflowStepSpec]] = None,
                               triggers: Option[List[WorkflowTrigger]] = None,
                               variableDefs: Option[List[strider.WorkflowVariable]] = None,
                               tags: Option[List[String]] = None,
                               enabled: Option[Boolean] = None)
  extends ToolInput derives RW

/**
 * Update a workflow template in place. Every field is optional —
 * only the supplied values overwrite. Subject to
 * `accessibleSpaces` authz. When `steps` is supplied it is the flat,
 * `kind`-tagged [[WorkflowStepSpec]] list (lowered like `create_workflow`),
 * replacing the prior step list wholesale.
 *
 * In-flight runs are unaffected; the update lands for the next
 * scheduling.
 */
final class UpdateWorkflowTool extends Tool with WorkflowToolSupport {
  type Input = UpdateWorkflowInput
  type Output = TextToolOutput
  val inputRW = summon[RW[UpdateWorkflowInput]]
  val outputRW = summon[RW[TextToolOutput]]
  val name = ToolName("update_workflow")
  val description =
    """Update a workflow template's fields. Only set fields are overwritten.
      |
      |Useful for incremental editing — e.g. add a step without resending the full step list.
      |For step-list edits, fetch the current template first, modify, then pass the full
      |updated list here.""".stripMargin
  override val examples = List(
    ToolExample(
      "disable a workflow",
      UpdateWorkflowInput(workflowId = "wf-abc", enabled = Some(false))
    )
  )
  override val keywords = Set("workflow", "update", "edit", "modify")

  override def executeResult(input: UpdateWorkflowInput, ctx: ToolContext): Task[ToolResult[TextToolOutput]] = withHostTyped(ctx) { host =>
    val knownVariables = input.variableDefs.getOrElse(Nil).map(_.name).toSet
    val loweredSteps: Either[List[String], Option[List[sigil.workflow.WorkflowStepInput]]] =
      input.steps match {
        case None => Right(None)
        case Some(specs) => WorkflowStepSpec.lower(specs, knownVariables).map(Some(_))
      }
    loweredSteps match {
      case Left(errors) => Task.pure(ToolResult.failure(WorkflowStepSpec.violationMessage(errors)))
      case Right(stepsOpt) =>
        validateStepToolArgs(host, input.steps.getOrElse(Nil)).flatMap {
          case argErrors if argErrors.nonEmpty =>
            Task.pure(ToolResult.failure("Workflow step arguments invalid:\n" + argErrors.mkString("\n")))
          case _ =>
        val id = Id[WorkflowTemplate](input.workflowId)
        host.withDB(_.workflowTemplates.transaction(_.get(id))).flatMap {
          case None => Task.pure(ToolResult.failure(s"Workflow '${input.workflowId}' not found."))
          case Some(prior) =>
            authorizeAccess(host, prior, ctx.chain).flatMap {
              case Left(_) => Task.pure(ToolResult.failure(s"Workflow '${input.workflowId}' not found."))
              case Right(_) =>
                val updated = prior.copy(
                  name = input.name.getOrElse(prior.name),
                  description = input.description.orElse(prior.description),
                  steps = stepsOpt.getOrElse(prior.steps),
                  triggers = input.triggers.getOrElse(prior.triggers),
                  variableDefs = input.variableDefs.getOrElse(prior.variableDefs),
                  tags = input.tags.map(_.toSet).getOrElse(prior.tags),
                  enabled = input.enabled.getOrElse(prior.enabled),
                  modified = Timestamp()
                )
                host.withDB(_.workflowTemplates.transaction(_.upsert(updated))).map(_ =>
                  ToolResult.success(TextToolOutput(s"Workflow '${updated.name}' updated.")))
            }
        }
        }
    }
  }
}
