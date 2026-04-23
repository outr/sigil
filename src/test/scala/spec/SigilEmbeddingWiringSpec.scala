package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.AsyncTaskSpec
import sigil.conversation.{ContextMemory, ContextSummary, Conversation, MemorySource}
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
  TestSigil.setEmbeddingProvider(TestHashEmbeddingProvider)
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
      } yield hits.flatMap(_.payload.get("summaryId")) should contain(summary._id.value)
    }
  }
}

