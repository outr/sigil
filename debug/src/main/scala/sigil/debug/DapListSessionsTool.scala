package sigil.debug

import fabric.rw.*
import rapid.{Stream, Task}
import sigil.TurnContext
import sigil.event.Event
import sigil.tool.{ToolExample, ToolInput, ToolName, TypedTool}

case class DapListSessionsInput() extends ToolInput derives RW

/**
 * List every active debug session — id, language, current state.
 * Useful when the agent is juggling multiple debug sessions and
 * needs a roster.
 */
final class DapListSessionsTool(val manager: DapManager) extends TypedTool[DapListSessionsInput](
  name = ToolName("dap_list_sessions"),
  description = "List every active debug session in this Sigil instance.",
  examples = List(
    ToolExample(
      "list active sessions",
      DapListSessionsInput()
    )
  )
) with DapToolSupport {
  override protected def executeTyped(input: DapListSessionsInput, context: TurnContext): Stream[Event] = {
    val task = Task {
      val sessions = manager.listSessions()
      if (sessions.isEmpty) reply(context, "No active debug sessions.", isError = false)
      else {
        val rendered = sessions.map { case (id, s) =>
          val state =
            if (s.client.terminated.get()) "terminated"
            else if (s.client.lastStopped.get().isDefined) "stopped"
            else if (s.client.initializedFlag.get()) "running"
            else "starting"
          s"  [$id] language=${s.config.languageId} state=$state"
        }.mkString("\n")
        reply(context, rendered, isError = false)
      }
    }
    Stream.force(task.map(Stream.emit))
  }
}
