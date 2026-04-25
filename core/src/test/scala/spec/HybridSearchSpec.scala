package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.embedding.EmbeddingProvider
import sigil.vector.{HybridSearch, InMemoryVectorIndex, VectorPoint}

/**
 * Mechanical + behavioral coverage for [[HybridSearch]]. Uses a
 * deterministic stub embedder so the test isn't at the mercy of a
 * real model.
 *
 * Covers:
 *   - Jaccard token overlap + tokenization (stopwords filtered)
 *   - end-to-end hybrid search ordering — a keyword-perfect hit beats
 *     a semantically-similar but keyword-poor candidate at `α = 0.3`
 *   - filter pass-through: payload filters constrain candidates
 *     before rescoring.
 */
class HybridSearchSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {

  private object StubEmbedder extends EmbeddingProvider {
    override def dimensions: Int = 16
    override def embed(text: String): Task[Vector[Double]] = Task {
      val tokens = HybridSearch.tokenize(text)
      val vec = Array.fill(16)(0.0)
      tokens.foreach { t =>
        val idx = math.abs(t.hashCode) % 16
        vec(idx) += 1.0
      }
      val norm = math.sqrt(vec.map(x => x * x).sum)
      if (norm > 0) vec.map(_ / norm).toVector else vec.toVector
    }
    override def embedBatch(texts: List[String]): Task[List[Vector[Double]]] =
      Task.sequence(texts.map(embed))
  }

  "HybridSearch.tokenize" should {
    "drop stopwords and lowercase" in {
      HybridSearch.tokenize("The Quick Brown Fox") shouldBe Set("quick", "brown", "fox")
    }
    "handle empty input" in {
      HybridSearch.tokenize("") shouldBe Set.empty
      HybridSearch.tokenize(null) shouldBe Set.empty
    }
  }

  "HybridSearch.jaccard" should {
    "return 1.0 for identical token sets" in {
      HybridSearch.jaccard(Set("a", "b"), Set("a", "b")) shouldBe 1.0
    }
    "return 0.0 for disjoint token sets" in {
      HybridSearch.jaccard(Set("a"), Set("b")) shouldBe 0.0
    }
    "compute intersection / union correctly" in {
      HybridSearch.jaccard(Set("a", "b", "c"), Set("b", "c", "d")) shouldBe (2.0 / 4.0)
    }
  }

  "HybridSearch" should {
    "re-rank a keyword-perfect candidate ahead of a semantically-close but keyword-poor one" in {
      val index = new InMemoryVectorIndex
      val hybrid = HybridSearch(index, StubEmbedder, semanticWeight = 0.3)

      val keywordMatch = "Garden compost ratios and worm bin maintenance"
      val semanticNeighbor = "The quick brown fox leaped over the lazy dog"

      for {
        vec1 <- StubEmbedder.embed(keywordMatch)
        vec2 <- StubEmbedder.embed(semanticNeighbor)
        _ <- index.upsert(VectorPoint("keyword", vec1, Map(HybridSearch.TextKey -> keywordMatch)))
        _ <- index.upsert(VectorPoint("semantic", vec2, Map(HybridSearch.TextKey -> semanticNeighbor)))
        hits <- hybrid.search("worm bin maintenance tips", limit = 2)
      } yield {
        hits.head.id shouldBe "keyword"
      }
    }

    "honor payload filters (constraints applied to candidates)" in {
      val index = new InMemoryVectorIndex
      val hybrid = HybridSearch(index, StubEmbedder)
      for {
        v1 <- StubEmbedder.embed("alpha beta gamma")
        v2 <- StubEmbedder.embed("alpha beta gamma")
        _ <- index.upsert(VectorPoint("p1", v1, Map("kind" -> "a", HybridSearch.TextKey -> "alpha beta gamma")))
        _ <- index.upsert(VectorPoint("p2", v2, Map("kind" -> "b", HybridSearch.TextKey -> "alpha beta gamma")))
        hits <- hybrid.search("alpha", limit = 5, filter = Map("kind" -> "a"))
      } yield {
        hits.map(_.id) shouldBe List("p1")
      }
    }

    "short-circuit keyword re-ranking when query has no tokens" in {
      val index = new InMemoryVectorIndex
      val hybrid = HybridSearch(index, StubEmbedder)
      for {
        vec <- StubEmbedder.embed("alpha beta")
        _ <- index.upsert(VectorPoint("p1", vec, Map(HybridSearch.TextKey -> "alpha beta")))
        hits <- hybrid.search("", limit = 5)
      } yield {
        hits.map(_.id) shouldBe List("p1")
      }
    }
  }
}
