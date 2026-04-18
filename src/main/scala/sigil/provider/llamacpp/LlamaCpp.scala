package sigil.provider.llamacpp

import fabric.*
import fabric.filter.SnakeToCamelFilter
import fabric.rw.*
import lightdb.id.Id
import lightdb.time.Timestamp
import rapid.Task
import sigil.db.{Model, ModelArchitecture, ModelDefaultParameters, ModelLinks, ModelPricing, ModelTopProvider}
import spice.http.client.HttpClient
import spice.net.*

/**
 * Loads models from a llama.cpp server's OpenAI-compatible `/v1/models` endpoint
 * and maps them into sigil [[Model]] instances.
 *
 * llama.cpp models are transient — tied to whatever the server currently has
 * loaded — so they're not persisted to the catalog. Callers fetch them on demand.
 *
 * llama.cpp exposes minimal metadata compared to OpenRouter — parameter count,
 * training context length, embedding dim. Fields that don't apply to a locally
 * served model (pricing, knowledge cutoff, expiration) are filled with neutral
 * defaults (zero pricing, None).
 */
object LlamaCpp {
  val Provider: String = "llamacpp"

  /**
   * Parsed entry from the `/v1/models` response (camelCased).
   */
  case class Entry(id: String, created: Option[Long] = None, ownedBy: Option[String] = None, meta: Option[Meta] = None) derives RW

  /**
   * Model metadata reported by llama.cpp under `meta`.
   */
  case class Meta(nParams: Option[Long] = None,
                  nCtxTrain: Option[Long] = None,
                  nEmbd: Option[Int] = None,
                  nVocab: Option[Int] = None,
                  size: Option[Long] = None)
    derives RW

  /**
   * Convert a llama.cpp entry into a sigil [[Model]].
   *
   * The sigil id is `llamacpp/<model-name>` where model-name is derived from
   * the llama.cpp id (basename, `.gguf` extension stripped, lowercased).
   */
  def toModel(entry: Entry): Model = {
    val name = modelNameFromId(entry.id)
    val id = Id[Model](s"$Provider/${name.toLowerCase}")
    val contextLength = entry.meta.flatMap(_.nCtxTrain).getOrElse(0L)

    Model(
      canonicalSlug = s"$Provider/$name",
      huggingFaceId = "",
      name = name,
      description = describe(entry),
      contextLength = contextLength,
      architecture = ModelArchitecture(
        modality = "text->text",
        inputModalities = List("text"),
        outputModalities = List("text"),
        tokenizer = "Unknown",
        instructType = None
      ),
      pricing = ModelPricing(
        prompt = BigDecimal(0),
        completion = BigDecimal(0),
        webSearch = None,
        inputCacheRead = None
      ),
      topProvider = ModelTopProvider(
        contextLength = Some(contextLength).filter(_ > 0),
        maxCompletionTokens = None,
        isModerated = false
      ),
      perRequestLimits = None,
      supportedParameters = Set(
        "temperature",
        "max_tokens",
        "top_p",
        "top_k",
        "stop",
        "tools",
        "tool_choice"
      ),
      defaultParameters = ModelDefaultParameters(),
      knowledgeCutoff = None,
      expirationDate = None,
      links = ModelLinks(details = ""),
      created = Timestamp(),
      modified = Timestamp(),
      _id = id
    )
  }

  /**
   * Fetch and map currently-loaded models from a llama.cpp server.
   */
  def loadModels(baseUrl: URL = url"http://localhost:8081"): Task[List[Model]] =
    HttpClient.url(baseUrl.withPath("/v1/models")).call[Json].map { json =>
      val normalized = json.filterOne(SnakeToCamelFilter)
      val data = normalized("data").asVector
      data.map(_.as[Entry]).map(toModel).toList
    }

  private def modelNameFromId(id: String): String = {
    val basename = id.split('/').last.split('\\').last
    if (basename.toLowerCase.endsWith(".gguf")) basename.dropRight(5) else basename
  }

  private def describe(entry: Entry): String = {
    val params = entry.meta.flatMap(_.nParams).map(p => s"${humanParams(p)} params")
    val ctx = entry.meta.flatMap(_.nCtxTrain).map(c => s"${c.toString} ctx")
    val parts = params.toList ++ ctx.toList
    if (parts.isEmpty) s"llama.cpp model ${entry.id}"
    else s"llama.cpp model (${parts.mkString(", ")})"
  }

  private def humanParams(n: Long): String =
    if (n >= 1_000_000_000L) f"${n / 1e9}%.1fB"
    else if (n >= 1_000_000L) f"${n / 1e6}%.1fM"
    else n.toString
}
