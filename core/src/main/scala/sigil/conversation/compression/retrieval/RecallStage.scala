package sigil.conversation.compression.retrieval

import lightdb.Sort
import lightdb.filter.*
import rapid.Task
import sigil.conversation.{ContextMemory, MemoryStatus}

/**
 * Recall — runs the retrieval legs against the candidate pool:
 *
 *   - Vector (question): [[sigil.Sigil.searchMemories]] over the
 *     user's question text alone (space scope pushed into the index's
 *     top-K cut; falls back to a space-scoped listing when vector
 *     search isn't wired).
 *   - Lexical (question): BM25 over `ContextMemory.searchText` with
 *     the question's tokens, with spaces, `pinned == false`, and
 *     `status == Approved` compiled into the Lucene query so a large
 *     multi-tenant store can't crowd in-space matches out of the pool.
 *   - Keyword (context): the same BM25 shape over
 *     [[MemoryRetrievalContext.contextTerms]] — the classifier's
 *     conversation keywords plus the topic label — as its OWN leg.
 *
 * The question legs deliberately see NOTHING but the question: mixing
 * topic/summary text into their query biases the embedding (and the
 * BM25 token set) toward the conversation's general theme, crowding
 * out the memory that answers a specific factual question. The
 * context signal still contributes — through the keyword leg, at its
 * own fusion weight.
 *
 * All legs land raw on the state; the Gate stage applies the shared
 * recall predicate.
 */
case class RecallStage() extends MemoryRetrievalStage {
  override val name: String = "recall"

  override def run(state: MemoryRetrievalState, ctx: MemoryRetrievalContext): Task[MemoryRetrievalState] = {
    val question = ctx.query.trim
    for {
      vector <- if (question.isEmpty) Task.pure(Nil)
                else ctx.sigil.searchMemories(question, ctx.spaces, ctx.candidatePool)
      lexical <- luceneHits(ctx, tokensOf(question))
      keyword <- luceneHits(ctx, tokensOf(ctx.contextTerms.mkString(" ")))
    } yield state.copy(
      lexical = lexical.toVector,
      vectorHits = vector.toVector,
      keywordHits = keyword.toVector
    )
  }

  /** Whitespace tokens, lowercased, deduplicated, capped at
    * [[RecallStage.MaxQueryTokens]] — one `Should` clause is built per
    * token, and an oversized token list (a pasted artefact in the
    * user's message) would otherwise compile into a clause count
    * Lucene rejects outright (taking the turn with it). Past the cap
    * the extra terms add noise, not recall. */
  private def tokensOf(text: String): List[String] =
    text.toLowerCase.split("\\s+").iterator
      .map(_.trim).filter(_.nonEmpty)
      .toList.distinct.take(RecallStage.MaxQueryTokens)

  /** Lucene BM25 query over `ContextMemory.searchText`, OR-matching
    * the supplied tokens; result order is BM25 relevance. */
  private def luceneHits(ctx: MemoryRetrievalContext, tokens: List[String]): Task[List[ContextMemory]] =
    if (tokens.isEmpty || ctx.spaces.isEmpty) Task.pure(Nil)
    else ctx.sigil.withDB(_.memories.transaction { tx =>
      tx.query
        .filter { _ =>
          val clauses = tokens.map { kw =>
            FilterClause(ContextMemory.searchText.exactly(kw), Condition.Should, None)
          }
          val spaceClauses = ctx.spaces.toList.map { space =>
            FilterClause(ContextMemory.spaceIdValue === space.value, Condition.Should, None)
          }
          Filter.Multi(minShould = 1, filters = clauses) &&
            Filter.Multi(minShould = 1, filters = spaceClauses) &&
            (ContextMemory.pinned === false) &&
            (ContextMemory.statusName === MemoryStatus.Approved.toString)
        }
        .scored
        .sort(Sort.BestMatch())
        .limit(ctx.candidatePool)
        .toList
    })
}

object RecallStage {
  /** Ceiling on the distinct query tokens compiled into a lexical
    * leg's clause list. */
  val MaxQueryTokens: Int = 32
}
