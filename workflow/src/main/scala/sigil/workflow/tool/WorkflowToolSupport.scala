package sigil.workflow.tool

import lightdb.id.Id
import rapid.{Stream, Task}
import sigil.{Sigil, SpaceId, TurnContext}
import sigil.event.{Event, Message, MessageRole, MessageVisibility}
import sigil.signal.EventState
import sigil.tool.model.ResponseContent
import sigil.workflow.{WorkflowSigil, WorkflowTemplate}

/**
 * Shared plumbing for the agent-facing workflow management tools.
 * Resolves the host [[WorkflowSigil]] from the [[TurnContext]] and
 * provides the `accessibleSpaces` authz check + a `reply` helper
 * that emits the result as a `Role.Tool` Message.
 */
trait WorkflowToolSupport {
  /** Cast the turn's host Sigil to its WorkflowSigil mixin. Tools
    * registered on a non-workflow Sigil produce a clear error. */
  protected def workflowHost(ctx: TurnContext): Either[String, WorkflowSigil] =
    ctx.sigil match {
      case ws: WorkflowSigil => Right(ws)
      case _ => Left("Workflow tools require the host Sigil to mix in WorkflowSigil.")
    }

  /** Authz check: confirm the caller's chain has access to the
    * given template's space. Returns Right when allowed; Left
    * with an explanatory message when denied. */
  protected def authorizeAccess(host: Sigil, template: WorkflowTemplate, chain: List[sigil.participant.ParticipantId]): Task[Either[String, WorkflowTemplate]] =
    if (template.space == sigil.GlobalSpace) Task.pure(Right(template))
    else host.accessibleSpaces(chain).map { allowed =>
      if (allowed.contains(template.space)) Right(template)
      else Left(s"Workflow '${template.name}' lives in space ${template.space.value} — caller's chain isn't authorized for that space.")
    }

  /** Authz check for an in-flight workflow run. The run carries its
    * space as a string (Strider's persistence side); compare against
    * the chain's accessible spaces by their string projections.
    * Runs without a space tag (cron-fired admin flows) bypass the
    * scope check; `GlobalSpace`-tagged runs always allow. */
  protected def authorizeRun(host: Sigil, workflow: strider.Workflow, chain: List[sigil.participant.ParticipantId]): Task[Either[String, strider.Workflow]] =
    workflow.space match {
      case None | Some("") => Task.pure(Right(workflow))
      case Some(spaceValue) if spaceValue == sigil.GlobalSpace.value => Task.pure(Right(workflow))
      case Some(spaceValue) =>
        host.accessibleSpaces(chain).map { allowed =>
          if (allowed.exists(_.value == spaceValue)) Right(workflow)
          else Left(s"Workflow run lives in space $spaceValue — caller's chain isn't authorized.")
        }
    }

  protected def reply(ctx: TurnContext, text: String, isError: Boolean = false): Stream[Event] =
    Stream.emit[Event](Message(
      participantId = ctx.caller,
      conversationId = ctx.conversation.id,
      topicId = ctx.conversation.currentTopicId,
      content = Vector(ResponseContent.Text(text)),
      state = EventState.Complete,
      role = MessageRole.Tool,
      visibility = MessageVisibility.All
    ))
}
