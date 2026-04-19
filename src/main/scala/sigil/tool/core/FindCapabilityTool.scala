package sigil.tool.core

import sigil.event.{Event, Message}
import sigil.tool.{Tool, ToolContext, ToolExample}
import sigil.tool.model.ResponseContent

/**
 * Discovery tool. The agent calls `find_capability` when it needs to check
 * what tools exist to satisfy the current request — rather than guessing or
 * claiming inability to do something. Returns a Message listing each matching
 * tool's name and description so the LLM can read them in its next turn and
 * decide whether to call one of them.
 *
 * Per-instance: holds a reference to the [[ToolManager]] supplied by the app
 * (via [[sigil.Sigil.toolManager]]).
 */
case class FindCapabilityTool(manager: ToolManager) extends Tool[FindCapabilityInput] {
  override protected def uniqueName: String = "find_capability"

  override protected def description: String =
    """Discover tools available for the current request. Call this when the user asks for something and you
      |aren't sure whether a tool exists for it — do NOT guess or claim inability until you've checked.
      |
      |The `query` is a free-form search string describing the desired capability in whatever phrasing feels
      |natural ("send a slack message", "generate an invoice", "count rows in the users table"). The response
      |will list matching tools with their descriptions; you can then call the right one on your next turn.""".stripMargin

  override protected def examples: List[ToolExample[FindCapabilityInput]] = List(
    ToolExample("Direct capability lookup", FindCapabilityInput("send a slack message")),
    ToolExample("Concept search", FindCapabilityInput("billing / invoices"))
  )

  override def execute(input: FindCapabilityInput, context: ToolContext): rapid.Stream[Event] =
    rapid.Stream.force(
      manager
        .find(input.query, context.chain)
        .map(tools => rapid.Stream.emits(List(buildMessage(input.query, tools, context))))
    )

  private def buildMessage(query: String,
                           tools: List[Tool[? <: sigil.tool.ToolInput]],
                           context: ToolContext): Message = {
    val blocks: Vector[ResponseContent] =
      if (tools.isEmpty) {
        Vector(ResponseContent.Text(s"No tools matched the query: $query"))
      } else {
        val header = ResponseContent.Text(s"Tools matching '$query':")
        val listed = tools.map { t =>
          val s = t.schema
          ResponseContent.Field(label = s.name, value = s.description)
        }
        (header +: listed).toVector
      }
    Message(
      participantId = context.caller,
      conversationId = context.conversation.id,
      content = blocks
    )
  }
}
