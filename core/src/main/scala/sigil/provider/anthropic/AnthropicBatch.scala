package sigil.provider.anthropic

import fabric.*
import fabric.io.{JsonFormatter, JsonParser}
import rapid.{Stream, Task}
import sigil.provider.{OneShotRequest, OneShotResponse, TokenUsage}
import sigil.tool.model.ResponseContent
import spice.http.{HttpMethod, HttpRequest}
import spice.http.client.HttpClient
import spice.http.content.StringContent
import spice.net.{ContentType, URL}

import scala.concurrent.duration.*

/**
 * Sigil #299 — Anthropic Message Batches API integration. The wire
 * flow:
 *
 *   1. POST `/v1/messages/batches` with an inline `requests` array;
 *      each entry is `{custom_id, params: {model, max_tokens, system,
 *      messages, ...}}`. Anthropic accepts up to 100K requests OR
 *      256MB body — no separate file-upload step like OpenAI's.
 *   2. Poll `GET /v1/messages/batches/{id}` until
 *      `processing_status == "ended"` (in-progress / canceling are
 *      transient).
 *   3. Download per-request results via `GET /v1/messages/batches/{id}/results`
 *      (JSONL). Parse each line into a [[OneShotResponse]] keyed by
 *      `custom_id`.
 *
 * Anthropic Message Batches discount is ~50% on Sonnet / Haiku / Opus
 * with a 24-hour SLA. Multimodal content (text + images) flows via
 * the standard Messages API content-block shape; ImageBytes becomes
 * `{type: "image", source: {type: "base64", media_type, data}}`.
 *
 * Tools-in-batch deferred (consistent with OpenAI's batch override);
 * most real-world batch workloads (classification, vision rewrite,
 * embedding-prep) don't use tools.
 */
object AnthropicBatch {

  /**
   * Anthropic's documented per-batch ceiling (entries).
   */
  val MaxRequestsPerBatch: Int = 100_000

  /**
   * Initial poll interval. Anthropic batches complete on similar
   * timescales to OpenAI's — small batches in seconds-to-minutes,
   * large batches up to the 24h SLA.
   */
  val InitialPollInterval: FiniteDuration = 5.seconds

  /**
   * Max poll interval.
   */
  val MaxPollInterval: FiniteDuration = 60.seconds

  /**
   * Default per-request `max_tokens` when the caller hasn't supplied
   * one. Anthropic requires `max_tokens` on every Messages request
   * (it's not optional like OpenAI's `max_completion_tokens`).
   */
  val DefaultMaxTokens: Int = 4096

  /**
   * Render one OneShotRequest into the Anthropic Message Batches
   * entry shape: `{custom_id, params: {model, max_tokens, system,
   * messages}}`. Used by [[submitChunk]] to build the inline
   * `requests` array.
   */
  def renderRequestEntry(request: OneShotRequest): Json = {
    val modelName = stripNamespacePrefix(request.model._id.value)
    val userContent: Vector[Json] =
      if (request.userContent.isEmpty)
        Vector(obj("type" -> str("text"), "text" -> str(request.userPrompt)))
      else
        request.userContent.flatMap(rcToAnthropicContent)
    val userMsg = obj(
      "role" -> str("user"),
      "content" -> arr(userContent*)
    )
    val maxTokens = request.generationSettings.effectiveCap match {
      case sigil.provider.OutputTokenCap.Below(n) => n
      case _ => DefaultMaxTokens
    }
    val params = Vector[(String, Json)](
      "model" -> str(modelName),
      "max_tokens" -> num(maxTokens),
      "messages" -> arr(userMsg)
    )
    val withSystem: Vector[(String, Json)] =
      if (request.systemPrompt.isEmpty) params
      else params :+ ("system" -> str(request.systemPrompt))
    val withTemp: Vector[(String, Json)] = request.generationSettings.temperature match {
      case Some(t) => withSystem :+ ("temperature" -> num(t))
      case None => withSystem
    }
    obj(
      "custom_id" -> str(request.requestId.value),
      "params" -> obj(withTemp*)
    )
  }

  /**
   * Convert a [[ResponseContent]] block into one or more Anthropic
   * Messages-API content entries. Text → `text` block. Image (URL)
   * → `image.source.type = "url"`. ImageBytes → `image.source.type
   * = "base64"`. Other variants flatten through markdown to text.
   */
  private def rcToAnthropicContent(rc: ResponseContent): Vector[Json] = rc match {
    case ResponseContent.Text(t) =>
      Vector(obj("type" -> str("text"), "text" -> str(t)))
    case ResponseContent.Image(url, _) =>
      Vector(obj(
        "type" -> str("image"),
        "source" -> obj("type" -> str("url"), "url" -> str(url.toString))
      ))
    case ResponseContent.ImageBytes(mt, b64, _) =>
      Vector(obj(
        "type" -> str("image"),
        "source" -> obj(
          "type" -> str("base64"),
          "media_type" -> str(mt),
          "data" -> str(b64)
        )
      ))
    case other =>
      Vector(obj(
        "type" -> str("text"),
        "text" -> str(sigil.render.MarkdownRenderer.renderBlock(other))
      ))
  }

  /**
   * Parse one JSONL result line into a [[OneShotResponse]]. Anthropic
   * shape: `{custom_id, result: {type: "succeeded"|"errored"|...,
   * message?, error?}}`.
   */
  def parseResultLine(line: String): Option[OneShotResponse] = {
    if (line.trim.isEmpty) return None
    scala.util.Try(JsonParser(line)).toOption.flatMap { json =>
      json.get("custom_id").map(_.asString).map { customId =>
        val reqId = lightdb.id.Id[sigil.provider.ProviderRequest](customId)
        val result = json.get("result")
        result.flatMap(_.get("type")).map(_.asString) match {
          case Some("succeeded") =>
            val message = result.flatMap(_.get("message"))
            val text = message
              .flatMap(_.get("content"))
              .flatMap(_.asVector.headOption)
              .flatMap(_.get("text"))
              .map(_.asString)
              .getOrElse("")
            val usage = message.flatMap(_.get("usage")).map { u =>
              TokenUsage(
                promptTokens = u.get("input_tokens").map(_.asInt).getOrElse(0),
                completionTokens = u.get("output_tokens").map(_.asInt).getOrElse(0),
                totalTokens = u.get("input_tokens").map(_.asInt).getOrElse(0) +
                  u.get("output_tokens").map(_.asInt).getOrElse(0)
              )
            }
            OneShotResponse(
              requestId = reqId,
              content = if (text.isEmpty) Vector.empty else Vector(ResponseContent.Text(text)),
              usage = usage
            )
          case Some("errored") =>
            val err = result.flatMap(_.get("error"))
            val msg = err.flatMap(_.get("message")).map(_.asString).getOrElse("unknown error")
            val code = err.flatMap(_.get("type")).map(_.asString)
            OneShotResponse(
              requestId = reqId,
              error = Some(OneShotResponse.Error(message = msg, code = code, recoverable = false))
            )
          case Some(other) =>
            // "canceled" / "expired" — surface as error with the
            // result type so consumers can distinguish from real
            // failures.
            OneShotResponse(
              requestId = reqId,
              error = Some(OneShotResponse.Error(
                message = s"batch entry status: $other",
                code = Some(other),
                recoverable = other == "expired"
              ))
            )
          case None =>
            OneShotResponse(
              requestId = reqId,
              error = Some(OneShotResponse.Error(
                message = "batch result line missing `result.type`",
                recoverable = false
              ))
            )
        }
      }
    }
  }

  /**
   * Strip the `anthropic/` namespace prefix from a model id.
   */
  private def stripNamespacePrefix(modelId: String): String = {
    val prefix = "anthropic/"
    if (modelId.toLowerCase.startsWith(prefix)) modelId.drop(prefix.length) else modelId
  }

  /**
   * Submit one chunk of requests as a single Anthropic batch, poll
   * until completion, return the parsed responses as a stream.
   */
  def submitChunk(chunk: List[OneShotRequest],
                  apiKey: String,
                  baseUrl: URL,
                  authMode: AnthropicAuthMode): Stream[OneShotResponse] =
    if (chunk.isEmpty) Stream.empty
    else Stream.force(runChunk(chunk, apiKey, baseUrl, authMode).map(Stream.emits))

  private def runChunk(chunk: List[OneShotRequest],
                       apiKey: String,
                       baseUrl: URL,
                       authMode: AnthropicAuthMode): Task[List[OneShotResponse]] = {
    val customIds = chunk.map(_.requestId).toSet
    for {
      batchId <- createBatch(chunk, apiKey, baseUrl, authMode)
      finalSt <- pollUntilEnded(batchId, apiKey, baseUrl, authMode)
      results <- finalSt.resultsUrl match {
        case Some(url) =>
          downloadResults(url, apiKey, authMode).map { jsonl =>
            jsonl.split('\n').iterator.flatMap(parseResultLine).toList
          }
        case None =>
          Task.pure(chunk.map { r =>
            OneShotResponse(
              requestId = r.requestId,
              error = Some(OneShotResponse.Error(
                message = s"batch ended with no results URL (status=${finalSt.processingStatus})",
                code = Some(finalSt.processingStatus),
                recoverable = false
              ))
            )
          })
      }
      seenIds = results.map(_.requestId).toSet
      missing = customIds.diff(seenIds).toList.map { rid =>
        OneShotResponse(
          requestId = rid,
          error = Some(OneShotResponse.Error(
            message = "request did not appear in batch results",
            recoverable = false
          ))
        )
      }
    } yield results ++ missing
  }

  /**
   * Auth-header helper — Anthropic's two auth modes share the same
   * shape across all batch endpoints.
   */
  private def auth(req: HttpRequest, apiKey: String, authMode: AnthropicAuthMode): HttpRequest = authMode match {
    case AnthropicAuthMode.XApiKey =>
      req.withHeader("x-api-key", apiKey).withHeader("anthropic-version", Anthropic.ApiVersion)
    case AnthropicAuthMode.Bearer =>
      req.withHeader("Authorization", s"Bearer $apiKey")
        .withHeader("anthropic-version", Anthropic.ApiVersion)
  }

  /**
   * Create the batch via POST /v1/messages/batches with inline requests.
   */
  def createBatch(chunk: List[OneShotRequest],
                  apiKey: String,
                  baseUrl: URL,
                  authMode: AnthropicAuthMode): Task[String] = {
    val entries = chunk.map(renderRequestEntry)
    val body = obj("requests" -> arr(entries*))
    val req = auth(
      HttpRequest(
        method = HttpMethod.Post,
        url = baseUrl.withPath("/v1/messages/batches"),
        content = Some(StringContent(JsonFormatter.Compact(body), ContentType.`application/json`))
      ),
      apiKey,
      authMode
    )
    HttpClient.modify(_ => req).noFailOnHttpStatus.send().flatMap { resp =>
      resp.content match {
        case Some(c) => c.asString.flatMap { resBody =>
            if (resp.status.isSuccess) Task {
              JsonParser(resBody).get("id").map(_.asString).getOrElse {
                throw new RuntimeException(s"Anthropic Batch: missing `id` in response: $resBody")
              }
            }
            else Task.error(new RuntimeException(s"Anthropic Batch: create failed (${resp.status}): $resBody"))
          }
        case None => Task.error(new RuntimeException(s"Anthropic Batch: empty body on create (${resp.status})"))
      }
    }
  }

  /**
   * Snapshot of an Anthropic batch's poll-state.
   */
  case class BatchState(processingStatus: String,
                        resultsUrl: Option[String])

  def pollUntilEnded(batchId: String,
                     apiKey: String,
                     baseUrl: URL,
                     authMode: AnthropicAuthMode): Task[BatchState] = {
    def loop(delay: FiniteDuration): Task[BatchState] =
      getBatchState(batchId, apiKey, baseUrl, authMode).flatMap { st =>
        if (st.processingStatus == "ended") Task.pure(st)
        else Task.sleep(delay).flatMap(_ => loop((delay * 2).min(MaxPollInterval)))
      }
    loop(InitialPollInterval)
  }

  private def getBatchState(batchId: String,
                            apiKey: String,
                            baseUrl: URL,
                            authMode: AnthropicAuthMode): Task[BatchState] = {
    val req = auth(
      HttpRequest(method = HttpMethod.Get, url = baseUrl.withPath(s"/v1/messages/batches/$batchId")),
      apiKey,
      authMode
    )
    HttpClient.modify(_ => req).noFailOnHttpStatus.send().flatMap { resp =>
      resp.content match {
        case Some(c) => c.asString.flatMap { body =>
            if (resp.status.isSuccess) Task {
              val json = JsonParser(body)
              BatchState(
                processingStatus = json.get("processing_status").map(_.asString).getOrElse("unknown"),
                resultsUrl = json.get("results_url").filter(_ != fabric.Null).map(_.asString)
              )
            }
            else Task.error(new RuntimeException(s"Anthropic Batch: state fetch failed (${resp.status}): $body"))
          }
        case None => Task.error(new RuntimeException(s"Anthropic Batch: empty body on state fetch (${resp.status})"))
      }
    }
  }

  /**
   * Download results — Anthropic returns a `results_url` (an
   * absolute URL the client GETs directly with the same auth
   * headers). The body is JSONL.
   */
  def downloadResults(resultsUrl: String,
                      apiKey: String,
                      authMode: AnthropicAuthMode): Task[String] = {
    val parsed = spice.net.URL.parse(resultsUrl)
    val req = auth(
      HttpRequest(method = HttpMethod.Get, url = parsed),
      apiKey,
      authMode
    )
    HttpClient.modify(_ => req).noFailOnHttpStatus.send().flatMap { resp =>
      resp.content match {
        case Some(c) =>
          c.asString.flatMap { body =>
            if (resp.status.isSuccess) Task.pure(body)
            else Task.error(new RuntimeException(s"Anthropic Batch: results download failed (${resp.status}): $body"))
          }
        case None => Task.error(new RuntimeException(s"Anthropic Batch: empty body on results download (${resp.status})"))
      }
    }
  }
}
