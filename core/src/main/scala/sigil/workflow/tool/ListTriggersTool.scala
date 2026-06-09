package sigil.workflow.tool

import fabric.io.JsonFormatter
import fabric.rw.*
import lightdb.id.Id
import rapid.Task
import sigil.tool.ToolContext
import sigil.tool.{TextToolOutput, Tool, ToolExample, ToolInput, ToolName, ToolResult}
import sigil.workflow.{WorkflowTemplate, WorkflowTrigger}

case class ListTriggersInput(workflowId: String) extends ToolInput derives RW

/**
 * Show the triggers registered on a template, in declaration
 * order. Each trigger is rendered with its 0-based index (for
 * `unregister_trigger`), its `kind` discriminator, and its typed
 * field values (compact JSON of the trigger's case-class shape).
 */
final class ListTriggersTool extends Tool with WorkflowToolSupport {
  type Input  = ListTriggersInput
  type Output = TextToolOutput
  val inputRW  = summon[RW[ListTriggersInput]]
  val outputRW = summon[RW[TextToolOutput]]
  val name = ToolName("list_triggers")
  val description =
    """List the triggers registered on a workflow template.
      |
      |`workflowId` is the template id. Returns each trigger's index, kind, and typed
      |field values — useful before unregistering a trigger by index, or when reviewing
      |what events fire a workflow.""".stripMargin
  override val examples = List(ToolExample("list triggers on a template", ListTriggersInput(workflowId = "wf-abc")))
  override val keywords = Set("workflow", "trigger", "list")

  override def executeResult(input: ListTriggersInput, ctx: ToolContext): Task[ToolResult[TextToolOutput]] = withHostTyped(ctx) { host =>
    val id = Id[WorkflowTemplate](input.workflowId)
    host.withDB(_.workflowTemplates.transaction(_.get(id))).flatMap {
      case None => Task.pure(ToolResult.failure(s"Workflow '${input.workflowId}' not found."))
      case Some(template) =>
        authorizeAccess(host, template, ctx.chain).map {
          case Left(_) => ToolResult.failure(s"Workflow '${input.workflowId}' not found.")
          case Right(_) =>
            val text =
              if (template.triggers.isEmpty) s"Workflow '${template.name}' has no triggers — manual-run only."
              else template.triggers.zipWithIndex.map { case (t, idx) =>
                val rendered = JsonFormatter.Compact(summon[RW[WorkflowTrigger]].read(t))
                s"  [$idx] [${t.kind}] $rendered"
              }.mkString("\n")
            ToolResult.success(TextToolOutput(text))
        }
    }
  }
}
