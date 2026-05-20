package sigil.tool.process

import rapid.Task
import sigil.TurnContext
import sigil.tool.model.{ProcessListEntry, ProcessListInput, ProcessListOutput, ProcessListScope}
import sigil.tool.{ToolExample, ToolName, TypedOutputTool}

/**
 * List registered subprocesses. `scope = "current"` (default)
 * restricts to the spawning conversation; `scope = "all"` returns
 * every entry.
 */
final class ProcessListTool(registry: ProcessRegistry)
  extends TypedOutputTool[ProcessListInput, ProcessListOutput](
    name = ToolName("process_list"),
    description =
      """List subprocesses registered with the framework. `scope = "current"` (default) restricts
        |to processes spawned by this conversation; `scope = "all"` returns every entry. Each
        |handle includes its id, pid, start time, and command.""".stripMargin,
    examples = List(
      ToolExample("Processes spawned by this conversation", ProcessListInput()),
      ToolExample("Every registered process",                ProcessListInput(scope = ProcessListScope.All))
    ),
    keywords = Set("process", "list", "running", "background")
  ) {
  override def paginate: Boolean = false

  override protected def executeTyped(input: ProcessListInput, ctx: TurnContext): Task[ProcessListOutput] =
    registry.list(filterByConversation = input.scope match {
      case ProcessListScope.All     => None
      case ProcessListScope.Current => Some(ctx.conversation.id)
    }).map { handles =>
      ProcessListOutput(handles.map { h =>
        ProcessListEntry(id = h.id, pid = h.pid, startedAt = h.startedAt, command = h.command)
      })
    }
}
