package bench

import lightdb.id.Id
import rapid.Task
import sigil.Sigil
import sigil.db.Model
import sigil.embedding.{EmbeddingProvider, OpenAICompatibleEmbeddingProvider}
import sigil.participant.ParticipantId
import sigil.vector.{HybridSearch, LLMReranker, QdrantOps, QdrantVectorIndex, TemporalBoost, VectorIndex, VectorPoint, VectorSearchResult}
import spice.http.HttpMethod
import spice.http.client.HttpClient
import spice.net.{TLDValidation, URL, url}

/**
 * Shared scaffolding for sigil's memory benchmarks (LoCoMo,
 * LongMemEval, ConvoMem). Speaks Sigil's primitives directly
 * (`EmbeddingProvider`, `VectorIndex`).
 *
 * A harness instance owns:
 *   - one [[QdrantVectorIndex]] bound to a benchmark-scoped
 *     collection name
 *   - one [[EmbeddingProvider]] (OpenAI-compatible; default text-
 *     embedding-3-small / 1536 dims)
 *
 * Each benchmark resets its collection between runs via
 * [[resetCollection]] so runs don't cross-contaminate.
 */
case class BenchmarkHarness(embeddingProvider: EmbeddingProvider,
                            vectorIndex: VectorIndex,
                            reset: () => Task[Unit]) {

  /** Ensure the underlying index is initialized for this embedder's
    * dimensions. Idempotent. */
  def ensureCollection(): Task[Unit] =
    vectorIndex.ensureCollection(embeddingProvider.dimensions)

  /** Embed `text`, upsert a point into the index with `payload`.
    * Also stores the original text under
    * [[sigil.vector.HybridSearch.TextKey]] so the hybrid-search
    * wrapper can compute keyword overlap at query time.
    *
    * Qdrant requires point ids to be UUIDs or unsigned ints —
    * arbitrary benchmark ids (like `msg-12-session-3-turn-4`) are
    * rejected silently. We normalize the caller's id to a
    * deterministic UUID via `UUID.nameUUIDFromBytes` and stash the
    * original under [[BenchmarkHarness.OrigIdKey]] so search results
    * restore the user's id. InMemoryVectorIndex accepts either, so
    * this normalization is a no-op on that backend semantically. */
  def embedAndIndex(id: String, text: String, payload: Map[String, String]): Task[Unit] =
    embeddingProvider.embed(text).flatMap { vec =>
      val enriched = payload
        .updated(sigil.vector.HybridSearch.TextKey, text)
        .updated(BenchmarkHarness.OrigIdKey, id)
      val pointId = java.util.UUID.nameUUIDFromBytes(id.getBytes("UTF-8")).toString
      vectorIndex.upsert(VectorPoint(id = pointId, vector = vec, payload = enriched))
    }

  /** Embed + upsert a batch of items in a single round-trip. OpenAI
    * accepts up to ~2048 inputs per embeddings request; we chunk at
    * `batchSize` (default 64 to stay well under request-size limits
    * while still reducing RTT by ~50x over single-item embeds).
    *
    * Each individual text is also truncated to
    * `maxCharsPerInput` (default 20000 — conservative at ~2.4
    * chars/token to stay under text-embedding-3-small's 8192-token
    * cap on dense technical/code-heavy content) so one oversized
    * haystack item doesn't crash the whole run. */
  def embedAndIndexBatch(items: List[(String, String, Map[String, String])],
                         batchSize: Int = 64,
                         maxCharsPerInput: Int = 20000): Task[Unit] = {
    if (items.isEmpty) Task.unit
    else {
      // Drop empty / whitespace-only inputs — OpenAI embeddings
      // rejects them with "input cannot be an empty string".
      val nonEmpty = items.filter(_._2.trim.nonEmpty)
      val clipped = nonEmpty.map { case (id, text, payload) =>
        val t = if (text.length > maxCharsPerInput) text.take(maxCharsPerInput) else text
        (id, t, payload)
      }
      val chunks = clipped.grouped(batchSize).toList
      Task.sequence(chunks.map { chunk =>
        val texts = chunk.map(_._2)
        embeddingProvider.embedBatch(texts).flatMap { vectors =>
          val points = chunk.zip(vectors).map { case ((id, text, payload), vec) =>
            val enriched = payload
              .updated(sigil.vector.HybridSearch.TextKey, text)
              .updated(BenchmarkHarness.OrigIdKey, id)
            VectorPoint(
              id = java.util.UUID.nameUUIDFromBytes(id.getBytes("UTF-8")).toString,
              vector = vec,
              payload = enriched
            )
          }
          vectorIndex.upsertBatch(points)
        }
      }).unit
    }
  }

  /** Embed `query`, search the index, return raw results with the
    * caller's original ids restored from payload. */
  def searchByQuery(query: String,
                    limit: Int = 10,
                    filter: Map[String, String] = Map.empty): Task[List[VectorSearchResult]] =
    embeddingProvider.embed(query).flatMap { vec =>
      vectorIndex.search(vec, limit = limit, filter = filter).map(restoreOrigIds)
    }

  private def restoreOrigIds(results: List[VectorSearchResult]): List[VectorSearchResult] =
    results.map(r => r.payload.get(BenchmarkHarness.OrigIdKey) match {
      case Some(orig) => r.copy(id = orig)
      case None       => r
    })

  /**
   * Hybrid / temporal / LLM-rerank pipeline. Pass `Retrieval` flags
   * to enable any combination; unset flags short-circuit to the
   * vanilla vector path. Call sites use this uniform entry so a
   * single harness instance supports every benchmark variant.
   */
  def searchByQueryEnhanced(query: String,
                            retrieval: Retrieval,
                            limit: Int = 10,
                            filter: Map[String, String] = Map.empty,
                            referenceTimeMs: Option[Long] = None): Task[List[VectorSearchResult]] = {
    // 1. Base retrieval — hybrid if enabled, else vanilla cosine.
    //    Restore caller-facing ids from the OrigIdKey payload at
    //    every fork so downstream stages (temporal, rerank) operate
    //    on the user's ids rather than the Qdrant UUIDs.
    val base: Task[List[VectorSearchResult]] = retrieval.hybrid match {
      case Some(cfg) =>
        HybridSearch(vectorIndex, embeddingProvider, semanticWeight = cfg.semanticWeight)
          .search(query, limit = math.max(limit * 2, retrieval.rerank.map(_.poolSize).getOrElse(limit)), filter = filter)
          .map(restoreOrigIds)
      case None =>
        val poolSize = math.max(limit, retrieval.rerank.map(_.poolSize).getOrElse(limit))
        embeddingProvider.embed(query).flatMap { vec =>
          vectorIndex.search(vec, limit = poolSize, filter = filter).map(restoreOrigIds)
        }
    }
    // 2. Temporal boost re-rank (in-place on the candidate list).
    val temporalStep: Task[List[VectorSearchResult]] = base.map { candidates =>
      retrieval.temporal match {
        case Some(cfg) => cfg.boost.rerank(candidates, referenceTimeMs.getOrElse(System.currentTimeMillis()))
        case None      => candidates
      }
    }
    // 3. LLM reranker — requires a Sigil for the consult call.
    val reranked: Task[List[VectorSearchResult]] = retrieval.rerank match {
      case Some(cfg) =>
        temporalStep.flatMap { candidates =>
          cfg.reranker.rerank(cfg.sigil, query, candidates)
        }
      case None => temporalStep
    }
    reranked.map(_.take(limit))
  }

  /** Reset the underlying store so the next benchmark iteration starts
    * clean. For Qdrant-backed runs this deletes and recreates the
    * collection; for in-memory runs it flushes the map. */
  def resetCollection(): Task[Unit] = reset().flatMap(_ => ensureCollection())
}

/**
 * Toggles for the enhanced search pipeline. Build via the `with*`
 * helpers in [[Retrieval]] so benchmark runners only need to wire
 * whichever combination their CLI flags enabled.
 */
case class Retrieval(hybrid: Option[Retrieval.Hybrid] = None,
                     temporal: Option[Retrieval.Temporal] = None,
                     rerank: Option[Retrieval.Rerank] = None)

object Retrieval {
  case class Hybrid(semanticWeight: Double = 0.7)
  case class Temporal(boost: TemporalBoost)
  case class Rerank(reranker: LLMReranker, sigil: Sigil, poolSize: Int = 20)

  val vanilla: Retrieval = Retrieval()

  def withHybrid(semanticWeight: Double = 0.7, base: Retrieval = vanilla): Retrieval =
    base.copy(hybrid = Some(Hybrid(semanticWeight)))

  def withTemporal(boost: TemporalBoost, base: Retrieval = vanilla): Retrieval =
    base.copy(temporal = Some(Temporal(boost)))

  def withRerank(reranker: LLMReranker, sigil: Sigil, poolSize: Int = 20, base: Retrieval = vanilla): Retrieval =
    base.copy(rerank = Some(Rerank(reranker, sigil, poolSize)))
}

object BenchmarkHarness {

  /** Payload key under which [[embedAndIndex]] stashes the caller's
    * original id so search results can present user-friendly ids
    * (Qdrant rejects non-UUID point ids). */
  val OrigIdKey: String = "_origId"

  /** Construct a harness from env / args:
    *   - `SIGIL_QDRANT_URL` (default `http://localhost:6333`)
    *   - `OPENAI_API_KEY` (required)
    *   - `SIGIL_EMBEDDING_MODEL` (default `text-embedding-3-small`)
    *   - `SIGIL_EMBEDDING_DIMENSIONS` (default `1536`)
    *
    * Terminates with a clear error message when `OPENAI_API_KEY` is
    * missing; the benchmarks are unusable without it.
    */
  def fromEnv(collection: String): BenchmarkHarness = {
    val qdrantUrl: URL = qdrantUrlFromEnv
    val openaiKey = Option(System.getenv("OPENAI_API_KEY")).filter(_.nonEmpty).getOrElse {
      System.err.println("ERROR: OPENAI_API_KEY not set — benchmarks require an OpenAI-compatible embedding endpoint")
      sys.exit(1)
    }
    val model = Option(System.getenv("SIGIL_EMBEDDING_MODEL")).filter(_.nonEmpty).getOrElse("text-embedding-3-small")
    val dims = Option(System.getenv("SIGIL_EMBEDDING_DIMENSIONS")).flatMap(_.toIntOption).getOrElse(1536)
    val openaiBaseUrl: URL = Option(System.getenv("OPENAI_BASE_URL"))
      .filter(_.nonEmpty)
      .flatMap(s => URL.get(s, tldValidation = TLDValidation.Off).toOption)
      .getOrElse(url"https://api.openai.com")

    val embedder = OpenAICompatibleEmbeddingProvider(
      apiKey = openaiKey,
      baseUrl = openaiBaseUrl,
      model = model,
      dimensions = dims
    )
    val vectorIndex = QdrantVectorIndex(qdrantUrl, collection)
    val reset: () => Task[Unit] = () => Task {
      try {
        HttpClient.url(qdrantUrl.withPath(s"/collections/$collection"))
          .method(HttpMethod.Delete).send().sync()
      } catch { case _: Exception => () }
    }
    BenchmarkHarness(embedder, vectorIndex, reset)
  }

  def qdrantUrlFromEnv: URL =
    Option(System.getenv("SIGIL_QDRANT_URL"))
      .filter(_.nonEmpty)
      .flatMap(s => URL.get(s, tldValidation = TLDValidation.Off).toOption)
      .getOrElse(url"http://localhost:6333")

  /** Sanity-check that Qdrant is reachable; terminates with a clear
    * error otherwise. Cheap to call at benchmark start. */
  def ensureQdrantReachable(qdrantUrl: URL): Unit = {
    try QdrantOps.healthCheck(qdrantUrl).sync()
    catch {
      case e: Exception =>
        System.err.println(s"ERROR: Qdrant not reachable at $qdrantUrl — ${e.getMessage}")
        sys.exit(1)
    }
  }
}
