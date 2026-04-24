package sigil.tool.consult

import rapid.Stream
import sigil.TurnContext
import sigil.event.Event
import sigil.tool.{Tool, ToolExample}

/**
 * Internal tool invoked by [[sigil.vector.LLMReranker]]. Never
 * registered on any agent's roster — the reranker calls it via
 * `ConsultTool.invoke` with `tool_choice = required`.
 *
 * The tool's output is a single list: the candidate ids in order
 * from most-to-least relevant to the query.
 */
object RerankTool extends Tool[RerankInput] {
  override protected def uniqueName: String = "rerank_candidates"

  override protected def description: String =
    """Re-rank a list of candidate snippets by relevance to a query. Return the candidate ids
      |in order from most-relevant to least-relevant.
      |
      |`orderedIds` — a list of candidate id strings. Include every id from the candidate set
      |exactly once. The first id is the most relevant, the last is the least. Ids missing
      |from the output are treated as least-relevant (appended at the end in their original
      |order); ids not in the candidate set are ignored.""".stripMargin

  override protected def examples: List[ToolExample[RerankInput]] = Nil

  override def execute(input: RerankInput, context: TurnContext): Stream[Event] = Stream.empty
}
