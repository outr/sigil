package sigil.provider.deepseek

import lightdb.id.Id
import lightdb.time.Timestamp
import sigil.db.{Model, ModelArchitecture, ModelDefaultParameters, ModelLinks, ModelPricing, ModelTopProvider}

/**
 * DeepSeek uses an OpenAI-chat-completions-compatible API at
 * `https://api.deepseek.com/v1/chat/completions`. Models include
 * `deepseek-chat` (general) and `deepseek-reasoner` (reasoning
 * series).
 *
 * No dynamic model discovery — DeepSeek's `/v1/models` endpoint
 * returns a superset of what's actually routable, so we just hand
 * back a curated catalog.
 */
object DeepSeek {
  val Provider: String = "deepseek"

  private case class Metadata(ctx: Long,
                              maxOut: Long,
                              promptCostPerM: BigDecimal,
                              completionCostPerM: BigDecimal,
                              supportsThinking: Boolean = false,
                              cacheReadPerM: Option[BigDecimal] = None)

  private val Catalog: Map[String, Metadata] = Map(
    "deepseek-chat"     -> Metadata(ctx = 128_000, maxOut = 8_192,  promptCostPerM = BigDecimal(0.27), completionCostPerM = BigDecimal(1.10), cacheReadPerM = Some(BigDecimal(0.07))),
    "deepseek-reasoner" -> Metadata(ctx = 128_000, maxOut = 32_000, promptCostPerM = BigDecimal(0.55), completionCostPerM = BigDecimal(2.19), supportsThinking = true, cacheReadPerM = Some(BigDecimal(0.14)))
  )

  def models: List[Model] = Catalog.keys.toList.sorted.map(toModel)

  private def toModel(id: String): Model = {
    val meta = Catalog(id)
    Model(
      canonicalSlug = s"$Provider/$id",
      huggingFaceId = "",
      name = id,
      description = s"DeepSeek $id",
      contextLength = meta.ctx,
      architecture = ModelArchitecture(
        modality = "text->text",
        inputModalities = List("text"),
        outputModalities = List("text"),
        tokenizer = "DeepSeek",
        instructType = None
      ),
      pricing = ModelPricing(
        prompt = meta.promptCostPerM,
        completion = meta.completionCostPerM,
        webSearch = None,
        inputCacheRead = meta.cacheReadPerM
      ),
      topProvider = ModelTopProvider(
        contextLength = Some(meta.ctx).filter(_ > 0),
        maxCompletionTokens = Some(meta.maxOut).filter(_ > 0),
        isModerated = false
      ),
      perRequestLimits = None,
      supportedParameters = Set(
        "temperature", "top_p", "max_tokens", "stop",
        "tools", "tool_choice", "response_format"
      ),
      defaultParameters = ModelDefaultParameters(),
      knowledgeCutoff = None,
      expirationDate = None,
      links = ModelLinks(details = "https://api-docs.deepseek.com/"),
      created = Timestamp(),
      modified = Timestamp(),
      _id = Id[Model](s"$Provider/$id")
    )
  }

  def stripProviderPrefix(sigilModelId: String): String = {
    val prefix = s"$Provider/"
    if (sigilModelId.startsWith(prefix)) sigilModelId.drop(prefix.length) else sigilModelId
  }
}
