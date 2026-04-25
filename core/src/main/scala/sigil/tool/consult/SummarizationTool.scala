package sigil.tool.consult

import rapid.Stream
import sigil.TurnContext
import sigil.event.Event
import sigil.tool.{ToolName, TypedTool}

/**
 * Internal-only tool used by [[sigil.conversation.compression.SummaryOnlyCompressor]]
 * to force the consulted model into a structured summary output.
 */
case object SummarizationTool extends TypedTool[SummarizationInput](
  name = ToolName("summarize_conversation"),
  description =
    """Emit the final summary of a conversation excerpt as structured output. The supplied
      |`summary` will replace the excerpt in every subsequent turn — so it must stand on its own
      |without the original text.
      |
      |`summary` — the compact narrative. See the system prompt for style / content rules.
      |
      |`tokenEstimate` — your best estimate of `summary` length in tokens (~4 chars/token is fine).
      |The framework uses this to budget future turns.""".stripMargin
) {
  override protected def executeTyped(input: SummarizationInput, context: TurnContext): Stream[Event] =
    Stream.empty
}
