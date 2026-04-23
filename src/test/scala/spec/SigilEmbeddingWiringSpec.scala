package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.conversation.{ContextMemory, ContextSummary, Conversation, MemorySource}
import sigil.embedding.EmbeddingProvider
import sigil.vector.InMemoryVectorIndex

/**
 * Verifies Sigil's vector wiring end-to-end: with an
 * [[EmbeddingProvider]] + non-NoOp [[sigil.vector.VectorIndex]]
 * installed, [[sigil.Sigil.persistMemory]] and
 * [[sigil.Sigil.persistSummary]] auto-index their text and
 * [[sigil.Sigil.searchMemories]] retrieves records via the vector
 * index. Uses a deterministic stub embedder so results are
 * reproducible.
 */
class SigilEmbeddingWiringSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  // Wire a deterministic embedder + in-memory index for this suite.
  TestSigil.setEmbeddingProvider(HashEmbeddingProvider)
  TestSigil.setVectorIndex(new InMemoryVectorIndex)
  // `initFor` already ran `ensureCollection` under the earlier NoOp
  // pair; call it again against the newly-installed InMemory index so
  // the shape is consistent (InMemory's ensureCollection is unit).
  TestSigil.vectorIndex.ensureCollection(TestSigil.embeddingProvider.dimensions).sync()

  private val convId = Conversation.id("wiring-conv")

  "Sigil with vector wiring" should {
    "auto-index a persisted ContextMemory and retrieve it via searchMemories" in {
      val mem = ContextMemory(
        fact = "The capital of France is Paris.",
        source = MemorySource.Explicit,
        spaceId = WiringSpace
      )
      for {
        _ <- TestSigil.persistMemory(mem)
        hits <- TestSigil.searchMemories("capital France", Set(WiringSpace), limit = 5)
      } yield hits.map(_._id.value) should contain(mem._id.value)
    }

    "auto-index a persisted ContextSummary (side-effect on the vector index)" in {
      val summary = ContextSummary(
        text = "Earlier in the conversation we discussed French geography.",
        conversationId = convId,
        tokenEstimate = 12
      )
      for {
        _ <- TestSigil.persistSummary(summary)
        hits <- TestSigil.vectorIndex.search(
          TestSigil.embeddingProvider.embed("French geography").sync(),
          limit = 5,
          filter = Map("kind" -> "summary")
        )
      } yield hits.map(_.id) should contain(summary._id.value)
    }
  }
}

/** Deterministic test embedder — each token contributes to a
  * fixed-dim vector via a stable hash. Avoids a real embedding API
  * while exercising the wiring. */
private object HashEmbeddingProvider extends EmbeddingProvider {
  override def dimensions: Int = 32

  override def embed(text: String): Task[Vector[Double]] = Task {
    val buf = Array.fill(dimensions)(0.0)
    text.toLowerCase.split("\\W+").filter(_.nonEmpty).foreach { tok =>
      val h = tok.hashCode
      val i = math.floorMod(h, dimensions)
      buf(i) += 1.0
    }
    val norm = math.sqrt(buf.map(x => x * x).sum)
    if (norm == 0.0) buf.toVector else buf.map(_ / norm).toVector
  }

  override def embedBatch(texts: List[String]): Task[List[Vector[Double]]] =
    Task.sequence(texts.map(embed))
}
