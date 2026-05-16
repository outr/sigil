package sigil.tool.process

import fabric.{arr, num, obj, str}
import rapid.Stream
import sigil.TurnContext
import sigil.event.Event
import sigil.tool.fs.FsToolEmit
import sigil.tool.model.ProcessListInput
import sigil.tool.{ToolExample, ToolName, TypedTool}

/**
 * List registered subprocesses. `scope = "current"` (default)
 * restricts to the spawning conversation; `scope = "all"` returns
 * every entry.
 */
final class ProcessListTool(registry: ProcessRegistry)
  extends TypedTool[ProcessListInput](
    name = ToolName("process_list"),
    description =
      """List subprocesses registered with the framework. `scope = "current"` (default) restricts
        |to processes spawned by this conversation; `scope = "all"` returns every entry. Each
        |handle includes `{id, pid, startedAt, command}`.""".stripMargin,
    examples = List(
      ToolExample("Processes spawned by this conversation", ProcessListInput()),
      ToolExample("Every registered process",                ProcessListInput(scope = "all"))
    ),
    keywords = Set("process", "list", "running", "background")
  ) {
  override def paginate: Boolean = false

  override protected def executeTyped(input: ProcessListInput, ctx: TurnContext): Stream[Event] = Stream.force(
    registry.list(filterByConversation = input.scope match {
      case "all" => None
      case _     => Some(ctx.conversation.id)
    }).map { handles =>
      val arrJson = arr(handles.map(h => obj(
        "id"        -> str(h.id),
        "pid"       -> num(h.pid),
        "startedAt" -> num(h.startedAt.value),
        "command"   -> str(h.command)
      ))*)
      val payload = obj("processes" -> arrJson)
      Stream.emit[Event](FsToolEmit(payload, ctx))
    }
  )
}
