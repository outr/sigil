package sigil.conversation.compression.retrieval

import lightdb.Sort
import lightdb.filter.*
import rapid.Task
import sigil.conversation.{ContextMemory, MemoryStatus}

/**
 * Recall — runs the two retrieval legs against the candidate pool:
 *
 *   - Vector: [[sigil.Sigil.searchMemories]] (space scope pushed into
 *     the index's top-K cut; falls back to a space-scoped listing when
 *     vector search isn't wired).
 *   - Lexical: BM25 over `ContextMemory.searchText`, with spaces,
 *     `pinned == false`, and `status == Approved` compiled into the
 *     Lucene query so a large multi-tenant store can't crowd in-space
 *     matches out of the pool.
 *
 * Both legs land raw on the state; the Gate stage applies the shared
 * recall predicate.
 */
case class RecallStage() extends MemoryRetrievalStage {
  override val name: String = "recall"

  override def run(state: MemoryRetrievalState, ctx: MemoryRetrievalContext): Task[MemoryRetrievalState] =
    for {
      vector <- ctx.sigil.searchMemories(ctx.query, ctx.spaces, ctx.candidatePool)
      lexical <- luceneHits(ctx)
    } yield state.copy(lexical = lexical.toVector, vectorHits = vector.toVector)

  /** Lucene BM25 query over `ContextMemory.searchText`. Splits the
    * query into whitespace tokens and OR-matches; result order is BM25
    * relevance.
    *
    * The token list is deduplicated and capped at
    * [[RecallStage.MaxQueryTokens]] — one `Should` clause is built per
    * token, and the retrieval query is composed from topic label +
    * summary + keywords + the last user message, so a pasted artefact
    * would otherwise compile into a clause count Lucene rejects
    * outright (taking the turn with it). Past the cap the extra terms
    * add noise, not recall. */
  private def luceneHits(ctx: MemoryRetrievalContext): Task[List[ContextMemory]] = {
    val tokens = ctx.query.toLowerCase.split("\\s+").iterator
      .map(_.trim).filter(_.nonEmpty)
      .toList.distinct.take(RecallStage.MaxQueryTokens)
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
}

object RecallStage {
  /** Ceiling on the distinct query tokens compiled into the lexical
    * leg's clause list. */
  val MaxQueryTokens: Int = 32
}
