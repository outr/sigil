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
    * `{property_id, value}` records keyed by string id.
    *
    * `value` is `Json`, not `String`: Cloudflare returns non-string
    * values for some properties (e.g. `price` is a JSON array of
    * per-unit pricing objects). [[toModel]] reads only the string-valued
    * properties it consumes; everything else is ignored rather than
    * fatal. Typing this `String` previously made fabric's strict decode
    * throw on the array-valued `price`, collapsing the entire catalog to
    * zero models. */
  case class Property(propertyId: String, value: Json) derives RW

  private case class ListResponse(result: List[Json] = Nil,
                                   resultInfo: Option[ResultInfo] = None) derives RW

  case class ResultInfo(page: Int = 1,
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
    * Pricing is read from the array-valued `price` property (#337) and
    * mapped into per-token [[ModelPricing]]; a model whose catalog entry
    * carries no `price` stays at zero. */
  def toModel(entry: Entry): Model = {
    val canonical = s"$Provider/${entry.name}"
    // Keep only the string-valued properties toModel actually reads;
    // non-string values (e.g. `price`'s array) are simply absent here.
    val propsMap: Map[String, String] = entry.properties.iterator.collect {
      case Property(id, Str(s, _)) => id -> s
    }.toMap
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
      pricing             = pricingFrom(entry.properties),
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

  /** Per-million-token → per-token divisor. Cloudflare's catalog quotes
    * `price` in per-M-token units; [[ModelPricing]] is per-token (see
    * [[sigil.Sigil.costFor]], which multiplies by raw token counts). */
  private val PerMillion = BigDecimal(1000000)

  /** Map the array-valued `price` property into per-token
    * [[ModelPricing]] (#337). Each row is `{unit, price, currency}`; we
    * map the three token units we bill on and divide by a million.
    * A missing / non-array / malformed `price` yields zeros, so a catalog
    * entry without pricing still loads. Values parse through `toString`
    * to avoid binary-float artifacts (`0.95` stays `0.95`). */
  private def pricingFrom(properties: List[Property]): ModelPricing = {
    val byUnit: Map[String, BigDecimal] = properties.collectFirst {
      case Property("price", arr) if arr.isArr =>
        arr.asVector.toList.flatMap { row =>
          for {
            unit  <- row.get("unit").map(_.asString)
            price <- scala.util.Try(BigDecimal(row.get("price").map(_.asDouble).getOrElse(0.0).toString)).toOption
          } yield unit -> price
        }
    }.getOrElse(Nil).toMap
    def perToken(unit: String): Option[BigDecimal] = byUnit.get(unit).map(_ / PerMillion)
    ModelPricing(
      prompt         = perToken("per M input tokens").getOrElse(BigDecimal(0)),
      completion     = perToken("per M output tokens").getOrElse(BigDecimal(0)),
      webSearch      = None,
      inputCacheRead = perToken("per M cached input tokens")
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
          val (entries, resultInfo) = parsePage(json)
          val combined = acc ++ entries
          val hasMore = resultInfo match {
            case Some(info) if info.perPage > 0 && info.page * info.perPage < info.totalCount => true
            case _ => false
          }
          if (hasMore) fetchPage(page + 1, combined) else Task.pure(combined)
        }
    }
    fetchPage(1, Nil).map(_.map(toModel))
  }

  /** Parse one `/ai/models/search` page: snake_case-normalise, then
    * decode each entry **individually** so a single unparseable row
    * (an unexpected property shape, a missing field) skips itself with a
    * `scribe.warn` instead of collapsing the whole page to empty. A
    * top-level decode failure is logged before falling back to an empty
    * page — "0 models" is never silent. Returns the surviving entries
    * plus pagination info. */
  def parsePage(rawJson: Json): (List[Entry], Option[ResultInfo]) = {
    val normalized = rawJson.filterOne(SnakeToCamelFilter)
    val response = scala.util.Try(normalized.as[ListResponse]) match {
      case scala.util.Success(r) => r
      case scala.util.Failure(t) =>
        scribe.warn(s"Cloudflare catalog page failed to parse — ${t.getClass.getSimpleName}: ${t.getMessage}")
        ListResponse()
    }
    val entries = response.result.flatMap { row =>
      scala.util.Try(row.as[Entry]) match {
        case scala.util.Success(e) => Some(e)
        case scala.util.Failure(t) =>
          scribe.warn(s"Cloudflare catalog: skipping unparseable model entry — ${t.getClass.getSimpleName}: ${t.getMessage}")
          None
      }
    }
    (entries, response.resultInfo)
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
