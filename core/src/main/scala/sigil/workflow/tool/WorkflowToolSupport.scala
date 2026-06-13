package sigil.workflow.tool

import fabric.Obj
import fabric.define.DefType
import rapid.Task
import sigil.Sigil
import sigil.tool.{TextToolOutput, ToolContext, ToolName, ToolResult}
import sigil.workflow.{WorkflowSigil, WorkflowStepSpec, WorkflowTemplate}

/**
 * Shared plumbing for the agent-facing workflow management tools.
 * Resolves the host [[WorkflowSigil]] from the [[TurnContext]] and
 * provides the `accessibleSpaces` authz checks.
 */
trait WorkflowToolSupport {

  /**
   * Cast the turn's host Sigil to its WorkflowSigil mixin. Tools
   * registered on a non-workflow Sigil produce a clear error.
   */
  protected def workflowHost(ctx: ToolContext): Either[String, WorkflowSigil] =
    ctx.sigil match {
      case ws: WorkflowSigil => Right(ws)
      case _ => Left("Workflow tools require the host Sigil to mix in WorkflowSigil.")
    }

  /**
   * Resolve the host [[WorkflowSigil]] and run `body` against it,
   * yielding a text [[ToolResult]]. When the host isn't a
   * `WorkflowSigil` the resolution is a [[ToolResult.Failure]].
   * Absorbs the host-unwrap boilerplate shared by every workflow
   * management tool.
   */
  protected def withHostResult(ctx: ToolContext)(body: WorkflowSigil => Task[String]): Task[ToolResult[TextToolOutput]] =
    workflowHost(ctx) match {
      case Left(err) => Task.pure(ToolResult.failure(err))
      case Right(host) => body(host).map(text => ToolResult.success(TextToolOutput(text)))
    }

  /**
   * Like [[withHostResult]], but the body produces the full [[ToolResult]]
   * itself — for tools that distinguish logical failure (e.g. invalid step
   * specs) from success. The host-unwrap failure is handled identically.
   */
  protected def withHostTyped(ctx: ToolContext)(body: WorkflowSigil => Task[ToolResult[TextToolOutput]]): Task[ToolResult[TextToolOutput]] =
    workflowHost(ctx) match {
      case Left(err) => Task.pure(ToolResult.failure(err))
      case Right(host) => body(host)
    }

  /**
   * Authz check: confirm the caller's chain has access to the
   * given template's space. Returns Right when allowed; Left
   * with an explanatory message when denied.
   */
  protected def authorizeAccess(host: Sigil,
                                template: WorkflowTemplate,
                                chain: List[sigil.participant.ParticipantId]): Task[Either[String, WorkflowTemplate]] =
    if (template.space == sigil.GlobalSpace) Task.pure(Right(template))
    else host.accessibleSpaces(chain).map { allowed =>
      if (allowed.contains(template.space)) Right(template)
      else Left(s"Workflow '${template.name}' lives in space ${template.space.value} — caller's chain isn't authorized for that space.")
    }

  /**
   * Authz check for an in-flight workflow run. The run carries its
   * space as a string (Strider's persistence side); compare against
   * the chain's accessible spaces by their string projections.
   * Runs without a space tag (cron-fired admin flows) bypass the
   * scope check; `GlobalSpace`-tagged runs always allow.
   */
  /**
   * Sigil #378 — validate each Job step's tool `arguments` against the
   * referenced tool's input schema AT AUTHORING time, so a wrong field name
   * (the `path` vs `filePath` trap) fails fast with a fixable message instead
   * of crashing the whole run when the Loop reaches that step.
   *
   * Field-NAME validation only (unknown argument keys): it catches the common
   * trap without false-positiving on unresolved `{{var}}` placeholders (still
   * strings at authoring) or on fields that carry defaults. Unknown tools are
   * left to the run-time / discovery path. Returns one message per offending
   * step; empty when every step's args are field-shaped correctly.
   */
  protected def validateStepToolArgs(host: WorkflowSigil, steps: List[WorkflowStepSpec]): Task[List[String]] = {
    val toolSteps = steps.filter(_.tool.exists(_.trim.nonEmpty))
    toolSteps.foldLeft(Task.pure(List.empty[String])) { (accTask, step) =>
      accTask.flatMap { acc =>
        val toolName = step.tool.get.trim
        host.findTools.byName(ToolName(toolName)).map {
          case None => acc
          case Some(tool) =>
            tool.inputDefinition.defType match {
              case DefType.Obj(fields) =>
                val provided = step.arguments match {
                  case Some(Obj(m)) => m.keySet
                  case _            => Set.empty[String]
                }
                val unknown = (provided -- fields.keySet).toList.sorted
                if (unknown.isEmpty) acc
                else acc :+ s"Step '${step.id}': tool '$toolName' got unknown argument(s): ${unknown.mkString(", ")}. " +
                  s"Valid fields: ${fields.keys.toList.sorted.mkString(", ")}."
              case _ => acc
            }
        }
      }
    }
  }

  protected def authorizeRun(host: Sigil,
                             workflow: strider.Workflow,
                             chain: List[sigil.participant.ParticipantId]): Task[Either[String, strider.Workflow]] =
    workflow.space match {
      case None | Some("") => Task.pure(Right(workflow))
      case Some(spaceValue) if spaceValue == sigil.GlobalSpace.value => Task.pure(Right(workflow))
      case Some(spaceValue) =>
        host.accessibleSpaces(chain).map { allowed =>
          if (allowed.exists(_.value == spaceValue)) Right(workflow)
          else Left(s"Workflow run lives in space $spaceValue — caller's chain isn't authorized.")
        }
    }
}
