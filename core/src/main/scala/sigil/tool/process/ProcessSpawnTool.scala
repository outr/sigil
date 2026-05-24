package sigil.tool.process

import fabric.rw.*
import rapid.Task
import sigil.tool.ToolContext
import sigil.tool.fs.WorkspacePathResolver
import sigil.tool.model.{ProcessSpawnInput, ProcessSpawnOutput}
import sigil.tool.{Tool, ToolExample, ToolName}

/**
 * Spawn a subprocess and return a handle for later
 * [[ProcessOutputTool]] / [[ProcessSignalTool]] calls. The call
 * returns IMMEDIATELY — this is the long-running counterpart to
 * `bash`, intended for `tsc --watch`, dev servers, `tail -f`, and
 * other commands whose output IS the value.
 */
final class ProcessSpawnTool(registry: ProcessRegistry) extends Tool {
  type Input  = ProcessSpawnInput
  type Output = ProcessSpawnOutput
  val inputRW  = summon[RW[ProcessSpawnInput]]
  val outputRW = summon[RW[ProcessSpawnOutput]]

  val name = ToolName("process_spawn")
  val description =
    """Fork a subprocess and detach — call returns immediately with the handle, pid, and start time.
      |Read accumulated stdout/stderr or signal the child through the matching process
      |output / signal tools paired by `handle`. Optional `workingDir` overrides the
      |conversation workspace; `env` extra env vars; `stdin` is piped to the child once
      |(the child sees EOF).""".stripMargin
  override val examples = List(
    ToolExample("Start tsc --watch",   ProcessSpawnInput(command = "tsc --watch --noEmit")),
    ToolExample("Start a dev server",   ProcessSpawnInput(command = "npm run dev")),
    ToolExample("Tail a log",           ProcessSpawnInput(command = "tail -F app.log"))
  )
  override val keywords = Set("process", "spawn", "background", "watch", "tail", "stream", "subprocess")

  override def executeOutput(input: ProcessSpawnInput, ctx: ToolContext): Task[ProcessSpawnOutput] =
    WorkspacePathResolver.resolveOptional(ctx, input.workingDir).flatMap { dir =>
      registry.spawn(
        command        = input.command,
        workingDir     = dir,
        env            = input.env.getOrElse(Map.empty),
        stdin          = input.stdin,
        conversationId = ctx.conversation.id
      ).map { handle =>
        ProcessSpawnOutput(handle = handle.id, pid = handle.pid, startedAt = handle.startedAt)
      }
    }
}
