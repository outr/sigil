package sigil.vector

import fabric.*
import fabric.io.{JsonFormatter, JsonParser}
import rapid.{Task, Unique}
import spice.http.HttpMethod
import spice.http.client.HttpClient
import spice.http.content.StringContent
import spice.net.{ContentType, URL}

/**
 * Low-level HTTP helpers for talking to a Qdrant instance. Raw JSON
 * surface — [[QdrantVectorIndex]] composes these into the
 * [[VectorIndex]] trait.
 *
 * Qdrant's REST API uses PUT for collection creation and point upsert,
 * POST for search and delete.
 */
object QdrantOps {

  /** Verify the server is reachable. Returns `false` on any error
    * rather than propagating — callers typically fall back to an
    * in-memory index when Qdrant is unavailable in dev environments. */
  def healthCheck(baseUrl: URL): Task[Boolean] =
    HttpClient.url(baseUrl.withPath("/healthz")).get.send()
      .map(_.status.code == 200)
      .handleError(_ => Task.pure(false))

  /** Ensure a collection exists with the given vector dimensionality
    * and cosine distance. Idempotent — creating an existing collection
    * is a no-op. */
  def ensureCollection(baseUrl: URL, name: String, dimensions: Int): Task[Unit] =
    HttpClient.url(baseUrl.withPath(s"/collections/$name")).get.send().flatMap { response =>
      if (response.status.code == 200) Task.unit
      else createCollection(baseUrl, name, dimensions)
    }.handleError(_ => createCollection(baseUrl, name, dimensions))

  private def createCollection(baseUrl: URL, name: String, dimensions: Int): Task[Unit] = {
    val body = obj(
      "vectors" -> obj(
        "size" -> num(dimensions),
        "distance" -> str("Cosine")
      )
    )
    putJson(baseUrl.withPath(s"/collections/$name"), body).unit
  }

  /** Upsert a batch of points. The wait flag is set so subsequent
    * searches see the writes immediately — matches the latency
    * contract callers expect from the [[VectorIndex]] trait. */
  def upsert(baseUrl: URL, collection: String, points: List[VectorPoint]): Task[Unit] = {
    val pointsJson = points.map { p =>
      val payloadObj = Obj(p.payload.toList.map { case (k, v) => k -> str(v) }*)
      obj(
        "id" -> str(p.id),
        "vector" -> arr(p.vector.map(num)*),
        "payload" -> payloadObj
      )
    }
    putJson(
      baseUrl.withPath(s"/collections/$collection/points").withParam("wait", "true"),
      obj("points" -> arr(pointsJson*))
    ).unit
  }

  def search(baseUrl: URL,
             collection: String,
             vector: Vector[Double],
             limit: Int,
             filter: Map[String, String]): Task[List[VectorSearchResult]] = {
    val bodyFields = List(
      "vector" -> arr(vector.map(num)*),
      "limit" -> num(limit),
      "with_payload" -> bool(true)
    ) ++ (if (filter.nonEmpty) List("filter" -> filterExpr(filter)) else Nil)
    postJson(baseUrl.withPath(s"/collections/$collection/points/search"), obj(bodyFields*)).map { json =>
      json("result").asVector.map { r =>
        val id = r("id") match {
          case Str(s, _) => s
          case other     => other.toString.stripPrefix("\"").stripSuffix("\"")
        }
        val payload = r.get("payload").map(_.asObj).getOrElse(Obj.empty)
        val metadata = payload.value.toList.flatMap { case (k, v) =>
          v match {
            case Str(s, _) => Some(k -> s)
            case _         => None
          }
        }.toMap
        VectorSearchResult(id, r("score").asDouble, metadata)
      }.toList
    }.handleError { e =>
      scribe.warn(s"Qdrant search failed on $collection: ${e.getMessage}")
      Task.pure(Nil)
    }
  }

  def delete(baseUrl: URL, collection: String, ids: List[String]): Task[Unit] =
    if (ids.isEmpty) Task.unit
    else postJson(
      baseUrl.withPath(s"/collections/$collection/points/delete").withParam("wait", "true"),
      obj("points" -> arr(ids.map(str)*))
    ).unit

  /** Generate a Qdrant-compatible UUID point id. Qdrant accepts either
    * unsigned int or UUID — Sigil standardizes on UUID because the
    * upstream ids (memory ids, summary ids, event ids) are already
    * opaque strings, not integers. */
  def generateId(): String = Unique.uuid.sync()

  /** Translate a flat `Map[String, String]` filter into Qdrant's
    * `must + match` shape. */
  private def filterExpr(filter: Map[String, String]): Json = {
    val conditions = filter.toList.map { case (k, v) =>
      obj("key" -> str(k), "match" -> obj("value" -> str(v)))
    }
    obj("must" -> arr(conditions*))
  }

  private def putJson(url: URL, body: Json): Task[Json] =
    sendJson(url, HttpMethod.Put, body)

  private def postJson(url: URL, body: Json): Task[Json] =
    sendJson(url, HttpMethod.Post, body)

  private def sendJson(url: URL, method: HttpMethod, body: Json): Task[Json] =
    HttpClient.url(url)
      .method(method)
      .content(StringContent(JsonFormatter.Compact(body), ContentType.`application/json`))
      .send()
      .flatMap { response =>
        response.content match {
          case Some(c) => c.asString.map(JsonParser(_))
          case None    => Task.pure(Obj.empty)
        }
      }
}
