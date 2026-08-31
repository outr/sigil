package sigil.embedding

import rapid.Task

/**
 * Generates vector embeddings from text. Used for semantic search,
 * memory retrieval, and RAG. Each [[sigil.Sigil]] instance wires a
 * single implementation; apps that don't need embeddings leave the
 * default [[NoOpEmbeddingProvider]] in place.
 *
 * Implementations are expected to be thread-safe.
 */
trait EmbeddingProvider {

  /**
   * Stable identifier for the embedding space this provider produces
   * vectors in. Two providers sharing an `id` and [[dimensions]] must
   * produce comparable vectors; changing the underlying model MUST
   * change the `id`, because [[EmbeddingRef]] uses it to detect that a
   * persisted vector point was built by a different embedder and needs
   * recomputing. Defaults to the implementation's simple class name —
   * providers parameterised by model name override to include it.
   */
  def id: String = getClass.getSimpleName.stripSuffix("$")

  /**
   * Embed a single text into a vector of length [[dimensions]].
   */
  def embed(text: String): Task[Vector[Double]]

  /**
   * Embed multiple texts in one batch — usually cheaper than N single
   * calls. The returned list MUST be the same length and order as
   * `texts`.
   */
  def embedBatch(texts: List[String]): Task[List[Vector[Double]]]

  /**
   * The dimensionality of the vectors this provider produces. The
   * paired [[sigil.vector.VectorIndex]] must be initialized with this
   * size. `0` signals "no embeddings" — [[NoOpEmbeddingProvider]].
   */
  def dimensions: Int
}
