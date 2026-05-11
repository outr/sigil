package sigil.provider.digitalocean

import fabric.*
import fabric.rw.*
import lightdb.id.Id
import lightdb.time.Timestamp
import rapid.{Task, logger}
import sigil.Sigil
import sigil.db.{Model, ModelArchitecture, ModelDefaultParameters, ModelLinks, ModelPricing, ModelTopProvider}
import spice.http.{HttpMethod, HttpRequest}
import spice.http.client.HttpClient
import spice.net.*

/**
 * DigitalOcean Inference (GenAI Platform) — OpenAI-compatible
 * endpoints at `https://inference.do-ai.run`. Two wire surfaces
 * matter to the framework:
 *
 *   - `/v1/chat/completions` — universal across hosted models. Served
 *     by [[DigitalOceanProvider]].
 *   - `/v1/responses` — opt-in Responses API. Served by
 *     [[DigitalOceanResponsesProvider]] (a configured [[sigil.provider.openai.OpenAIProvider]]).
 *
 * Model metadata lives in [[sigil.cache.ModelRegistry]]; populate it
 * via [[refreshModels]] (DO's `/v1/models` endpoint) or by registering
 * entries manually if an app pins a specific catalog.
 */
object DigitalOcean {
  val Provider: String = "digitalocean"

  def stripProviderPrefix(sigilModelId: String): String = {
    val prefix = s"$Provider/"
    if (sigilModelId.startsWith(prefix)) sigilModelId.drop(prefix.length) else sigilModelId
  }

  /** Fetch DO's hosted-model catalog from `GET /v1/models` and merge
    * the entries into the registry under the `digitalocean/` namespace.
    * DO's response follows the OpenAI list shape:
    * `{ "object": "list", "data": [ { "id": "kimi-k2.5", ... }, ... ] }`.
    * The framework keeps the entries small — DO doesn't publish pricing
    * or architecture metadata on the list endpoint, so the registry
    * gets the id + default placeholders. Apps with richer cost
    * tracking override [[Model.pricing]] post-merge. */
  def refreshModels(sigil: Sigil,
                    apiKey: String,
                    baseUrl: URL = url"https://inference.do-ai.run"): Task[List[Model]] = {
    val req = HttpRequest(
      method = HttpMethod.Get,
      url    = baseUrl.withPath("/v1/models")
    ).withHeader("Authorization", s"Bearer $apiKey")
    HttpClient.modify(_ => req).call[Json].flatMap { json =>
      val rows = json.get("data").map(_.asVector).getOrElse(Vector.empty)
      val now  = Timestamp()
      val models = rows.toList.flatMap { row =>
        row.get("id").map(_.asString).filter(_.nonEmpty).map { modelId =>
          val canonical = s"$Provider/$modelId"
          Model(
            canonicalSlug       = canonical,
            huggingFaceId       = "",
            name                = modelId,
            displayName         = Some(modelId),
            description         = "",
            contextLength       = 0L,
            architecture        = ModelArchitecture(
              modality         = "text->text",
              inputModalities  = List("text"),
              outputModalities = List("text"),
              tokenizer        = "Unknown",
              instructType     = None
            ),
            pricing             = ModelPricing(
              prompt         = BigDecimal(0),
              completion     = BigDecimal(0),
              webSearch      = None,
              inputCacheRead = None
            ),
            topProvider         = ModelTopProvider(
              contextLength       = None,
              maxCompletionTokens = None,
              isModerated         = false
            ),
            perRequestLimits    = None,
            supportedParameters = Set.empty,
            defaultParameters   = ModelDefaultParameters(),
            knowledgeCutoff     = None,
            expirationDate      = None,
            links               = ModelLinks(details = ""),
            created             = now,
            modified            = now,
            _id                 = Id[Model](canonical)
          )
        }
      }
      sigil.cache.merge(models).map { _ =>
        logger.info(s"Refreshed DigitalOcean catalog with ${models.length} models.").sync()
        models
      }
    }
  }
}
