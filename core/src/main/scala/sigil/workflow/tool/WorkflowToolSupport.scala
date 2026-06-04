package sigil.workflow.tool

import rapid.Task
import sigil.Sigil
import sigil.tool.ToolContext
import sigil.tool.{TextToolOutput, ToolResult}
import sigil.workflow.{WorkflowSigil, WorkflowTemplate}

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
