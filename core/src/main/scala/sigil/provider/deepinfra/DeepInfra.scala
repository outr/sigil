package sigil.provider.deepinfra

import fabric.*
import fabric.rw.*
import lightdb.id.Id
import lightdb.time.Timestamp
import rapid.{Task, logger}
import sigil.Sigil
import sigil.db.{Model, ModelArchitecture, ModelDefaultParameters, ModelLinks, ModelPricing, ModelTopProvider}
import spice.http.client.HttpClient
import spice.net.*

/**
 * DeepInfra-specific constants and catalog loader. DeepInfra exposes an
 * OpenAI-compatible chat-completions endpoint at
 * `https://api.deepinfra.com/v1/openai/chat/completions` and hosts the
 * Kimi family (`moonshotai/Kimi-K2.5`, `moonshotai/Kimi-K2.6`) plus
 * other open-weight models. Runs on vLLM/SGLang upstream.
 *
 * Sigil [[sigil.db.Model]] ids combine namespace + model name:
 * `Model.id("deepinfra", "moonshotai/Kimi-K2.5")` yields
 * `deepinfra/moonshotai/kimi-k2.5`. The wire strips the `deepinfra/`
 * prefix and sends `moonshotai/kimi-k2.5` as the `model` field.
 *
 * The catalog is loaded from DeepInfra's public `/models/list` endpoint —
 * no auth required for listing. See [[refreshModels]] and [[loadModels]].
 * Pricing, context length, and tag-derived modality flow into the cached
 * [[Model]] so the framework's cost pipeline (`ConversationCostUpdated`,
 * `cumulativeCost`) attributes spend correctly. Sigil bug #162.
 */
object DeepInfra {
  val Provider: String = "deepinfra"

  /** DeepInfra `/models/list` row. Names mirror the wire field names
    * verbatim so fabric's auto-derived RW handles the mapping. */
  case class Entry(modelName: String,
                   `type`: Option[String] = None,
                   description: Option[String] = None,
                   tags: List[String] = Nil,
                   pricing: Option[Pricing] = None,
                   maxTokens: Option[Long] = None,
                   deprecated: Option[Long] = None,
                   quantization: Option[String] = None) derives RW

  /** DeepInfra `pricing` sub-object. Per-token rates are in CENTS
    * (e.g. `cents_per_input_token: 4.5e-05` = $0.45 per 1M tokens).
    * `rate_per_input_token_cached` is a FRACTION of the input rate
    * (e.g. `0.155` means cache reads cost 15.5% of normal input). */
  case class Pricing(`type`: Option[String] = None,
                     centsPerInputToken: Option[BigDecimal] = None,
                     centsPerOutputToken: Option[BigDecimal] = None,
                     ratePerInputTokenCached: Option[BigDecimal] = None,
                     ratePerInputTokenCacheWrite: Option[BigDecimal] = None) derives RW

  def stripProviderPrefix(sigilModelId: String): String = {
    val prefix = s"$Provider/"
    if (sigilModelId.startsWith(prefix)) sigilModelId.drop(prefix.length) else sigilModelId
  }

  /** Modalities derived from a DeepInfra entry's `tags` and `type` —
    * `multimodal` tag advertises image input, `vision` likewise. The
    * default for `text-generation` rows is text→text. */
  private def deriveModalities(entry: Entry): (List[String], List[String]) = {
    val isMultimodal = entry.tags.exists(t => t.equalsIgnoreCase("multimodal") || t.equalsIgnoreCase("vision"))
    val inputs = if (isMultimodal) List("text", "image") else List("text")
    (inputs, List("text"))
  }

  /** Translate a DeepInfra catalog row into a Sigil [[Model]]. Pricing
    * cents → USD ÷ 100 → per-token rates. Cached-input cost is the
    * fractional rate times the input rate (DeepInfra reports a ratio,
    * not an absolute number). Deprecated rows still translate; callers
    * filter via [[Entry.deprecated]] when relevant. */
  def toModel(entry: Entry): Model = {
    val canonical = s"$Provider/${entry.modelName}"
    val now = Timestamp()
    val (inputModalities, outputModalities) = deriveModalities(entry)

    val promptPerToken: BigDecimal =
      entry.pricing.flatMap(_.centsPerInputToken).map(_ / 100).getOrElse(BigDecimal(0))
    val completionPerToken: BigDecimal =
      entry.pricing.flatMap(_.centsPerOutputToken).map(_ / 100).getOrElse(BigDecimal(0))
    val cachedReadPerToken: Option[BigDecimal] =
      for {
        pricing <- entry.pricing
        cents   <- pricing.centsPerInputToken
        rate    <- pricing.ratePerInputTokenCached
      } yield (cents * rate) / 100

    val contextLength = entry.maxTokens.getOrElse(0L)
    val supportedParameters =
      if (entry.tags.exists(_.equalsIgnoreCase("tools")))
        Set("temperature", "max_tokens", "top_p", "stop", "tools", "tool_choice")
      else
        Set("temperature", "max_tokens", "top_p", "stop")

    Model(
      canonicalSlug       = canonical,
      huggingFaceId       = entry.modelName,
      name                = entry.modelName,
      displayName         = Some(entry.modelName),
      description         = entry.description.getOrElse(""),
      contextLength       = contextLength,
      architecture        = ModelArchitecture(
        modality         = if (inputModalities.contains("image")) "text+image->text" else "text->text",
        inputModalities  = inputModalities,
        outputModalities = outputModalities,
        tokenizer        = "Unknown",
        instructType     = None
      ),
      pricing             = ModelPricing(
        prompt         = promptPerToken,
        completion     = completionPerToken,
        webSearch      = None,
        inputCacheRead = cachedReadPerToken
      ),
      topProvider         = ModelTopProvider(
        contextLength       = if (contextLength > 0) Some(contextLength) else None,
        maxCompletionTokens = None,
        isModerated         = false
      ),
      perRequestLimits    = None,
      supportedParameters = supportedParameters,
      defaultParameters   = ModelDefaultParameters(),
      knowledgeCutoff     = None,
      expirationDate      = entry.deprecated.map(epoch => Timestamp(epoch * 1000)),
      links               = ModelLinks(details = s"https://deepinfra.com/${entry.modelName}"),
      created             = now,
      modified            = now,
      _id                 = Id[Model](canonical.toLowerCase)
    )
  }

  /** Fetch DeepInfra's public catalog. The endpoint requires no auth
    * for the read-only list. Returns translated [[Model]] entries for
    * every `text-generation` row, including ones flagged
    * `deprecated` — callers filter when they want only active models
    * (e.g. `models.filterNot(_.expirationDate.exists(_.value < now))`).
    * Non-chat rows (image generation, embeddings, TTS) are skipped. */
  def loadModels(baseUrl: URL = url"https://api.deepinfra.com"): Task[List[Model]] =
    HttpClient.url(baseUrl.withPath("/models/list")).call[Json].map { json =>
      val rows = json.asVector
      rows.toList.flatMap { row =>
        val typ = row.get("type").map(_.asString).getOrElse("")
        if (typ != "text-generation") None
        else {
          val normalized = row.filterOne(fabric.filter.SnakeToCamelFilter)
          scala.util.Try(normalized.as[Entry]).toOption.map(toModel)
        }
      }
    }

  /** Convenience boot helper — load + merge into the framework cache.
    * Apps call this once on startup so cost-tracking surfaces
    * (`ConversationCostUpdated`, `cumulativeCost`) attribute spend
    * to the right `Model.pricing`. Returns the loaded list for apps
    * that want to inspect / log it. */
  def refreshModels(sigil: Sigil, baseUrl: URL = url"https://api.deepinfra.com"): Task[List[Model]] =
    loadModels(baseUrl).flatMap { models =>
      sigil.cache.merge(models).map { _ =>
        logger.info(s"Refreshed DeepInfra catalog with ${models.length} models.").sync()
        models
      }
    }
}
