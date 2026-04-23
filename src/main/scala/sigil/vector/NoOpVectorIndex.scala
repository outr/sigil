package sigil.vector

import rapid.Task

/**
 * Default [[VectorIndex]] — swallows upserts, returns no results from
 * `search`. Signals "no vector index configured." Paired with
 * [[sigil.embedding.NoOpEmbeddingProvider]]; semantic search APIs on
 * [[sigil.Sigil]] fall back to Lucene full-text when this is the wired
 * index.
 */
object NoOpVectorIndex extends VectorIndex {
  override def upsert(point: VectorPoint): Task[Unit] = Task.unit

  override def upsertBatch(points: List[VectorPoint]): Task[Unit] = Task.unit

  override def search(vector: Vector[Double],
                      limit: Int,
                      filter: Map[String, String]): Task[List[VectorSearchResult]] =
    Task.pure(Nil)

  override def delete(id: String): Task[Unit] = Task.unit

  override def ensureCollection(dimensions: Int): Task[Unit] = Task.unit
}
