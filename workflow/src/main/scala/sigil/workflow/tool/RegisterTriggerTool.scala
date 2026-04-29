package sigil.workflow.tool

import fabric.rw.*
import lightdb.id.Id
import lightdb.time.Timestamp
import rapid.{Stream, Task}
import sigil.TurnContext
import sigil.event.Event
import sigil.tool.{ToolExample, ToolInput, ToolName, TypedTool}
import sigil.workflow.{WorkflowTemplate, WorkflowTrigger}

case class RegisterTriggerInput(workflowId: String,
                                trigger: WorkflowTrigger) extends ToolInput derives RW

/**
 * Append a [[WorkflowTrigger]] to a persisted template's
 * `triggers` list. Subject to `accessibleSpaces` authz.
 *
 * The registered trigger is typed — fabric round-trips it through
 * the polymorphic dispatcher so apps' Slack / Email / Git triggers
 * (Sage's downstream additions) work the same as the framework's
 * baseline four.
 */
final class RegisterTriggerTool extends TypedTool[RegisterTriggerInput](
  name = ToolName("register_trigger"),
  description =
    """Add a typed WorkflowTrigger to a workflow template.
      |
      |`workflowId` is the template id. `trigger` is the typed trigger shape — pick
      |from the available subtypes (ConversationMessageTrigger, TimeTrigger, WebhookTrigger,
      |WorkflowEventTrigger, plus app-defined ones).""".stripMargin,
  examples = List(
    ToolExample(
      "fire on a daily 9am cron",
      RegisterTriggerInput(
        workflowId = "wf-abc",
        trigger = sigil.workflow.trigger.TimeTrigger(cron = Some("0 9 * * *"))
      )
    )
  ),
  keywords = Set("workflow", "trigger", "schedule", "register")
) with WorkflowToolSupport {
  override protected def executeTyped(input: RegisterTriggerInput, ctx: TurnContext): Stream[Event] = {
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
                  triggers = prior.triggers :+ input.trigger,
                  modified = Timestamp()
                )
                host.withDB(_.workflowTemplates.transaction(_.upsert(updated))).map { _ =>
                  s"Trigger '${input.trigger.kind}' registered on workflow '${prior.name}'."
                }
            }
        }
        Stream.force(task.map(text => reply(ctx, text)))
    }
  }
}
