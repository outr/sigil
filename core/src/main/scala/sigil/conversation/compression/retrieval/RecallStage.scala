package sigil.conversation.compression.retrieval

import lightdb.Sort
import lightdb.filter.*
import rapid.Task
import sigil.conversation.{ContextMemory, MemoryStatus}
import sigil.vector.HybridSearch

/**
 * Recall — runs the retrieval legs against the candidate pool:
 *
 *   - Vector (question): [[sigil.Sigil.searchMemories]] over the
 *     user's question text alone (space scope pushed into the index's
 *     top-K cut; falls back to a space-scoped listing when vector
 *     search isn't wired).
 *   - Lexical (question): BM25 over `ContextMemory.searchText` with
 *     the question's discriminative terms (punctuation normalized,
 *     stopwords dropped — see [[tokensOf]]), with spaces,
 *     `pinned == false`, and `status == Approved` compiled into the
 *     Lucene query so a large multi-tenant store can't crowd in-space
 *     matches out of the pool.
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
      // The vector leg runs only when vector search is actually wired:
      // `searchMemories` otherwise falls back to an UNRANKED
      // space-scoped listing, which fused at vectorWeight would inject
      // arbitrary-order noise the lexical leg already supersedes.
      vector <- if (question.isEmpty || !ctx.sigil.vectorWired) Task.pure(Nil)
                else ctx.sigil.searchMemories(question, ctx.spaces, ctx.candidatePool)
      lexical <- luceneHits(ctx, tokensOf(question))
      keyword <- luceneHits(ctx, tokensOf(ctx.contextTerms.mkString(" ")))
    } yield state.copy(
      lexical = lexical.toVector,
      vectorHits = vector.toVector,
      keywordHits = keyword.toVector
    )
  }

  /** Query terms for a BM25 leg: tokenized the SAME way the indexed
    * content is — punctuation split off and stopwords dropped (via
    * [[sigil.vector.HybridSearch.tokenizeList]]) — then deduplicated
    * and capped at [[RecallStage.MaxQueryTokens]].
    *
    * Both halves of that normalization are load-bearing for a
    * naturally-phrased question. Splitting on whitespace alone leaves
    * the one discriminative term carrying its punctuation
    * (`"tobacco?"`), which no exact-term clause matches. Keeping
    * stopwords ORs in near-universal function words (`where`, `do`,
    * `you`, `your`), each matching thousands of unrelated facts — and
    * at `lexicalWeight = 2.0` that flood outvotes a vector leg that
    * ranked the answer first. "Where do you keep your tobacco?"
    * reduces to `[keep, tobacco]`.
    *
    * A query of pure stopwords falls back to the punctuation-split
    * tokens: a leg matching common words is weak, but a leg matching
    * nothing gives up recall the fusion has no other source for.
    *
    * The cap bounds the clause count — one `Should` clause is built
    * per token, and a pasted artefact in the user's message would
    * otherwise compile into a clause count Lucene rejects outright
    * (taking the turn with it). Past the cap the extra terms add
    * noise, not recall. */
  private[retrieval] def tokensOf(text: String): List[String] = {
    val filtered = HybridSearch.tokenizeList(text)
    val tokens =
      if (filtered.nonEmpty) filtered
      else text.toLowerCase.split("\\W+").iterator.filter(_.nonEmpty).toList
    tokens.distinct.take(RecallStage.MaxQueryTokens)
  }

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
