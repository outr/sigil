package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.AsyncTaskSpec
import sigil.vector.{InMemoryVectorIndex, VectorPoint}

/**
 * Targeted coverage of [[InMemoryVectorIndex]] — verifies cosine
 * similarity ordering and payload filtering, the two pieces of real
 * logic in the impl. Upsert / delete / limit-respecting are
 * ConcurrentHashMap + List.take behavior and aren't tested here.
 */
class InMemoryVectorIndexSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {

  "InMemoryVectorIndex" should {
    "return upserted points in order of cosine similarity to the query" in {
      val index = new InMemoryVectorIndex
      val a = VectorPoint("a", Vector(1.0, 0.0), Map.empty)
      val b = VectorPoint("b", Vector(0.0, 1.0), Map.empty)
      val c = VectorPoint("c", Vector(0.9, 0.1), Map.empty)
      for {
        _ <- index.upsertBatch(List(a, b, c))
        hits <- index.search(Vector(1.0, 0.0), limit = 3)
      } yield {
        hits.map(_.id) shouldBe List("a", "c", "b")
        hits.head.score shouldBe 1.0 +- 1e-9
      }
    }

    "filter by exact-match payload keys" in {
      val index = new InMemoryVectorIndex
      val a = VectorPoint("a", Vector(1.0, 0.0), Map("kind" -> "memory"))
      val b = VectorPoint("b", Vector(0.9, 0.1), Map("kind" -> "summary"))
      for {
        _ <- index.upsertBatch(List(a, b))
        memOnly <- index.search(Vector(1.0, 0.0), filter = Map("kind" -> "memory"))
      } yield memOnly.map(_.id) shouldBe List("a")
    }
  }
}
