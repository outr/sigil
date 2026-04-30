package sigil.metals

import fabric.rw.*
import rapid.{Stream, Task}
import sigil.TurnContext
import sigil.event.Event
import sigil.tool.{ToolInput, ToolName, TypedTool}

case class MetalsStatusInput() extends ToolInput derives RW

/**
 * List every workspace currently backed by a live Metals
 * subprocess, with its endpoint URL, alive status, and
 * idle-time-since-last-use. Read-only; no lifecycle effect.
 *
 * Useful before `start_metals` / `stop_metals` to confirm what's
 * actually running, and for surfaces that want to render a Metals
 * chip per workspace.
 */
final class MetalsStatusTool extends TypedTool[MetalsStatusInput](
  name = ToolName("metals_status"),
  description =
    """List every workspace currently backed by a Metals subprocess. Reports the workspace path,
      |MCP endpoint URL, alive flag, and milliseconds since the last touch (so you can see which
      |sessions are about to be reaped by the idle sweeper).""".stripMargin
) {
  import MetalsToolSupport.*

  override protected def executeTyped(input: MetalsStatusInput, context: TurnContext): Stream[Event] = {
    val sigil = context.sigil
    Stream.force(manager(sigil) match {
      case None =>
        Task.pure(Stream.emit[Event](reply(
          context,
          "metals_status: this Sigil instance doesn't include sigil-metals — mix in MetalsSigil.",
          isError = true
        )))
      case Some(mm) =>
        mm.status.map { entries =>
          val text =
            if (entries.isEmpty) "No Metals subprocesses running."
            else entries.map(render).mkString("\n")
          Stream.emit[Event](reply(context, text))
        }
    })
  }

  private def render(s: MetalsManager.WorkspaceStatus): String = {
    val now    = System.currentTimeMillis()
    val idleMs = now - s.lastUsedMs
    val ep     = s.endpoint.getOrElse("(starting…)")
    val alive  = if (s.alive) "alive" else "DEAD"
    s"- ${s.workspaceKey}: ${s.workspace} → $ep [$alive, idle ${idleMs}ms]"
  }
}
