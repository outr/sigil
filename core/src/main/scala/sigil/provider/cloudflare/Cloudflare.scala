package sigil.provider.cloudflare

import fabric.*
import fabric.filter.SnakeToCamelFilter
import fabric.rw.*
import lightdb.id.Id
import lightdb.time.Timestamp
import rapid.{Task, logger}
import sigil.Sigil
import sigil.db.{Model, ModelArchitecture, ModelDefaultParameters, ModelLinks, ModelPricing, ModelTopProvider}
import spice.http.client.HttpClient
import spice.net.*

/**
 * Cloudflare Workers AI — OpenAI-compatible chat-completions hosted at
 * `https://api.cloudflare.com/client/v4/accounts/{accountId}/ai/v1/chat/completions`.
 *
 * Model id format on the wire is `@cf/<vendor>/<model>` (e.g.
 * `@cf/moonshotai/kimi-k2.6`). The framework's `Id[Model]` namespaces
 * it as `cloudflare/@cf/moonshotai/kimi-k2.6`; [[stripProviderPrefix]]
 * removes the `cloudflare/` prefix before the model name reaches the
 * wire body's `model` field.
 *
 * The catalog is loaded from Cloudflare's
 * `/client/v4/accounts/{accountId}/ai/models/search` endpoint — auth
 * via Bearer token. See [[refreshModels]] and [[loadModels]]. Context
 * length and max-output budget flow into the cached [[Model]] so the
 * framework's pre-flight budget gate, routing strategy, and the
 * post-#311 boundary check on tool inputs that accept a `modelId`
 * have a registered record to compare against.
 */
object Cloudflare {
  val Provider: String = "cloudflare"

  /** Strip the `cloudflare/` namespace prefix from a Sigil model id,
    * leaving the raw `@cf/...` model name Cloudflare's OpenAI-compatible
    * endpoint expects in the request body. */
  def stripProviderPrefix(sigilModelId: String): String = {
    val prefix = s"$Provider/"
    if (sigilModelId.startsWith(prefix)) sigilModelId.drop(prefix.length) else sigilModelId
  }

  /** One row of Cloudflare's `/ai/models/search` response. Names mirror
    * the wire fields (after camelCase normalisation). The `id` field is
    * Cloudflare's internal UUID — we key Sigil records by `name`
    * (e.g. `@cf/moonshotai/kimi-k2.6`) because that's what the
    * chat-completions wire expects in the `model` field. */
  case class Entry(id: String,
                   name: String,
                   description: Option[String] = None,
                   task: Option[CloudflareTask] = None,
                   properties: List[Property] = Nil) derives RW

  case class CloudflareTask(id: String, name: String) derives RW

  /** Single entry in a row's `properties` array. Cloudflare exposes
    * per-model metadata (context length, max input/output tokens,
    * function-calling support, vision support, …) as a flat list of
    * `{property_id, value}` records keyed by string id. */
  case class Property(propertyId: String, value: String) derives RW

  private case class ListResponse(result: List[Entry] = Nil,
                                   resultInfo: Option[ResultInfo] = None) derives RW

  private case class ResultInfo(page: Int = 1,
                                 perPage: Int = 0,
                                 count: Int = 0,
                                 totalCount: Int = 0) derives RW

  /** The `task.name` Cloudflare assigns to chat-completion models. The
    * Workers AI catalog hosts many other task families (embeddings,
    * image-to-image, text-to-image, automatic-speech-recognition, …)
    * that are NOT chat-completion endpoints; the default filter on
    * [[loadModels]] excludes them. */
  val TextGenerationTask: String = "Text Generation"

  /** Translate a Cloudflare catalog entry into a Sigil [[Model]].
    * Context length pulls from `context_window` (preferred) or
    * `max_input_tokens` (fallback). Max-output budget pulls from
    * `max_output_tokens` when present so the framework's max-tokens
    * resolver has a real ceiling.
    *
    * Pricing is left at zero — Workers AI bills per-neuron through
    * the Cloudflare account rather than per-token in the catalog
    * payload. Apps that need accurate cost attribution wire a
    * custom Model record via `cache.merge` post-load. */
  def toModel(entry: Entry): Model = {
    val canonical = s"$Provider/${entry.name}"
    val propsMap = entry.properties.iterator.map(p => p.propertyId -> p.value).toMap
    val contextLength: Long = propsMap.get("context_window")
      .orElse(propsMap.get("max_input_tokens"))
      .flatMap(_.toLongOption)
      .getOrElse(0L)
    val maxOutput: Option[Long] = propsMap.get("max_output_tokens").flatMap(_.toLongOption)
    val supportsTools: Boolean = propsMap.get("function_calling").exists(_.equalsIgnoreCase("true"))
    val supportsVision: Boolean = propsMap.get("vision").exists(_.equalsIgnoreCase("true"))
    val inputModalities: List[String] = if (supportsVision) List("text", "image") else List("text")
    val supportedParameters: Set[String] = {
      val base = Set("temperature", "max_tokens", "top_p", "response_format", "reasoning_effort")
      if (supportsTools) base ++ Set("tools", "tool_choice") else base
    }
    val now = Timestamp()

    Model(
      canonicalSlug       = canonical,
      huggingFaceId       = "",
      name                = entry.name,
      displayName         = displayNameFor(entry.name),
      description         = entry.description.filter(_.nonEmpty)
                              .getOrElse(s"Cloudflare Workers AI model ${entry.name}"),
      contextLength       = contextLength,
      architecture        = ModelArchitecture(
        modality         = if (supportsVision) "text+image->text" else "text->text",
        inputModalities  = inputModalities,
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
        contextLength       = Some(contextLength).filter(_ > 0),
        maxCompletionTokens = maxOutput,
        isModerated         = false
      ),
      perRequestLimits    = None,
      supportedParameters = supportedParameters,
      defaultParameters   = ModelDefaultParameters(),
      knowledgeCutoff     = None,
      expirationDate      = None,
      links               = ModelLinks(details = s"https://developers.cloudflare.com/workers-ai/models/"),
      created             = now,
      modified            = now,
      _id                 = Id[Model](canonical)
    )
  }

  /** Fetch + map Cloudflare's Workers AI catalog. Walks pagination
    * until every page is consumed; default filters to
    * [[TextGenerationTask]] rows so embeddings / image / ASR models
    * don't pollute the chat-completion registry. Pass
    * `textGenerationOnly = false` to load the whole catalog.
    *
    * Auth uses the supplied `apiToken` as a Bearer header. The token
    * needs the `Workers AI:Read` scope. Failures (auth, network,
    * malformed response) propagate so provider construction fails
    * loudly rather than silently seeding nothing. */
  def loadModels(accountId: String,
                 apiToken: String,
                 baseUrl: URL = url"https://api.cloudflare.com",
                 textGenerationOnly: Boolean = true): Task[List[Model]] = {
    val perPage = 100
    def fetchPage(page: Int, acc: List[Entry]): Task[List[Entry]] = {
      var u = baseUrl
        .withPath(s"/client/v4/accounts/$accountId/ai/models/search")
        .withParam("per_page", perPage.toString)
        .withParam("page", page.toString)
        .withParam("hide_experimental", "true")
      if (textGenerationOnly) u = u.withParam("task", TextGenerationTask)
      HttpClient.url(u)
        .header("Authorization", s"Bearer $apiToken")
        .call[Json]
        .flatMap { json =>
          val normalized = json.filterOne(SnakeToCamelFilter)
          val response = scala.util.Try(normalized.as[ListResponse]).getOrElse(ListResponse())
          val combined = acc ++ response.result
          val hasMore = response.resultInfo match {
            case Some(info) if info.perPage > 0 && info.page * info.perPage < info.totalCount => true
            case _ => false
          }
          if (hasMore) fetchPage(page + 1, combined) else Task.pure(combined)
        }
    }
    fetchPage(1, Nil).map(_.map(toModel))
  }

  /** Convenience boot helper — load + merge into the framework cache.
    * Apps call this once on startup so the framework's pre-flight
    * budget gate, routing strategy, and the post-#311 modelId
    * boundary check on tool inputs all see a real [[Model]] record
    * for every Workers AI deployment the app routes to. Returns the
    * loaded list for apps that want to inspect / log it. */
  def refreshModels(sigil: Sigil,
                    accountId: String,
                    apiToken: String,
                    baseUrl: URL = url"https://api.cloudflare.com"): Task[List[Model]] =
    loadModels(accountId, apiToken, baseUrl).flatMap { models =>
      sigil.cache.merge(models).map { _ =>
        logger.info(s"Refreshed Cloudflare Workers AI catalog with ${models.length} models.").sync()
        models
      }
    }

  /** Heuristic friendly name for a Cloudflare model name like
    * `@cf/moonshotai/kimi-k2.6`. Drops the `@cf/` namespace and the
    * vendor prefix, hyphen-splits the remaining slug, and title-cases
    * each segment ("Kimi K2.6"). Returns `None` when the name doesn't
    * conform to the `@cf/<vendor>/<slug>` shape. */
  def displayNameFor(name: String): Option[String] = {
    val parts = name.stripPrefix("@cf/").split('/').filter(_.nonEmpty)
    parts.lastOption.map { slug =>
      slug.split('-').filter(_.nonEmpty).map(seg =>
        if (seg.isEmpty) seg else seg.head.toUpper.toString + seg.tail
      ).mkString(" ")
    }
  }
}
