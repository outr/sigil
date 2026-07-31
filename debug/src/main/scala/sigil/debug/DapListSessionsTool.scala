package sigil.debug

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
  ToolExample,
  ToolIO,
  ToolInput,
  ToolName,
  ToolProfile,
  ToolResult,
  ToolSpec
}

case class DapListSessionsInput() extends ToolInput derives RW

/**
 * List every active debug session — id, language, current state.
 * Useful when the agent is juggling multiple debug sessions and
 * needs a roster.
 */
final class DapListSessionsTool(val manager: DapManager) extends Tool with DapToolSupport {
  type Input = DapListSessionsInput
  type Output = TextToolOutput
  val io: ToolIO[DapListSessionsInput, TextToolOutput] = ToolIO.derived[DapListSessionsInput, TextToolOutput].withExamples(
    ToolExample(
      "list active sessions",
      DapListSessionsInput()
    )
  )
  override val name = ToolName("dap_list_sessions")
  override val description = "List every active debug session in this Sigil instance."
  val spec: ToolSpec = ToolSpec(
    name = name,
    description = description,
    profile = ToolProfile(effect = Effect.ReadOnly(Freshness.Volatile)),
    discovery = DiscoverySpec(keywords = Set("debug", "dap", "session", "sessions", "list", "debugger"))
  )

  protected def resolve: Resolution[Input, Output] = Resolution.Explicit(executeResult)

  private def executeResult(input: DapListSessionsInput, context: ToolContext): Task[ToolResult[TextToolOutput]] =
    Task {
      val sessions = manager.listSessions()
      val text =
        if (sessions.isEmpty) "No active debug sessions."
        else sessions.map { case (id, s) =>
          val state =
            if (s.client.terminated.get()) "terminated"
            else if (s.client.lastStopped.get().isDefined) "stopped"
            else if (s.client.initializedFlag.get()) "running"
            else "starting"
          s"  [$id] language=${s.config.languageId} state=$state"
        }.mkString("\n")
      ToolResult.success(TextToolOutput(text))
    }
}
