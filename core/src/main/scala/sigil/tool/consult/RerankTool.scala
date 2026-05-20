package sigil.tool.consult

import rapid.Stream
import sigil.TurnContext
import sigil.event.Event
import sigil.provider.{ClassificationWork, GenerationSettings, ReasoningMode, WorkType}
import sigil.tool.{ToolName, TypedTool}

/**
 * Internal tool invoked by [[sigil.vector.LLMReranker]]. Never registered
 * on any agent's roster — the reranker calls it via `ConsultTool.invoke`
 * with `tool_choice = required`.
 */
case object RerankTool extends TypedTool[RerankInput](
  name = ToolName("rerank_candidates"),
  description =
    """Re-rank a list of candidate snippets by relevance to a query. Return the candidate ids
      |in order from most-relevant to least-relevant.
      |
      |`orderedIds` — a list of candidate id strings. Include every id from the candidate set
      |exactly once. The first id is the most relevant, the last is the least. Ids missing
      |from the output are treated as least-relevant (appended at the end in their original
      |order); ids not in the candidate set are ignored.""".stripMargin
) with FrameworkConsult {
  override def paginate: Boolean = false

  /** Relevance scoring — routes through the cheap classification tier. */
  override def consultWorkType: WorkType = ClassificationWork

  /** Output is a list of candidate id strings — bounded by the
    * candidate-pool size (typically <= 20). 768 tokens covers a full
    * reorder plus the reasoning-spill margin. */
  override def consultSettings: GenerationSettings = GenerationSettings(
    maxOutputTokens = Some(768),
    reasoningMode = ReasoningMode.Off
  )

  override protected def executeTyped(input: RerankInput, context: TurnContext): Stream[Event] = Stream.empty
}
