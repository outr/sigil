package sigil.script

import rapid.Stream
import sigil.TurnContext
import sigil.event.{Event, Message, MessageVisibility, MessageRole}
import sigil.signal.EventState
import sigil.tool.{Tool, ToolName, TypedTool}
import sigil.tool.model.ResponseContent

/**
 * List the script-backed tools visible to the caller. Visibility is
 * [[sigil.GlobalSpace]] ∪ [[sigil.Sigil.accessibleSpaces]] for the
 * current chain — the same predicate `find_capability` uses. Output
 * is a single Markdown response listing each tool's name,
 * description, and space.
 */
case object ListScriptToolsTool extends TypedTool[ListScriptToolsInput](
  name = ToolName("list_script_tools"),
  description =
    """Enumerate the script-backed tools the caller can currently see (their own scoped tools
      |plus globally available ones). Optional `nameContains` narrows the listing to tools
      |whose name contains the given substring.""".stripMargin,
  keywords = Set("list", "tools", "script", "enumerate", "available")
) {
  override protected def executeTyped(input: ListScriptToolsInput,
                                      context: TurnContext): Stream[Event] = Stream.force(
    context.sigil.accessibleSpaces(context.chain).flatMap { accessible =>
      context.sigil.withDB(_.tools.transaction(_.list)).map { allTools =>
        val needle = input.nameContains.map(_.toLowerCase)
        val visible = allTools.collect { case s: ScriptTool => s }.filter { t =>
          val isVisible = t.space == sigil.GlobalSpace || accessible.contains(t.space)
          val matchesNeedle = needle.forall(n => t.name.value.toLowerCase.contains(n))
          isVisible && matchesNeedle
        }.sortBy(_.name.value)
        Stream.emit[Event](renderListing(context, visible))
      }
    }
  )

  private def renderListing(context: TurnContext, tools: List[ScriptTool]): Event = {
    val body =
      if (tools.isEmpty) "No script tools visible."
      else tools.map(t =>
        s"- **${t.name.value}** _(space: ${t.space.value})_ — ${t.description}"
      ).mkString("\n")
    Message(
      participantId  = context.caller,
      conversationId = context.conversation.id,
      topicId        = context.conversation.currentTopicId,
      content        = Vector(ResponseContent.Markdown(body)),
      state          = EventState.Complete,
      role           = MessageRole.Tool,
      visibility     = MessageVisibility.Agents
    )
  }
}
