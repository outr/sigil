package sigil.debug

import fabric.rw.*
import rapid.Task
import sigil.TurnContext
import sigil.tool.{TextToolOutput, Tool, ToolExample, ToolInput, ToolName, ToolResult}

case class DapListSessionsInput() extends ToolInput derives RW

/**
 * List every active debug session — id, language, current state.
 * Useful when the agent is juggling multiple debug sessions and
 * needs a roster.
 */
final class DapListSessionsTool(val manager: DapManager) extends Tool with DapToolSupport {
  type Input = DapListSessionsInput
  type Output = TextToolOutput
  val inputRW = summon[RW[DapListSessionsInput]]
  val outputRW = summon[RW[TextToolOutput]]
  val name = ToolName("dap_list_sessions")
  val description = "List every active debug session in this Sigil instance."
  override val examples = List(
    ToolExample(
      "list active sessions",
      DapListSessionsInput()
    )
  )

  override def executeResult(input: DapListSessionsInput, context: TurnContext): Task[ToolResult[TextToolOutput]] =
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
