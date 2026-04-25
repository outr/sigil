package sigil.vector

import rapid.Task
import spice.net.URL

/**
 * [[VectorIndex]] backed by a Qdrant collection. Point ids are string
 * UUIDs (generated upstream by Sigil when it auto-indexes records)
 * and payloads are flat string maps. Distance is Cosine (set at
 * collection-creation time via [[ensureCollection]]).
 *
 * One instance is bound to exactly one collection — apps that want
 * separate namespaces (e.g. per-environment) wire separate instances.
 */
case class QdrantVectorIndex(baseUrl: URL, collection: String) extends VectorIndex {
  override def upsert(point: VectorPoint): Task[Unit] =
    QdrantOps.upsert(baseUrl, collection, List(point))

  override def upsertBatch(points: List[VectorPoint]): Task[Unit] =
    if (points.isEmpty) Task.unit
    else QdrantOps.upsert(baseUrl, collection, points)

  override def search(vector: Vector[Double],
                      limit: Int,
                      filter: Map[String, String]): Task[List[VectorSearchResult]] =
    QdrantOps.search(baseUrl, collection, vector, limit, filter)

  override def delete(id: String): Task[Unit] =
    QdrantOps.delete(baseUrl, collection, List(id))

  override def ensureCollection(dimensions: Int): Task[Unit] =
    QdrantOps.ensureCollection(baseUrl, collection, dimensions)
}
