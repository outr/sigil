package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.AsyncTaskSpec
import sigil.vector.{InMemoryVectorIndex, VectorPoint}

/**
 * Mechanical coverage of the default in-process [[InMemoryVectorIndex]] —
 * upsert, search ordering, payload filtering, delete. The class is
 * primarily a test fixture for the Sigil-level vector wiring spec, so
 * it needs a solid floor of its own.
 */
class InMemoryVectorIndexSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {

  private def idx = new InMemoryVectorIndex

  "InMemoryVectorIndex" should {
    "return upserted points in order of cosine similarity to the query" in {
      val index = idx
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

    "honor the limit parameter" in {
      val index = idx
      for {
        _ <- index.upsertBatch((0 until 5).toList.map(i =>
               VectorPoint(s"p$i", Vector(1.0 - i * 0.1, i * 0.1), Map.empty)))
        hits <- index.search(Vector(1.0, 0.0), limit = 2)
      } yield hits should have size 2
    }

    "filter by exact-match payload keys" in {
      val index = idx
      val a = VectorPoint("a", Vector(1.0, 0.0), Map("kind" -> "memory"))
      val b = VectorPoint("b", Vector(0.9, 0.1), Map("kind" -> "summary"))
      for {
        _ <- index.upsertBatch(List(a, b))
        memOnly <- index.search(Vector(1.0, 0.0), filter = Map("kind" -> "memory"))
      } yield memOnly.map(_.id) shouldBe List("a")
    }

    "replace a point on upsert with the same id" in {
      val index = idx
      for {
        _ <- index.upsert(VectorPoint("a", Vector(1.0, 0.0), Map("v" -> "old")))
        _ <- index.upsert(VectorPoint("a", Vector(1.0, 0.0), Map("v" -> "new")))
        hits <- index.search(Vector(1.0, 0.0), limit = 5)
      } yield {
        hits should have size 1
        hits.head.payload("v") shouldBe "new"
      }
    }

    "remove a point on delete" in {
      val index = idx
      for {
        _ <- index.upsert(VectorPoint("a", Vector(1.0, 0.0), Map.empty))
        _ <- index.delete("a")
        hits <- index.search(Vector(1.0, 0.0))
      } yield hits shouldBe empty
    }
  }
}
