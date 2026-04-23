package sigil.tool.consult

import lightdb.id.Id
import rapid.Stream
import sigil.TurnContext
import sigil.event.Event
import sigil.tool.{Tool, ToolExample, ToolName, ToolSchema}

/**
 * Internal-only tool used by [[sigil.conversation.compression.SummaryOnlyCompressor]]
 * to force the consulted model into a structured summary output. Never
 * registered into any agent's tool roster — the compressor calls it via
 * [[ConsultTool.invoke]] with `tool_choice = required`.
 */
object SummarizationTool extends Tool[SummarizationInput] {
  override protected def uniqueName: String = "summarize_conversation"

  override protected def description: String =
    """Emit the final summary of a conversation excerpt as structured output. The supplied
      |`summary` will replace the excerpt in every subsequent turn — so it must stand on its own
      |without the original text.
      |
      |`summary` — the compact narrative. See the system prompt for style / content rules.
      |
      |`tokenEstimate` — your best estimate of `summary` length in tokens (~4 chars/token is fine).
      |The framework uses this to budget future turns.""".stripMargin

  override protected def examples: List[ToolExample[SummarizationInput]] = Nil

  override def execute(input: SummarizationInput, context: TurnContext): Stream[Event] =
    Stream.empty
}
