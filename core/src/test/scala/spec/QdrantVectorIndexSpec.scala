package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.AsyncTaskSpec
import sigil.vector.{QdrantOps, QdrantVectorIndex, VectorPoint}

/**
 * Live round-trip against a real Qdrant instance. Each run uses a
 * fresh uniquely-named collection (sigil-test-<unique>) and DELETES
 * it at the end — so pointing at a shared Qdrant is safe as long as
 * no existing collection name collides with `sigil-test-*`.
 *
 * Skipped cleanly when `SIGIL_QDRANT_URL` is unset.
 */
class QdrantVectorIndexSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {

  override def run(testName: Option[String], args: org.scalatest.Args): org.scalatest.Status =
    QdrantLiveSupport.runGated(this, testName, args) {
      super.run(testName, args)
    }

  "QdrantVectorIndex" should {
    "ensure-collection / upsert / search / delete round-trip" in {
      val baseUrl = QdrantLiveSupport.baseUrl.get
      val collection = s"sigil-test-${rapid.Unique()}"
      val index = QdrantVectorIndex(baseUrl, collection)
      val dims = 8
      val a = VectorPoint(id = java.util.UUID.randomUUID().toString, vector = Vector(1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0), payload = Map("kind" -> "x"))
      val b = VectorPoint(id = java.util.UUID.randomUUID().toString, vector = Vector(0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0), payload = Map("kind" -> "y"))
      val run = for {
        _ <- index.ensureCollection(dims)
        _ <- index.upsertBatch(List(a, b))
        hits <- index.search(Vector(1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0), limit = 5)
        filtered <- index.search(Vector(0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0), limit = 5, filter = Map("kind" -> "y"))
        _ <- index.delete(a.id)
        afterDelete <- index.search(Vector(1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0), limit = 5)
      } yield (hits, filtered, afterDelete)
      // Always drop the ephemeral collection, success or failure.
      val cleanup: rapid.Task[Unit] = rapid.Task {
        scala.util.Try(QdrantOps.delete(baseUrl, collection, Nil).sync())
      }.flatMap { _ =>
        spice.http.client.HttpClient.url(baseUrl.withPath(s"/collections/$collection"))
          .method(spice.http.HttpMethod.Delete).send().unit.handleError(_ => rapid.Task.unit)
      }
      run.attempt.flatMap { tried =>
        cleanup.map { _ =>
          val (hits, filtered, afterDelete) = tried.get  // re-throws on failure
          hits.map(_.id) should contain(a.id)
          filtered.map(_.id) shouldBe List(b.id)
          afterDelete.map(_.id) should not contain a.id
        }
      }
    }
  }
}
