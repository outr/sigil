package sigil.tool.process

import fabric.rw.*
import rapid.Task
import sigil.tool.ToolContext
import sigil.tool.model.{ProcessListEntry, ProcessListInput, ProcessListOutput, ProcessListScope}
import sigil.tool.{Tool, ToolExample, ToolName}

/**
 * List registered subprocesses. `scope = "current"` (default)
 * restricts to the spawning conversation; `scope = "all"` returns
 * every entry.
 */
final class ProcessListTool(registry: ProcessRegistry) extends Tool {
  type Input = ProcessListInput
  type Output = ProcessListOutput
  val inputRW = summon[RW[ProcessListInput]]
  val outputRW = summon[RW[ProcessListOutput]]

  val name = ToolName("process_list")
  val description =
    """List subprocesses registered with the framework. `scope = "current"` (default) restricts
      |to processes spawned by this conversation; `scope = "all"` returns every entry. Each
      |handle includes its id, pid, start time, and command.""".stripMargin
  override val examples = List(
    ToolExample("Processes spawned by this conversation", ProcessListInput()),
    ToolExample("Every registered process", ProcessListInput(scope = ProcessListScope.All))
  )
  override val keywords = Set("process", "list", "running", "background")

  override def executeOutput(input: ProcessListInput, ctx: ToolContext): Task[ProcessListOutput] =
    registry.list(filterByConversation = input.scope match {
      case ProcessListScope.All => None
      case ProcessListScope.Current => Some(ctx.conversation.id)
    }).map { handles =>
      ProcessListOutput(handles.map { h =>
        ProcessListEntry(id = h.id, pid = h.pid, startedAt = h.startedAt, command = h.command)
      })
    }
}
