package sigil.embedding

import rapid.Task

/**
 * Default [[EmbeddingProvider]] — produces empty vectors and reports
 * `dimensions = 0`. Signals "no embeddings configured." Sigil skips
 * auto-indexing when this is the wired provider; semantic search APIs
 * fall back to Lucene.
 */
object NoOpEmbeddingProvider extends EmbeddingProvider {
  override def embed(text: String): Task[Vector[Double]] = Task.pure(Vector.empty)

  override def embedBatch(texts: List[String]): Task[List[Vector[Double]]] =
    Task.pure(texts.map(_ => Vector.empty))

  override def dimensions: Int = 0
}
