package sigil.tool.process

import fabric.{num, obj, str}
import rapid.Stream
import sigil.TurnContext
import sigil.event.Event
import sigil.tool.fs.{FsToolEmit, WorkspacePathResolver}
import sigil.tool.model.ProcessSpawnInput
import sigil.tool.{ToolExample, ToolName, TypedTool}

/**
 * Spawn a subprocess and return a handle for later
 * [[ProcessOutputTool]] / [[ProcessSignalTool]] calls. The call
 * returns IMMEDIATELY — this is the long-running counterpart to
 * `bash`, intended for `tsc --watch`, dev servers, `tail -f`, and
 * other commands whose output IS the value.
 */
final class ProcessSpawnTool(registry: ProcessRegistry)
  extends TypedTool[ProcessSpawnInput](
    name = ToolName("process_spawn"),
    description =
      """Fork a subprocess and detach — call returns immediately with `{handle, pid, startedAt}`.
        |Read accumulated stdout/stderr or signal the child through the matching process
        |output / signal tools paired by `handle`. Optional `workingDir` overrides the
        |conversation workspace; `env` extra env vars; `stdin` is piped to the child once
        |(the child sees EOF).""".stripMargin,
    examples = List(
      ToolExample("Start tsc --watch", ProcessSpawnInput(command = "tsc --watch --noEmit")),
      ToolExample("Start a dev server", ProcessSpawnInput(command = "npm run dev")),
      ToolExample("Tail a log", ProcessSpawnInput(command = "tail -F app.log"))
    ),
    keywords = Set("process", "spawn", "background", "watch", "tail", "stream", "subprocess")
  ) {
  override def paginate: Boolean = false

  override protected def executeTyped(input: ProcessSpawnInput, ctx: TurnContext): Stream[Event] = Stream.force(
    WorkspacePathResolver.resolveOptional(ctx, input.workingDir).flatMap { dir =>
      registry.spawn(
        command = input.command,
        workingDir = dir,
        env = input.env.getOrElse(Map.empty),
        stdin = input.stdin,
        conversationId = ctx.conversation.id
      ).map { handle =>
        val payload = obj(
          "handle" -> str(handle.id),
          "pid" -> num(handle.pid),
          "startedAt" -> num(handle.startedAt.value)
        )
        Stream.emit[Event](FsToolEmit(payload, ctx))
      }
    }
  )
}
