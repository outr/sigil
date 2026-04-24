package sigil.vector

import rapid.Task
import sigil.embedding.EmbeddingProvider

/**
 * Retrieval wrapper that combines cosine similarity with BM25 keyword
 * scoring. Plain vector search misses queries whose exact identifiers
 * (names, dates, slugs) or rare distinguishing keywords matter more
 * than semantic similarity; hybrid scoring restores that signal.
 *
 * Approach:
 *   1. Over-fetch from the base [[VectorIndex]] (`candidateMultiplier
 *      * limit`) so keyword-rich hits that weren't in the top-limit
 *      by cosine have a chance to surface.
 *   2. Compute BM25 for each candidate against the query using the
 *      over-fetched pool as the mini-corpus for document-frequency /
 *      IDF. BM25 weights rare tokens heavily (via IDF) and normalizes
 *      for document length, so a single mention of a distinguishing
 *      word like "led" in a long session is surfaced correctly —
 *      which Jaccard (symmetric set overlap) does not.
 *   3. Min/max-normalize the BM25 scores to `[0, 1]` so they're
 *      comparable to the cosine scores before blending.
 *   4. Final score = `semanticWeight * semanticScore + (1 -
 *      semanticWeight) * bm25Normalized`.
 *   5. Re-rank and return the top `limit`.
 *
 * The indexed text must be stored in each point's payload under
 * [[HybridSearch.TextKey]] (default `"text"`).
 */
case class HybridSearch(inner: VectorIndex,
                        embedder: EmbeddingProvider,
                        semanticWeight: Double = 0.7,
                        candidateMultiplier: Int = 3,
                        textKey: String = HybridSearch.TextKey,
                        bm25K1: Double = 1.5,
                        bm25B: Double = 0.75) {

  require(semanticWeight >= 0.0 && semanticWeight <= 1.0, "semanticWeight must be in [0, 1]")
  require(candidateMultiplier >= 1, "candidateMultiplier must be >= 1")

  def search(query: String,
             limit: Int = 10,
             filter: Map[String, String] = Map.empty): Task[List[VectorSearchResult]] =
    embedder.embed(query).flatMap { vec =>
      inner.search(vec, limit = limit * candidateMultiplier, filter = filter).map { candidates =>
        val queryTokens = HybridSearch.tokenizeList(query)
        if (queryTokens.isEmpty) candidates.take(limit)
        else {
          val docs = candidates.map(c => HybridSearch.tokenizeList(c.payload.getOrElse(textKey, "")))
          val bm25Raw = HybridSearch.bm25Scores(queryTokens, docs, bm25K1, bm25B)
          val bm25Norm = HybridSearch.minMaxNormalize(bm25Raw)
          val rescored = candidates.zip(bm25Norm).map { case (c, kw) =>
            val blended = semanticWeight * c.score + (1.0 - semanticWeight) * kw
            c.copy(score = blended)
          }
          rescored.sortBy(-_.score).take(limit)
        }
      }
    }
}

object HybridSearch {

  /** Conventional payload key under which the original indexed text
    * is stored so [[HybridSearch]] can compute keyword overlap. */
  val TextKey: String = "text"

  private val stopWords: Set[String] = Set(
    "a", "an", "the", "and", "or", "but", "if", "then", "else", "of",
    "to", "in", "on", "at", "by", "for", "with", "as", "is", "are",
    "was", "were", "be", "been", "being", "have", "has", "had",
    "do", "does", "did", "this", "that", "these", "those", "it",
    "its", "i", "you", "he", "she", "we", "they", "me", "him", "her",
    "us", "them", "my", "your", "his", "our", "their",
    "how", "what", "when", "where", "why", "who", "which",
    "am", "can", "could", "should", "would", "will", "may", "might"
  )

  /** Tokenize preserving repetition (BM25 uses term frequencies). */
  def tokenizeList(text: String): List[String] = {
    if (text == null || text.isEmpty) Nil
    else text
      .toLowerCase
      .split("\\W+")
      .iterator
      .filter(_.nonEmpty)
      .filter(t => !stopWords.contains(t))
      .toList
  }

  /** Back-compat set tokenizer. BM25 uses [[tokenizeList]]. */
  def tokenize(text: String): Set[String] = tokenizeList(text).toSet

  /** Okapi BM25 over a candidate pool. `docs.length` is used as the
    * effective corpus size for IDF — not true global IDF, but a
    * reasonable within-pool approximation since we only care about
    * relative ranking of candidates the vector step already surfaced. */
  def bm25Scores(query: List[String],
                 docs: List[List[String]],
                 k1: Double = 1.5,
                 b: Double = 0.75): List[Double] = {
    if (docs.isEmpty) Nil
    else {
      val n = docs.size
      val avgdl = {
        val total = docs.iterator.map(_.length).sum.toDouble
        if (n == 0) 0.0 else total / n
      }
      val qTerms = query.distinct
      val df: Map[String, Int] = qTerms.map { t => t -> docs.count(_.contains(t)) }.toMap
      // BM25+ smoothing so common terms still contribute positively.
      val idf: Map[String, Double] = df.view.mapValues { f =>
        math.log(1.0 + (n - f + 0.5) / (f + 0.5))
      }.toMap
      docs.map { d =>
        val dl = d.length
        val tf = d.groupBy(identity).view.mapValues(_.size).toMap
        qTerms.iterator.map { t =>
          val f = tf.getOrElse(t, 0)
          if (f == 0) 0.0
          else {
            val num = f * (k1 + 1)
            val denom = f + k1 * (1.0 - b + b * (dl.toDouble / math.max(avgdl, 1.0)))
            idf.getOrElse(t, 0.0) * num / denom
          }
        }.sum
      }
    }
  }

  /** Min/max scale a list of non-negative scores into `[0, 1]`. If
    * all scores are equal (including all-zero), returns zeros — the
    * BM25 signal has no discriminative power on this pool. */
  def minMaxNormalize(scores: List[Double]): List[Double] = {
    if (scores.isEmpty) Nil
    else {
      val lo = scores.min
      val hi = scores.max
      val range = hi - lo
      if (range <= 0.0) scores.map(_ => 0.0)
      else scores.map(s => (s - lo) / range)
    }
  }

  /** Kept for callers outside the hybrid path; BM25 replaced Jaccard
    * as the internal scorer in [[HybridSearch.search]]. */
  def jaccard(a: Set[String], b: Set[String]): Double = {
    if (a.isEmpty || b.isEmpty) 0.0
    else {
      val inter = a.intersect(b).size.toDouble
      val union = a.union(b).size.toDouble
      if (union == 0.0) 0.0 else inter / union
    }
  }
}
