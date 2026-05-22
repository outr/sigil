package sigil.tool.memory

import fabric.rw.*
import rapid.Task
import sigil.TurnContext
import sigil.conversation.ContextMemory
import sigil.tool.{TextToolOutput, Tool, ToolExample, ToolName, ToolResult}

/**
 * Opt-in tool: return the full version history of a keyed memory,
 * chronologically (oldest → newest).
 */
case object MemoryHistoryTool extends Tool {
  type Input  = MemoryHistoryInput
  type Output = TextToolOutput
  val inputRW: RW[MemoryHistoryInput] = summon[RW[MemoryHistoryInput]]
  val outputRW: RW[TextToolOutput]    = summon[RW[TextToolOutput]]

  val name: ToolName = ToolName("memory_history")
  val description: String =
    """Show the version history of a keyed memory — every past value for this key,
      |with valid-from / valid-until timestamps. Use when you need to understand how
      |a fact has changed over time (e.g. "what did the user prefer before they changed their mind?").
      |
      |`key`     — the memory key whose history you want.
      |`spaceId` — optional; omit to use the caller's default scope.""".stripMargin
  override val examples: List[ToolExample] = List(
    ToolExample("History of the user's theme preference", MemoryHistoryInput(key = "user.ui.theme"))
  )
  override val keywords: Set[String] = Set("memory", "history", "version")

  override def executeResult(input: MemoryHistoryInput, context: TurnContext): Task[ToolResult[TextToolOutput]] =
    resolveSpace(input, context).flatMap {
      case None =>
        Task.pure(s"[memory_history] no memory space available for key ${input.key}.")
      case Some(space) =>
        context.sigil.memoryHistory(input.key, space).map(versions => render(input.key, versions))
    }.map(text => ToolResult.Success(TextToolOutput(text)))

  private def resolveSpace(input: MemoryHistoryInput, context: TurnContext) =
    input.spaceId match {
      case Some(s) => Task.pure(Some(s))
      case None    => context.sigil.defaultMemorySpace(context.conversation.id)
    }

  private def render(key: String, versions: List[ContextMemory]): String =
    if (versions.isEmpty) s"[memory_history] no versions for key $key"
    else {
      val sb = new StringBuilder(s"[memory_history] ${versions.size} version(s) of $key:\n")
      versions.foreach { v =>
        val current = v.validUntil.isEmpty
        val marker = if (current) "(current)" else "(archived)"
        val from = v.validFrom.map(_.value.toString).getOrElse("?")
        val until = v.validUntil.map(_.value.toString).getOrElse("—")
        sb.append(s"  $marker validFrom=$from validUntil=$until\n")
        sb.append(s"    ${v.fact}\n")
        v.justification.foreach(j => sb.append(s"    why: $j\n"))
      }
      sb.toString
    }
}
