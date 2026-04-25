package sigil.tool.memory

import rapid.{Stream, Task}
import sigil.TurnContext
import sigil.conversation.ContextMemory
import sigil.event.{Event, Message}
import sigil.tool.model.ResponseContent
import sigil.tool.{ToolExample, ToolName, TypedTool}

/**
 * Opt-in tool: return the full version history of a keyed memory,
 * chronologically (oldest → newest).
 */
case object MemoryHistoryTool extends TypedTool[MemoryHistoryInput](
  name = ToolName("memory_history"),
  description =
    """Show the version history of a keyed memory — every past value for this key,
      |with valid-from / valid-until timestamps. Use when you need to understand how
      |a fact has changed over time (e.g. "what did the user prefer before they changed their mind?").
      |
      |`key`     — the memory key whose history you want.
      |`spaceId` — optional; omit to use the caller's default scope.""".stripMargin,
  examples = List(
    ToolExample("History of the user's theme preference", MemoryHistoryInput(key = "user.ui.theme"))
  ),
  keywords = Set("memory", "history", "version")
) {
  override protected def executeTyped(input: MemoryHistoryInput, context: TurnContext): Stream[Event] =
    Stream.force {
      resolveSpace(input, context).flatMap {
        case None =>
          Task.pure(toMsg(context,
            s"[memory_history] no memory space available for key ${input.key}."))
        case Some(space) =>
          context.sigil.memoryHistory(input.key, space).map { versions =>
            toMsg(context, render(input.key, versions))
          }
      }.map(msg => Stream.emits(List[Event](msg)))
    }

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
      }
      sb.toString
    }

  private def toMsg(context: TurnContext, body: String): Message =
    Message(
      participantId = context.caller,
      conversationId = context.conversation.id,
      topicId = context.conversation.currentTopicId,
      content = Vector(ResponseContent.Text(body))
    )
}
