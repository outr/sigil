package sigil.conversation.compression.retrieval

import rapid.Task
import sigil.conversation.ContextMemory
import sigil.conversation.compression.TokenEstimator
import sigil.tokenize.{HeuristicTokenizer, Tokenizer}

/**
 * Budget — cuts the ranked list to what the turn can afford:
 *
 *   1. count cap — `take(limit)`;
 *   2. pinned exclusion — drops the turn's pinned ids
 *      ([[MemoryRetrievalContext.exclude]]) so criticals never
 *      double-render (applied after the count cut, matching the
 *      legacy retriever's order);
 *   3. optional token cap — walks the survivors best-first,
 *      estimating each record's rendered cost (`summary` falling back
 *      to `fact`, via [[TokenEstimator.estimateMemories]]) and keeps
 *      the prefix that fits. Strictly a prefix: a memory that
 *      overflows the budget ends the walk rather than letting a
 *      lower-ranked cheaper record jump it. The top-ranked memory is
 *      always kept, so a single oversized record can't zero the turn.
 */
case class BudgetStage(limit: Int,
                       tokenBudget: Option[Int] = None,
                       tokenizer: Tokenizer = HeuristicTokenizer) extends MemoryRetrievalStage {
  override val name: String = "budget"

  override def run(state: MemoryRetrievalState, ctx: MemoryRetrievalContext): Task[MemoryRetrievalState] = Task {
    val counted = state.ranked.take(limit).filterNot(m => ctx.exclude.contains(m._id))
    state.copy(ranked = tokenBudget.fold(counted)(applyTokenCap(counted, _)))
  }

  private def applyTokenCap(ranked: Vector[ContextMemory], budget: Int): Vector[ContextMemory] = {
    var used = 0
    val kept = Vector.newBuilder[ContextMemory]
    var open = true
    var first = true
    ranked.foreach { m =>
      if (open) {
        val cost = TokenEstimator.estimateMemories(Vector(m), tokenizer)
        if (first || used + cost <= budget) {
          kept += m
          used += cost
          first = false
        } else open = false
      }
    }
    kept.result()
  }
}
