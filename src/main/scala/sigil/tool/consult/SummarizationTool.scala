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
    """Produce a compact, self-contained summary of the provided conversation excerpt that can replace the excerpt in future turns.
      |
      |Summary requirements:
      |  - Preserve durable facts, names, decisions, and open questions the agents will need later.
      |  - Drop small-talk, retries, and intermediate reasoning steps the agents would not re-read.
      |  - Write in third person narrative — never "I said" / "you said". Refer to participants by role.
      |  - Keep it short (aim for a single paragraph unless the excerpt is long and fact-dense).
      |
      |`tokenEstimate` is your own best estimate of the `summary` length in tokens — the framework uses
      |it for downstream budget math.""".stripMargin

  override protected def examples: List[ToolExample[SummarizationInput]] = Nil

  override def execute(input: SummarizationInput, context: TurnContext): Stream[Event] =
    Stream.empty
}
