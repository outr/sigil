package sigil.workflow.tool

import fabric.io.JsonFormatter
import fabric.rw.*
import lightdb.id.Id
import rapid.{Stream, Task}
import sigil.TurnContext
import sigil.event.Event
import sigil.tool.{ToolExample, ToolInput, ToolName, TypedTool}
import sigil.workflow.{WorkflowTemplate, WorkflowTrigger}

case class ListTriggersInput(workflowId: String) extends ToolInput derives RW

/**
 * Show the triggers registered on a template, in declaration
 * order. Each trigger is rendered with its 0-based index (for
 * `unregister_trigger`), its `kind` discriminator, and its typed
 * field values (compact JSON of the trigger's case-class shape).
 */
final class ListTriggersTool extends TypedTool[ListTriggersInput](
  name = ToolName("list_triggers"),
  description =
    """List the triggers registered on a workflow template.
      |
      |`workflowId` is the template id. Returns each trigger's index, kind, and typed
      |field values — useful before calling `unregister_trigger` (index-based) or when
      |reviewing what events fire a workflow.""".stripMargin,
  examples = List(ToolExample("list triggers on a template", ListTriggersInput(workflowId = "wf-abc"))),
  keywords = Set("workflow", "trigger", "list")
) with WorkflowToolSupport {
  override protected def executeTyped(input: ListTriggersInput, ctx: TurnContext): Stream[Event] = {
    workflowHost(ctx) match {
      case Left(err) => reply(ctx, err, isError = true)
      case Right(host) =>
        val id = Id[WorkflowTemplate](input.workflowId)
        val task = host.withDB(_.workflowTemplates.transaction(_.get(id))).flatMap {
          case None => Task.pure(s"Workflow '${input.workflowId}' not found.")
          case Some(template) =>
            authorizeAccess(host, template, ctx.chain).map {
              case Left(_) => s"Workflow '${input.workflowId}' not found."
              case Right(_) =>
                if (template.triggers.isEmpty) s"Workflow '${template.name}' has no triggers — manual-run only."
                else template.triggers.zipWithIndex.map { case (t, idx) =>
                  val rendered = JsonFormatter.Compact(summon[RW[WorkflowTrigger]].read(t))
                  s"  [$idx] [${t.kind}] $rendered"
                }.mkString("\n")
            }
        }
        Stream.force(task.map(text => reply(ctx, text)))
    }
  }
}
