package sigil.vector

import lightdb.id.Id
import rapid.Task
import sigil.Sigil
import sigil.db.Model
import sigil.participant.ParticipantId
import sigil.tool.consult.{ConsultTool, RerankInput, RerankTool}

/**
 * LLM-based re-rank of retrieval candidates. Takes the top-N
 * results from a prior search (vector, hybrid, whatever), feeds
 * them to a capable model along with the query, and asks for a
 * relevance-ordered reshuffle.
 *
 * Typical use: wrap hybrid search → feed top-20 candidates to
 * LLMReranker → keep the re-ordered top-5. The rerank lift is
 * biggest on semantically-similar-but-wrong candidates that
 * cosine/keyword can't disambiguate.
 *
 * Implementation: uses [[ConsultTool.invoke]] with
 * [[RerankTool]], same pattern as the extractors. Failures leave
 * the candidate list unchanged.
 *
 * The caller is responsible for cutting the list to the final
 * display size after rerank — this type only reorders.
 */
case class LLMReranker(modelId: Id[Model],
                       chain: List[ParticipantId],
                       textKey: String = HybridSearch.TextKey,
                       maxCandidates: Int = 20,
                       systemPrompt: String = LLMReranker.DefaultSystemPrompt) {

  def rerank(sigil: Sigil,
             query: String,
             candidates: List[VectorSearchResult]): Task[List[VectorSearchResult]] = {
    val pool = candidates.take(maxCandidates)
    // Remainder: candidates beyond the LLM's pool. Kept at their
    // original positions (vector-ranked) so downstream slicing still
    // sees the full retrieval pool, just with the first N reordered
    // by the model.
    val remainder = candidates.drop(maxCandidates)
    if (pool.size <= 1) Task.pure(candidates)
    else {
      val numbered = pool.zipWithIndex.map { case (c, i) =>
        val text = c.payload.getOrElse(textKey, "(no text)")
        val snippet = if (text.length > 400) text.take(400) + "…" else text
        s"[${c.id}] $snippet"
      }.mkString("\n\n")
      val userPrompt =
        s"""Query: $query
           |
           |Candidates:
           |$numbered
           |
           |Return the candidate ids in order from most-relevant to least-relevant to the query.
           |Include every candidate id exactly once.""".stripMargin

      ConsultTool
        .invoke[RerankInput](
          sigil = sigil,
          modelId = modelId,
          chain = chain,
          systemPrompt = systemPrompt,
          userPrompt = userPrompt,
          tool = RerankTool
        )
        .map {
          case Some(result) =>
            val byId = pool.map(c => c.id -> c).toMap
            val ordered = result.orderedIds.distinct.flatMap(byId.get)
            val seen = ordered.map(_.id).toSet
            // Pool candidates the model omitted (shouldn't happen
            // with a well-behaved model) keep their original order.
            val missed = pool.filterNot(c => seen.contains(c.id))
            ordered ++ missed ++ remainder
          case None => candidates
        }
        .handleError(_ => Task.pure(candidates))
    }
  }
}

object LLMReranker {
  val DefaultSystemPrompt: String =
    """You are a relevance ranker. Given a query and a list of candidate snippets, return the candidate
      |ids in order from most-relevant to least-relevant to the query. Consider exact identifier matches,
      |topical alignment, and specificity. Return every candidate id exactly once.""".stripMargin
}
