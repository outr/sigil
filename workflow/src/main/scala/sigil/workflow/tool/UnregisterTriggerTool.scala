package sigil.workflow.tool

import fabric.rw.*
import lightdb.id.Id
import lightdb.time.Timestamp
import rapid.{Stream, Task}
import sigil.TurnContext
import sigil.event.Event
import sigil.tool.{ToolExample, ToolInput, ToolName, TypedTool}
import sigil.workflow.WorkflowTemplate

case class UnregisterTriggerInput(workflowId: String,
                                  index: Int) extends ToolInput derives RW

/**
 * Remove a trigger from a workflow template by its 0-based index
 * in the `triggers` list. The agent fetches the template first
 * (via `get_workflow` or `list_triggers`) to see the current order
 * + indices.
 *
 * Index-based addressing keeps the LLM's tool surface simple; the
 * triggers themselves don't carry a stable id (they're typed
 * values, not records). Apps that want id-based addressing wrap
 * triggers in a record with `Id[…]` before persisting.
 */
final class UnregisterTriggerTool extends TypedTool[UnregisterTriggerInput](
  name = ToolName("unregister_trigger"),
  description =
    """Remove a trigger from a workflow template by its 0-based index.
      |
      |`workflowId` is the template id; `index` is the position in the template's
      |`triggers` list (visible from `list_triggers`). Out-of-bounds indices are
      |a clear error.""".stripMargin,
  examples = List(
    ToolExample("remove the first trigger", UnregisterTriggerInput(workflowId = "wf-abc", index = 0))
  ),
  keywords = Set("workflow", "trigger", "remove", "unregister")
) with WorkflowToolSupport {
  override protected def executeTyped(input: UnregisterTriggerInput, ctx: TurnContext): Stream[Event] = {
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
                if (input.index < 0 || input.index >= prior.triggers.size)
                  Task.pure(s"Trigger index ${input.index} out of range (workflow has ${prior.triggers.size} trigger(s)).")
                else {
                  val removed = prior.triggers(input.index)
                  val updated = prior.copy(
                    triggers = prior.triggers.patch(input.index, Nil, 1),
                    modified = Timestamp()
                  )
                  host.withDB(_.workflowTemplates.transaction(_.upsert(updated))).map { _ =>
                    s"Trigger '${removed.kind}' (index ${input.index}) removed from workflow '${prior.name}'."
                  }
                }
            }
        }
        Stream.force(task.map(text => reply(ctx, text)))
    }
  }
}
