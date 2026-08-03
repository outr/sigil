package sigil.conversation.compression.retrieval

import rapid.Task
import sigil.conversation.ContextMemory
import sigil.conversation.compression.TokenEstimator
import sigil.tokenize.{HeuristicTokenizer, Tokenizer}

/**
 * Budget — cuts the ranked list to what the turn can afford:
 *
 *   1. pinned exclusion — drops the turn's pinned ids
 *      ([[MemoryRetrievalContext.exclude]]) so criticals never
 *      double-render. Applied BEFORE the count cut so an excluded
 *      entry gives its slot back: the default pipeline's Gate stage
 *      already drops pinned records, but a custom pipeline that gates
 *      differently (or populates `exclude` with something other than
 *      the pinned set) would otherwise silently under-fill the turn;
 *   2. count cap — `take(limit)`;
 *   3. optional token cap — walks the survivors best-first,
 *      estimating each record's rendered cost (`summary` falling back
 *      to `fact`, via [[TokenEstimator.memoryTokens]]) and keeps
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
    val counted = state.ranked.filterNot(m => ctx.exclude.contains(m._id)).take(limit)
    state.copy(ranked = tokenBudget.fold(counted)(applyTokenCap(counted, _)))
  }

  private def applyTokenCap(ranked: Vector[ContextMemory], budget: Int): Vector[ContextMemory] = {
    var used = 0
    val kept = Vector.newBuilder[ContextMemory]
    var open = true
    var first = true
    ranked.foreach { m =>
      if (open) {
        val cost = TokenEstimator.memoryTokens(m, tokenizer)
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
