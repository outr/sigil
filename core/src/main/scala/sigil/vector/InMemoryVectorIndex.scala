package sigil.vector

import rapid.Task

import java.util.concurrent.ConcurrentHashMap
import scala.jdk.CollectionConverters.*

/**
 * In-process [[VectorIndex]] backed by a `ConcurrentHashMap`. Useful
 * for tests and small apps that don't need a real vector database.
 * Cosine similarity is computed on every `search` call — fine for
 * hundreds of points, not for millions.
 */
class InMemoryVectorIndex extends VectorIndex {
  private val points = new ConcurrentHashMap[String, VectorPoint]()

  override def upsert(point: VectorPoint): Task[Unit] = Task {
    points.put(point.id, point)
    ()
  }

  override def upsertBatch(points: List[VectorPoint]): Task[Unit] = Task {
    points.foreach(p => this.points.put(p.id, p))
  }

  override def search(vector: Vector[Double],
                      limit: Int,
                      filter: Map[String, String]): Task[List[VectorSearchResult]] = Task {
    val qNorm = norm(vector)
    val candidates = points.values().iterator().asScala
      .filter(p => filter.forall { case (k, v) => p.payload.get(k).contains(v) })
      .map { p =>
        val sim = if (qNorm == 0.0 || norm(p.vector) == 0.0) 0.0
                  else cosine(vector, p.vector, qNorm, norm(p.vector))
        VectorSearchResult(p.id, sim, p.payload)
      }
      .toList
    candidates.sortBy(-_.score).take(limit)
  }

  override def delete(id: String): Task[Unit] = Task {
    points.remove(id)
    ()
  }

  override def ensureCollection(dimensions: Int): Task[Unit] = Task.unit

  /** Drop every stored point. Useful for tests and benchmark harness
    * resets (between per-iteration fixtures). */
  def clear(): Unit = points.clear()

  private def norm(v: Vector[Double]): Double = math.sqrt(v.foldLeft(0.0)((acc, x) => acc + x * x))

  private def cosine(a: Vector[Double], b: Vector[Double], na: Double, nb: Double): Double = {
    val n = math.min(a.length, b.length)
    var dot = 0.0
    var i = 0
    while (i < n) { dot += a(i) * b(i); i += 1 }
    dot / (na * nb)
  }
}
