package sigil.provider.anthropic

import fabric.*
import fabric.rw.*
import lightdb.id.Id
import lightdb.time.Timestamp
import rapid.Task
import sigil.db.{Model, ModelArchitecture, ModelDefaultParameters, ModelLinks, ModelPricing, ModelTopProvider}
import spice.http.client.HttpClient
import spice.net.*

/**
 * Anthropic-specific constants, model catalog, and model discovery.
 * Parallel structure to [[sigil.provider.openai.OpenAI]] and
 * [[sigil.provider.llamacpp.LlamaCpp]].
 *
 * Anthropic's `/v1/models` returns the full list of accessible
 * models but no pricing or context-length metadata; we overlay a
 * curated table for the common ones.
 */
object Anthropic {
  val Provider: String = "anthropic"
  val ApiVersion: String = "2023-06-01"

  private case class Entry(id: String, created_at: Option[String] = None, display_name: Option[String] = None) derives RW

  private case class Metadata(ctx: Long,
                              maxOut: Long,
                              promptCostPerM: BigDecimal,
                              completionCostPerM: BigDecimal,
                              supportsThinking: Boolean = false,
                              supportsImages: Boolean = true,
                              cacheReadPerM: Option[BigDecimal] = None)

  private val Catalog: Map[String, Metadata] = Map(
    "claude-opus-4-7"     -> Metadata(ctx = 1_000_000, maxOut = 64_000, supportsThinking = true,  promptCostPerM = BigDecimal(15.00), completionCostPerM = BigDecimal(75.00), cacheReadPerM = Some(BigDecimal(1.50))),
    "claude-sonnet-4-6"   -> Metadata(ctx = 1_000_000, maxOut = 64_000, supportsThinking = true,  promptCostPerM = BigDecimal(3.00),  completionCostPerM = BigDecimal(15.00), cacheReadPerM = Some(BigDecimal(0.30))),
    "claude-haiku-4-5"    -> Metadata(ctx = 200_000,   maxOut = 32_000, supportsThinking = false, promptCostPerM = BigDecimal(0.80),  completionCostPerM = BigDecimal(4.00),  cacheReadPerM = Some(BigDecimal(0.08))),
    "claude-3-5-sonnet-latest" -> Metadata(ctx = 200_000, maxOut = 8_192, promptCostPerM = BigDecimal(3.00),  completionCostPerM = BigDecimal(15.00), cacheReadPerM = Some(BigDecimal(0.30))),
    "claude-3-5-haiku-latest"  -> Metadata(ctx = 200_000, maxOut = 8_192, promptCostPerM = BigDecimal(0.80),  completionCostPerM = BigDecimal(4.00),  cacheReadPerM = Some(BigDecimal(0.08)))
  )

  private def toModel(id: String): Model = {
    val meta = Catalog.getOrElse(id, Metadata(ctx = 200_000, maxOut = 8_192, promptCostPerM = BigDecimal(0), completionCostPerM = BigDecimal(0)))
    val modalities = if (meta.supportsImages) List("text", "image") else List("text")
    Model(
      canonicalSlug = s"$Provider/$id",
      huggingFaceId = "",
      name = id,
      description = s"Anthropic $id",
      contextLength = meta.ctx,
      architecture = ModelArchitecture(
        modality = if (meta.supportsImages) "text+image->text" else "text->text",
        inputModalities = modalities,
        outputModalities = List("text"),
        tokenizer = "Claude",
        instructType = None
      ),
      pricing = ModelPricing(
        prompt = meta.promptCostPerM,
        completion = meta.completionCostPerM,
        webSearch = Some(BigDecimal(10.00)),
        inputCacheRead = meta.cacheReadPerM
      ),
      topProvider = ModelTopProvider(
        contextLength = Some(meta.ctx).filter(_ > 0),
        maxCompletionTokens = Some(meta.maxOut).filter(_ > 0),
        isModerated = true
      ),
      perRequestLimits = None,
      supportedParameters = Set(
        "temperature", "top_p", "top_k", "max_tokens",
        "stop_sequences", "tools", "tool_choice", "thinking"
      ),
      defaultParameters = ModelDefaultParameters(),
      knowledgeCutoff = None,
      expirationDate = None,
      links = ModelLinks(details = "https://docs.anthropic.com/en/docs/about-claude/models"),
      created = Timestamp(),
      modified = Timestamp(),
      _id = Id[Model](s"$Provider/$id")
    )
  }

  def loadModels(apiKey: String, baseUrl: URL = url"https://api.anthropic.com"): Task[List[Model]] =
    HttpClient.url(baseUrl.withPath("/v1/models"))
      .header("x-api-key", apiKey)
      .header("anthropic-version", ApiVersion)
      .call[Json].map { json =>
        json("data").asVector.map(_.as[Entry]).map(e => toModel(e.id)).toList
      }

  def stripProviderPrefix(sigilModelId: String): String = {
    val prefix = s"$Provider/"
    if (sigilModelId.startsWith(prefix)) sigilModelId.drop(prefix.length) else sigilModelId
  }
}
