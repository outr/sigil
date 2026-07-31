package sigil.metals

import fabric.rw.*
import rapid.Task
import sigil.tool.ToolContext
import sigil.tool.{
  DiscoverySpec,
  Effect,
  Freshness,
  Resolution,
  TextToolOutput,
  Tool,
  ToolIO,
  ToolInput,
  ToolName,
  ToolProfile,
  ToolResult,
  ToolSpec
}

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
final class MetalsStatusTool extends Tool {
  type Input = MetalsStatusInput
  type Output = TextToolOutput
  val io: ToolIO[MetalsStatusInput, TextToolOutput] = ToolIO.derived[MetalsStatusInput, TextToolOutput]

  override val name = ToolName("metals_status")
  override val description =
    """List every workspace currently backed by a Metals subprocess. Reports the workspace path,
      |MCP endpoint URL, alive flag, and milliseconds since the last touch (so you can see which
      |sessions are about to be reaped by the idle sweeper).""".stripMargin
  val spec: ToolSpec = ToolSpec(
    name = name,
    description = description,
    profile = ToolProfile(effect = Effect.ReadOnly(Freshness.Volatile)),
    discovery = DiscoverySpec(
      keywords = Set(
        "metals",
        "status",
        "health",
        "indexing",
        "ready",
        "scala",
        "compile",
        "subprocess",
        "running",
        "lsp"
      )
    )
  )

  import MetalsToolSupport.*

  protected def resolve: Resolution[Input, Output] = Resolution.Explicit(executeResult)

  private def executeResult(input: MetalsStatusInput, context: ToolContext): Task[ToolResult[TextToolOutput]] = {
    val sigil = context.sigil
    manager(sigil) match {
      case None =>
        Task.pure(ToolResult.failure(
          "metals_status: this Sigil instance doesn't include sigil-metals — mix in MetalsSigil."
        ))
      case Some(mm) =>
        mm.status.map { entries =>
          val text =
            if (entries.isEmpty) "No Metals subprocesses running."
            else entries.map(render).mkString("\n")
          ToolResult.Success(TextToolOutput(text))
        }
    }
  }

  private def render(s: MetalsManager.WorkspaceStatus): String = {
    val now = System.currentTimeMillis()
    val idleMs = now - s.lastUsedMs
    val ep = s.endpoint.getOrElse("(starting…)")
    val alive = if (s.alive) "alive" else "DEAD"
    s"- ${s.workspaceKey}: ${s.workspace} → $ep [$alive, idle ${idleMs}ms]"
  }
}
