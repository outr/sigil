package sigil.vector

import rapid.Task

/**
 * Storage-and-search interface for dense vectors. Sigil's framework
 * code talks to this trait; apps pick the backend (Qdrant for
 * production, in-memory for tests, no-op when embeddings aren't
 * wired).
 *
 * Payloads are a flat `Map[String, String]` — the lowest common
 * denominator every backend supports. Richer payloads belong in the
 * primary store; the vector index only needs enough metadata to
 * filter results and re-hydrate from the main DB.
 */
trait VectorIndex {
  /** Insert or replace a single point. */
  def upsert(point: VectorPoint): Task[Unit]

  /** Batch upsert — more efficient than looping [[upsert]] for bulk
    * indexing (e.g. initial import or re-embedding). */
  def upsertBatch(points: List[VectorPoint]): Task[Unit]

  /** Search by cosine (or backend-native) similarity. `filter` is an
    * exact-match predicate against payload keys — empty map means no
    * filtering. Returns up to `limit` results, highest `score` first. */
  def search(vector: Vector[Double],
             limit: Int = 10,
             filter: Map[String, String] = Map.empty): Task[List[VectorSearchResult]]

  /** Search with a richer payload predicate — exact-match clauses plus
    * any-of value-set clauses, ANDed. Backends that can express
    * value-set matching natively (Qdrant `match any`) override this
    * for a single round-trip; the default expands each `anyOf` clause
    * into per-value exact searches and merges by score, so the filter
    * still applies inside the index's top-K cut rather than after it. */
  def search(vector: Vector[Double],
             limit: Int,
             filter: VectorQueryFilter): Task[List[VectorSearchResult]] =
    if (filter.anyOf.isEmpty) search(vector, limit, filter.exact)
    else {
      val combos = filter.anyOf.foldLeft(List(filter.exact)) { case (bases, (key, values)) =>
        bases.flatMap(base => values.toList.map(v => base + (key -> v)))
      }
      Task.sequence(combos.map(c => search(vector, limit, c))).map { results =>
        results.flatten
          .groupBy(_.id).values.map(_.maxBy(_.score)).toList
          .sortBy(-_.score)
          .take(limit)
      }
    }

  /** Delete a single point by id. No-op if the id is not present. */
  def delete(id: String): Task[Unit]

  /** Idempotently ensure the underlying collection / table exists with
    * the given vector dimensionality. Called once at init for backends
    * that need schema setup (Qdrant); trivially unit for in-memory. */
  def ensureCollection(dimensions: Int): Task[Unit]
}
