package sigil.provider.openai

import fabric.*
import fabric.rw.*
import lightdb.id.Id
import lightdb.time.Timestamp
import rapid.Task
import sigil.db.{Model, ModelArchitecture, ModelDefaultParameters, ModelLinks, ModelPricing, ModelTopProvider}
import spice.http.client.HttpClient
import spice.net.*

/**
 * OpenAI-specific constants, model catalog, and model discovery.
 * Parallel structure to [[sigil.provider.llamacpp.LlamaCpp]].
 *
 * OpenAI's `/v1/models` returns ids but no pricing or context metadata —
 * those come from documentation. So we hit `/v1/models` to discover
 * which model ids the account can access, then overlay a curated
 * metadata table for each known id. Unknown ids get defaulted metadata.
 */
object OpenAI {
  val Provider: String = "openai"

  /** A raw `/v1/models` list entry. */
  private case class Entry(id: String, created: Option[Long] = None, ownedBy: Option[String] = None) derives RW

  /**
   * Curated metadata for known OpenAI model families. Fields not
   * reported by `/v1/models`. Kept as a plain map so additions are
   * cheap and the defaults are explicit.
   */
  private val Catalog: Map[String, Metadata] = Map(
    // GPT-5.4 series — current flagship
    "gpt-5.4"        -> Metadata(ctx = 1_050_000, maxOut = 32_768, supportsImages = true, promptCostPerM = BigDecimal(2.50),  completionCostPerM = BigDecimal(15.00), webSearchCostPerK = Some(BigDecimal(10.00))),
    "gpt-5.4-pro"    -> Metadata(ctx = 1_050_000, maxOut = 32_768, supportsImages = true, promptCostPerM = BigDecimal(30.00), completionCostPerM = BigDecimal(180.00), webSearchCostPerK = Some(BigDecimal(10.00))),
    "gpt-5.4-mini"   -> Metadata(ctx = 400_000,   maxOut = 32_768, supportsImages = true, promptCostPerM = BigDecimal(0.75),  completionCostPerM = BigDecimal(4.50),  webSearchCostPerK = Some(BigDecimal(10.00))),
    "gpt-5.4-nano"   -> Metadata(ctx = 400_000,   maxOut = 32_768, supportsImages = true, promptCostPerM = BigDecimal(0.20),  completionCostPerM = BigDecimal(1.25)),

    // GPT-5 series
    "gpt-5"          -> Metadata(ctx = 200_000, maxOut = 32_768, supportsImages = true, promptCostPerM = BigDecimal(1.25),  completionCostPerM = BigDecimal(10.00), webSearchCostPerK = Some(BigDecimal(10.00))),
    "gpt-5-mini"     -> Metadata(ctx = 200_000, maxOut = 32_768, supportsImages = true, promptCostPerM = BigDecimal(0.25),  completionCostPerM = BigDecimal(2.00)),
    "gpt-5-nano"     -> Metadata(ctx = 200_000, maxOut = 32_768, supportsImages = true, promptCostPerM = BigDecimal(0.05),  completionCostPerM = BigDecimal(0.40)),

    // GPT-4.x classic chat-completions family (fallback — reachable via Responses too)
    "gpt-4o"         -> Metadata(ctx = 128_000, maxOut = 16_384, supportsImages = true, promptCostPerM = BigDecimal(2.50), completionCostPerM = BigDecimal(10.00)),
    "gpt-4o-mini"    -> Metadata(ctx = 128_000, maxOut = 16_384, supportsImages = true, promptCostPerM = BigDecimal(0.15), completionCostPerM = BigDecimal(0.60))
  )

  private case class Metadata(ctx: Long,
                              maxOut: Long,
                              supportsImages: Boolean = false,
                              promptCostPerM: BigDecimal = BigDecimal(0),
                              completionCostPerM: BigDecimal = BigDecimal(0),
                              webSearchCostPerK: Option[BigDecimal] = None)

  /** Build a sigil Model from an OpenAI model id + (optional) curated
    * metadata. Always uses `openai/<id>` as the sigil id. */
  private def toModel(openAiId: String): Model = {
    val meta = Catalog.getOrElse(openAiId, Metadata(ctx = 0, maxOut = 0))
    val id = Id[Model](s"$Provider/$openAiId")
    val modalities = if (meta.supportsImages) List("text", "image") else List("text")
    Model(
      canonicalSlug = s"$Provider/$openAiId",
      huggingFaceId = "",
      name = openAiId,
      description = s"OpenAI $openAiId",
      contextLength = meta.ctx,
      architecture = ModelArchitecture(
        modality = if (meta.supportsImages) "text+image->text" else "text->text",
        inputModalities = modalities,
        outputModalities = List("text"),
        tokenizer = "o200k_base",
        instructType = None
      ),
      pricing = ModelPricing(
        prompt = meta.promptCostPerM,
        completion = meta.completionCostPerM,
        webSearch = meta.webSearchCostPerK,
        inputCacheRead = None
      ),
      topProvider = ModelTopProvider(
        contextLength = Some(meta.ctx).filter(_ > 0),
        maxCompletionTokens = Some(meta.maxOut).filter(_ > 0),
        isModerated = true
      ),
      perRequestLimits = None,
      supportedParameters = Set(
        "temperature", "top_p", "max_output_tokens", "stop",
        "tools", "tool_choice", "response_format", "reasoning"
      ),
      defaultParameters = ModelDefaultParameters(),
      knowledgeCutoff = None,
      expirationDate = None,
      links = ModelLinks(details = "https://platform.openai.com/docs/models"),
      created = Timestamp(),
      modified = Timestamp(),
      _id = id
    )
  }

  /** Fetch the set of models the API key can access from `/v1/models`
    * and map to sigil Models. Requires a valid key. */
  def loadModels(apiKey: String, baseUrl: URL = url"https://api.openai.com"): Task[List[Model]] =
    HttpClient.url(baseUrl.withPath("/v1/models"))
      .header("Authorization", s"Bearer $apiKey")
      .call[Json].map { json =>
        val data = json("data").asVector
        data.map(_.as[Entry]).map(e => toModel(e.id)).toList
      }

  /** Strip the `openai/` provider prefix from a sigil model id so the
    * wire request carries the bare OpenAI id. */
  def stripProviderPrefix(sigilModelId: String): String = {
    val prefix = s"$Provider/"
    if (sigilModelId.startsWith(prefix)) sigilModelId.drop(prefix.length) else sigilModelId
  }
}
