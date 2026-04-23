package sigil.provider.google

import lightdb.id.Id
import lightdb.time.Timestamp
import sigil.db.{Model, ModelArchitecture, ModelDefaultParameters, ModelLinks, ModelPricing, ModelTopProvider}

/**
 * Google / Gemini provider constants. Uses
 * `generativelanguage.googleapis.com/v1beta` endpoints. Models are
 * a curated catalog (no sigil-relevant metadata in the /v1beta/models
 * response beyond IDs).
 */
object Google {
  val Provider: String = "google"

  private case class Metadata(ctx: Long,
                              maxOut: Long,
                              promptCostPerM: BigDecimal,
                              completionCostPerM: BigDecimal,
                              supportsImages: Boolean = true,
                              supportsThinking: Boolean = false)

  private val Catalog: Map[String, Metadata] = Map(
    "gemini-2.5-pro"      -> Metadata(ctx = 1_000_000, maxOut = 8_192, supportsThinking = true, promptCostPerM = BigDecimal(2.50), completionCostPerM = BigDecimal(15.00)),
    "gemini-2.5-flash"    -> Metadata(ctx = 1_000_000, maxOut = 8_192, supportsThinking = true, promptCostPerM = BigDecimal(0.30), completionCostPerM = BigDecimal(2.50)),
    "gemini-2.5-flash-lite" -> Metadata(ctx = 1_000_000, maxOut = 8_192, promptCostPerM = BigDecimal(0.10), completionCostPerM = BigDecimal(0.40)),
    "gemini-2.0-flash"    -> Metadata(ctx = 1_048_576, maxOut = 8_192, promptCostPerM = BigDecimal(0.10), completionCostPerM = BigDecimal(0.40)),
    "gemini-1.5-pro"      -> Metadata(ctx = 2_097_152, maxOut = 8_192, promptCostPerM = BigDecimal(1.25), completionCostPerM = BigDecimal(5.00)),
    "gemini-1.5-flash"    -> Metadata(ctx = 1_048_576, maxOut = 8_192, promptCostPerM = BigDecimal(0.075), completionCostPerM = BigDecimal(0.30))
  )

  def models: List[Model] = Catalog.keys.toList.sorted.map(toModel)

  private def toModel(id: String): Model = {
    val meta = Catalog(id)
    val modalities = if (meta.supportsImages) List("text", "image") else List("text")
    Model(
      canonicalSlug = s"$Provider/$id",
      huggingFaceId = "",
      name = id,
      description = s"Google $id",
      contextLength = meta.ctx,
      architecture = ModelArchitecture(
        modality = if (meta.supportsImages) "text+image->text" else "text->text",
        inputModalities = modalities,
        outputModalities = List("text"),
        tokenizer = "Gemini",
        instructType = None
      ),
      pricing = ModelPricing(
        prompt = meta.promptCostPerM,
        completion = meta.completionCostPerM,
        webSearch = Some(BigDecimal(35.00)),  // per 1000 grounding queries
        inputCacheRead = None
      ),
      topProvider = ModelTopProvider(
        contextLength = Some(meta.ctx).filter(_ > 0),
        maxCompletionTokens = Some(meta.maxOut).filter(_ > 0),
        isModerated = true
      ),
      perRequestLimits = None,
      supportedParameters = Set(
        "temperature", "top_p", "top_k", "max_output_tokens",
        "stop_sequences", "tools", "tool_config", "thinking_config"
      ),
      defaultParameters = ModelDefaultParameters(),
      knowledgeCutoff = None,
      expirationDate = None,
      links = ModelLinks(details = "https://ai.google.dev/gemini-api/docs/models"),
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
