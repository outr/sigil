package sigil.tool.core

import sigil.TurnContext
import sigil.event.Event
import sigil.tool.{ToolName, TypedTool}
import sigil.tool.model.NoResponseInput

/**
 * Lets the agent end its turn without producing any user-visible content.
 *
 * In a multi-participant chat-based model, an agent may be activated on events
 * that aren't directed at it. Rather than forcing a response, the agent calls
 * `no_response` to signal "nothing from me this turn."
 */
case object NoResponseTool extends TypedTool[NoResponseInput](
  name = ToolName("no_response"),
  description =
    """Decline to respond when the latest message isn't directed at you or doesn't warrant a reply.
      |Prefer this over `respond` with filler like "I don't have anything to add" — silent decline is cleaner.""".stripMargin
) {
  override protected def executeTyped(input: NoResponseInput, context: TurnContext): rapid.Stream[Event] = rapid.Stream.empty
}
