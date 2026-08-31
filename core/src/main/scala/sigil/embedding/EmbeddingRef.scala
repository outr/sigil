package sigil.embedding

import fabric.rw.*
import lightdb.time.Timestamp

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Provenance for a record's vector-index point: which embedder built
 * it, at what dimensionality, and over exactly what text.
 *
 * Without it a stale point is undetectable — the store row and the
 * vector index drift apart silently whenever a write path skips
 * re-indexing, an embedding call fails and is swallowed, or the app
 * swaps embedding models. With it, staleness is a pure comparison
 * between the stamp and the record's current text plus the live
 * provider's identity, which is what
 * [[sigil.maintenance.EmbeddingReconcileTask]] sweeps on.
 *
 * `contentHash` is the SHA-256 of the embedded text, not of the whole
 * record: metadata edits (label, keywords, pin state) don't change the
 * vector, so they must not mark the point stale.
 */
case class EmbeddingRef(model: String,
                        dimensions: Int,
                        contentHash: String,
                        indexedAt: Timestamp = Timestamp())
  derives RW {

  /**
   * The embedding space this point lives in. Vectors are only
   * comparable within one identity, so a change here invalidates the
   * point regardless of whether the text moved.
   */
  def identity: String = EmbeddingRef.identity(model, dimensions)

  /**
   * `true` when this stamp still describes `text` embedded by
   * `provider` — the point is current and needs no work.
   */
  def isCurrentFor(text: String, provider: EmbeddingProvider): Boolean =
    contentHash == EmbeddingRef.hash(text) && identity == EmbeddingRef.identityOf(provider)
}

object EmbeddingRef {

  /**
   * The [[EmbeddingRef.identity]] projection for a record that holds
   * no usable point — never indexed, or indexed against text it no
   * longer carries. Distinct from every real identity, so the
   * reconcile sweep's single indexed inequality query catches both
   * cases alongside embedder drift.
   */
  val Unindexed: String = "unindexed"

  def identity(model: String, dimensions: Int): String = s"$model/$dimensions"

  def identityOf(provider: EmbeddingProvider): String = identity(provider.id, provider.dimensions)

  /**
   * SHA-256 hex of `text`.
   */
  def hash(text: String): String =
    MessageDigest.getInstance("SHA-256")
      .digest(text.getBytes(StandardCharsets.UTF_8))
      .iterator
      .map(b => f"${b & 0xff}%02x")
      .mkString

  /**
   * The stamp for `text` freshly embedded by `provider`.
   */
  def forText(provider: EmbeddingProvider, text: String): EmbeddingRef =
    EmbeddingRef(
      model = provider.id,
      dimensions = provider.dimensions,
      contentHash = hash(text),
      indexedAt = Timestamp()
    )
}
