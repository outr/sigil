package bench

import fabric.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.embedding.EmbeddingProvider
import sigil.vector.InMemoryVectorIndex

/**
 * End-to-end coverage of the benchmark harness against a synthetic
 * fixture. Exercises the LoCoMo-shaped pipeline — embed haystack →
 * search by question → map ranked ids back to conversations →
 * measure Recall@k — with the live Qdrant + OpenAI stack swapped for
 * in-memory stand-ins so the pipeline can be verified in CI.
 *
 * Absolute scores on real datasets depend on the embedding model
 * quality (orthogonal to the harness wiring this spec checks).
 */
class BenchmarkParitySpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {

  /** Deterministic token-hash embedder — same shape as
    * `TestHashEmbeddingProvider` in the main test sources, but we
    * don't have access to that from this subproject. Token overlap
    * yields high similarity, no overlap yields low similarity. */
  private object StubEmbedder extends EmbeddingProvider {
    override def dimensions: Int = 64
    override def embed(text: String): Task[Vector[Double]] = Task {
      val tokens = text.toLowerCase.split("\\W+").filter(_.nonEmpty).toSet
      val vec = Array.fill(64)(0.0)
      tokens.foreach { t =>
        val idx = math.abs(t.hashCode) % 64
        vec(idx) += 1.0
      }
      val norm = math.sqrt(vec.map(x => x * x).sum)
      if (norm > 0) vec.map(_ / norm).toVector else vec.toVector
    }
    override def embedBatch(texts: List[String]): Task[List[Vector[Double]]] =
      Task.sequence(texts.map(embed))
  }

  private def harness(): BenchmarkHarness = {
    val index = new InMemoryVectorIndex
    BenchmarkHarness(
      embeddingProvider = StubEmbedder,
      vectorIndex = index,
      reset = () => Task { index.clear() }
    )
  }

  /**
   * Synthetic LoCoMo-shape fixture: two conversations, each with a
   * distinct topic. The evidence item asks about conversation 1's
   * topic; a correct retriever should rank conv-1 first.
   */
  private val fixture: Json = obj(
    "conversations" -> arr(
      obj("messages" -> arr(
        obj("text" -> str("User discussed python packaging with setuptools and poetry for reproducible builds.")),
        obj("text" -> str("Agent recommended poetry for new projects, setuptools for legacy."))
      )),
      obj("messages" -> arr(
        obj("text" -> str("User asked about garden compost ratios and worm bin maintenance.")),
        obj("text" -> str("Agent explained the 2:1 brown-to-green rule and weekly turning."))
      ))
    ),
    "evidenceItems" -> arr(
      obj(
        "question" -> str("What build tool did the agent recommend for python?"),
        "answer" -> str("poetry"),
        "conversations" -> arr(
          obj("messages" -> arr(
            obj("text" -> str("User discussed python packaging with setuptools and poetry for reproducible builds.")),
            obj("text" -> str("Agent recommended poetry for new projects, setuptools for legacy."))
          ))
        )
      )
    )
  )

  "BenchmarkHarness.searchByQueryEnhanced — vanilla" should {
    "behave identically to searchByQuery on a trivial fixture" in {
      val h = harness()
      for {
        _ <- h.resetCollection()
        _ <- h.embedAndIndex("a", "first document about garden compost", Map.empty)
        _ <- h.embedAndIndex("b", "second document about python poetry build tool", Map.empty)
        plain <- h.searchByQuery("poetry build tool", limit = 5)
        enhanced <- h.searchByQueryEnhanced("poetry build tool", Retrieval.vanilla, limit = 5)
      } yield {
        plain.map(_.id) shouldBe enhanced.map(_.id)
      }
    }
  }

  "BenchmarkHarness.searchByQueryEnhanced — hybrid" should {
    "lift keyword-exact matches above semantically-near but keyword-poor neighbors" in {
      val h = harness()
      val keywordHit = "Garden compost ratios and worm bin maintenance cycles"
      val semanticNeighbor = "The quick brown fox leaped over the lazy dog in the garden"
      for {
        _ <- h.resetCollection()
        _ <- h.embedAndIndex("keywordHit", keywordHit, Map.empty)
        _ <- h.embedAndIndex("semanticNeighbor", semanticNeighbor, Map.empty)
        out <- h.searchByQueryEnhanced(
          "worm bin maintenance",
          Retrieval.withHybrid(semanticWeight = 0.3),
          limit = 2
        )
      } yield {
        out.head.id shouldBe "keywordHit"
      }
    }
  }

  "BenchmarkHarness.searchByQueryEnhanced — temporal boost" should {
    "prefer a timestamp-close candidate over a distant one of equal base score" in {
      val h = harness()
      val now = 1_700_000_000_000L
      val boost = sigil.vector.TemporalBoost(
        halfLifeMs = sigil.vector.TemporalBoost.HalfLife.OneDay,
        temporalWeight = 0.5
      )
      for {
        _ <- h.resetCollection()
        _ <- h.embedAndIndex("close", "alpha signal", Map(sigil.vector.TemporalBoost.TimestampKey -> now.toString))
        _ <- h.embedAndIndex("far", "alpha signal", Map(sigil.vector.TemporalBoost.TimestampKey -> (now - 30L * sigil.vector.TemporalBoost.HalfLife.OneDay).toString))
        out <- h.searchByQueryEnhanced(
          "alpha signal",
          Retrieval.withTemporal(boost),
          limit = 2,
          referenceTimeMs = Some(now)
        )
      } yield {
        out.head.id shouldBe "close"
      }
    }
  }

  "BenchmarkHarness" should {
    "achieve perfect R@k on a trivial synthetic LoCoMo fixture" in {
      val h = harness()
      val conversations = fixture("conversations").asVector
      val evidenceItems = fixture("evidenceItems").asVector

      val convToIdx = scala.collection.mutable.Map.empty[String, Int]

      val indexSetup = for {
        _ <- h.resetCollection()
        _ <- Task.sequence(conversations.zipWithIndex.toList.flatMap { case (conv, convIdx) =>
          val messages = conv("messages").asVector
          messages.zipWithIndex.toList.map { case (msg, msgIdx) =>
            val text = msg("text").asString
            val messageId = s"conv-$convIdx-msg-$msgIdx"
            convToIdx(messageId) = convIdx
            h.embedAndIndex(messageId, text, Map(
              "kind" -> "locomo-message",
              "conversationId" -> s"conv-$convIdx",
              "messageId" -> messageId
            ))
          }
        })
      } yield ()

      indexSetup.flatMap { _ =>
        val item = evidenceItems.head
        val question = item("question").asString
        val evidenceConvs = item("conversations").asVector
        val evidenceConvIndices = evidenceConvs.flatMap { ec =>
          val ecMsgs = ec("messages").asVector.map(m => m("text").asString).mkString(" ").take(200)
          conversations.zipWithIndex.collectFirst {
            case (c, idx) if c("messages").asVector.map(m => m("text").asString).mkString(" ").take(200) == ecMsgs => idx
          }
        }.toSet

        h.searchByQuery(question, limit = 10).map { results =>
          val rankedConvIndices = results.flatMap(r => convToIdx.get(r.id)).distinct
          val topK = rankedConvIndices.take(5).toSet
          val hit = evidenceConvIndices.exists(topK.contains)

          evidenceConvIndices shouldBe Set(0)
          hit shouldBe true
        }
      }
    }

    "reset() clears prior points so per-iteration isolation holds" in {
      val h = harness()
      for {
        _ <- h.resetCollection()
        _ <- h.embedAndIndex("p1", "alpha beta gamma", Map("k" -> "v"))
        beforeReset <- h.searchByQuery("alpha", limit = 5)
        _ <- h.resetCollection()
        afterReset <- h.searchByQuery("alpha", limit = 5)
      } yield {
        beforeReset.map(_.id) should contain("p1")
        afterReset shouldBe empty
      }
    }
  }
}
