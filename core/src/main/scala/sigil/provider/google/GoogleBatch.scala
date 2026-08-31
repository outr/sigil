package sigil.provider.google

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
 * Sigil #299 — Gemini Batch API integration. The wire flow (per
 * Google's `generativelanguage.googleapis.com` Batch surface):
 *
 *   1. POST `/v1beta/batches` with an inline `inlinedRequests` array;
 *      each entry is `{id, request: {contents, systemInstruction,
 *      generationConfig}}`. Returns the batch resource with a `name`
 *      like `batches/abc-123`.
 *   2. Poll `GET /v1beta/{name}` until `state` is terminal
 *      (`JOB_STATE_SUCCEEDED` / `JOB_STATE_FAILED` /
 *      `JOB_STATE_CANCELLED` / `JOB_STATE_EXPIRED`).
 *   3. Read per-request outputs from the terminal resource's
 *      `output.inlinedResponses.inlinedResponses[]` — each entry
 *      carries `id` (the originating request id), `response` (full
 *      Gemini response) or `error`.
 *
 * Gemini Batch offers ~50% cost reduction with a 24-hour SLA on the
 * stable 2.x model families.
 *
 * Gemini's Batch API has gone through wire-shape revisions; the
 * surface implemented here matches Google's current
 * `inlinedRequests` / `inlinedResponses` documentation. Consumers
 * hitting a future API change override `GoogleProvider.batch` with
 * their own adapter — the public contract (Stream in, Stream of
 * OneShotResponses out) stays stable.
 *
 * Multimodal content (text + images) flows via the Gemini content
 * shape: `[{role: "user", parts: [{text: "..."}, {inlineData: {mimeType,
 * data}}]}]`. Tools-in-batch is deferred (matches OpenAI / Anthropic
 * scope).
 */
object GoogleBatch {

  /**
   * Conservative per-batch ceiling — Gemini documents up to thousands
   * per inline batch but doesn't publish a hard limit at the
   * `inlinedRequests` shape. 1000 is a safe chunk size that avoids
   * hitting any single-request body-size cap. Apps with steady
   * larger workloads override `GoogleProvider.batch` to tune.
   */
  val MaxRequestsPerBatch: Int = 1000

  /**
   * Initial poll interval.
   */
  val InitialPollInterval: FiniteDuration = 5.seconds

  /**
   * Max poll interval.
   */
  val MaxPollInterval: FiniteDuration = 60.seconds

  /**
   * Render one OneShotRequest into the Gemini inlinedRequests entry
   * shape: `{id, request: {contents, systemInstruction?,
   * generationConfig?}}`.
   */
  def renderRequestEntry(request: OneShotRequest): Json = {
    val userParts: Vector[Json] =
      if (request.userContent.isEmpty)
        Vector(obj("text" -> str(request.userPrompt)))
      else
        request.userContent.flatMap(rcToGeminiPart)
    val contents = arr(obj(
      "role" -> str("user"),
      "parts" -> arr(userParts*)
    ))
    val baseRequest = Vector[(String, Json)](
      "contents" -> contents
    )
    val withSystem: Vector[(String, Json)] =
      if (request.systemPrompt.isEmpty) baseRequest
      else baseRequest :+
        ("systemInstruction" -> obj(
          "parts" -> arr(obj("text" -> str(request.systemPrompt)))
        ))
    val genConfig: Vector[(String, Json)] = {
      val gs = request.generationSettings
      val maxOut = gs.effectiveCap match {
        case sigil.provider.OutputTokenCap.Below(n) => Vector(("maxOutputTokens", num(n)))
        case _ => Vector.empty
      }
      val temp = gs.temperature.map(t => ("temperature", num(t))).toVector
      maxOut ++ temp
    }
    val withGen: Vector[(String, Json)] =
      if (genConfig.isEmpty) withSystem
      else withSystem :+ ("generationConfig" -> obj(genConfig*))
    obj(
      "id" -> str(request.requestId.value),
      "request" -> obj(withGen*)
    )
  }

  /**
   * Convert a [[ResponseContent]] block into one or more Gemini
   * content parts. Text → `text` part. Image (URL) → not directly
   * supported in batch (Gemini batch doesn't accept fetch URLs);
   * downgraded to text with the URL inline. ImageBytes →
   * `inlineData` part.
   */
  private def rcToGeminiPart(rc: ResponseContent): Vector[Json] = rc match {
    case ResponseContent.Text(t) =>
      Vector(obj("text" -> str(t)))
    case ResponseContent.Image(url, alt) =>
      // Gemini batch endpoint doesn't fetch external URLs the way
      // streaming `generateContent` does — degrade to text with the
      // URL inline so the model at least knows there's an image.
      // Consumers needing actual pixels through batch should encode
      // as ImageBytes (data URI inline) instead.
      Vector(obj("text" -> str(s"[image: ${alt.getOrElse(url.toString)}]")))
    case ResponseContent.ImageBytes(mt, b64, _) =>
      Vector(obj(
        "inlineData" -> obj(
          "mimeType" -> str(mt),
          "data" -> str(b64)
        )
      ))
    case other =>
      Vector(obj("text" -> str(sigil.render.MarkdownRenderer.renderBlock(other))))
  }

  /**
   * Parse one inlined response entry (a JSON object inside the
   * batch resource's `output.inlinedResponses.inlinedResponses`
   * array) into a [[OneShotResponse]].
   */
  def parseResponseEntry(entry: Json): Option[OneShotResponse] =
    entry.get("id").map(_.asString).map { idStr =>
      val reqId = lightdb.id.Id[sigil.provider.ProviderRequest](idStr)
      entry.get("error").filter(_ != fabric.Null) match {
        case Some(errJson) =>
          val msg = errJson.get("message").map(_.asString).getOrElse("unknown batch error")
          val code = errJson.get("code").map(_.asString)
          OneShotResponse(
            requestId = reqId,
            error = Some(OneShotResponse.Error(message = msg, code = code, recoverable = false))
          )
        case None =>
          entry.get("response") match {
            case Some(resp) =>
              val text = resp.get("candidates")
                .flatMap(_.asVector.headOption)
                .flatMap(_.get("content"))
                .flatMap(_.get("parts"))
                .flatMap(_.asVector.headOption)
                .flatMap(_.get("text"))
                .map(_.asString)
                .getOrElse("")
              val usage = resp.get("usageMetadata").map { u =>
                val prompt = u.get("promptTokenCount").map(_.asInt).getOrElse(0)
                val completion = u.get("candidatesTokenCount").map(_.asInt).getOrElse(0)
                TokenUsage(
                  promptTokens = prompt,
                  completionTokens = completion,
                  totalTokens = u.get("totalTokenCount").map(_.asInt).getOrElse(prompt + completion)
                )
              }
              OneShotResponse(
                requestId = reqId,
                content = if (text.isEmpty) Vector.empty else Vector(ResponseContent.Text(text)),
                usage = usage
              )
            case None =>
              OneShotResponse(
                requestId = reqId,
                error = Some(OneShotResponse.Error(
                  message = "batch response entry carried neither response nor error",
                  recoverable = false
                ))
              )
          }
      }
    }

  /**
   * Submit one chunk of requests as a single Gemini batch, poll
   * until completion, return the parsed responses as a stream.
   */
  def submitChunk(chunk: List[OneShotRequest],
                  apiKey: String,
                  baseUrl: URL): Stream[OneShotResponse] =
    if (chunk.isEmpty) Stream.empty
    else Stream.force(runChunk(chunk, apiKey, baseUrl).map(Stream.emits))

  private def runChunk(chunk: List[OneShotRequest],
                       apiKey: String,
                       baseUrl: URL): Task[List[OneShotResponse]] = {
    // Gemini's Batch surface expects all requests in a chunk to use
    // the same model. Group by stripped model id; one chunk per
    // distinct model would be cleaner but in practice consumers'
    // batches are uniform-model — assume that and fail loudly on
    // first mismatch.
    val distinctModels = chunk.map(r => Google.stripProviderPrefix(r.model._id.value)).distinct
    if (distinctModels.size > 1) {
      // Surface a per-request error so the stream stays consistent.
      Task.pure(chunk.map { r =>
        OneShotResponse(
          requestId = r.requestId,
          error = Some(OneShotResponse.Error(
            message = s"Gemini Batch: chunk has multiple models (${distinctModels.mkString(", ")}); " +
              "group OneShotRequests by model before calling batch().",
            recoverable = false
          ))
        )
      })
    } else {
      val model = distinctModels.head
      val customIds = chunk.map(_.requestId).toSet
      for {
        batchName <- createBatch(model, chunk, apiKey, baseUrl)
        finalSt <- pollUntilTerminal(batchName, apiKey, baseUrl)
        results = finalSt.inlinedResponses.flatMap(parseResponseEntry)
        seenIds = results.map(_.requestId).toSet
        missing = customIds.diff(seenIds).toList.map { rid =>
          OneShotResponse(
            requestId = rid,
            error = Some(OneShotResponse.Error(
              message = s"request did not appear in batch results (state=${finalSt.state})",
              recoverable = finalSt.state == "JOB_STATE_EXPIRED"
            ))
          )
        }
      } yield results ++ missing
    }
  }

  /**
   * Create the batch via POST /v1beta/batches.
   */
  def createBatch(model: String,
                  chunk: List[OneShotRequest],
                  apiKey: String,
                  baseUrl: URL): Task[String] = {
    val entries = chunk.map(renderRequestEntry)
    val body = obj(
      "batch" -> obj(
        "displayName" -> str(s"sigil-batch-${java.util.UUID.randomUUID().toString.take(8)}"),
        "model" -> str(s"models/$model"),
        "inputConfig" -> obj(
          "inlinedRequests" -> obj("inlinedRequests" -> arr(entries*))
        )
      )
    )
    val req = HttpRequest(
      method = HttpMethod.Post,
      url = baseUrl.withPath("/v1beta/batches"),
      content = Some(StringContent(JsonFormatter.Compact(body), ContentType.`application/json`))
    ).withHeader("x-goog-api-key", apiKey)
    HttpClient.modify(_ => req).noFailOnHttpStatus.send().flatMap { resp =>
      resp.content match {
        case Some(c) => c.asString.flatMap { resBody =>
            if (resp.status.isSuccess) Task {
              JsonParser(resBody).get("name").map(_.asString).getOrElse {
                throw new RuntimeException(s"Gemini Batch: missing `name` in create response: $resBody")
              }
            }
            else Task.error(new RuntimeException(s"Gemini Batch: create failed (${resp.status}): $resBody"))
          }
        case None => Task.error(new RuntimeException(s"Gemini Batch: empty body on create (${resp.status})"))
      }
    }
  }

  /**
   * Terminal states from Gemini's Batch state machine.
   */
  private val TerminalStates: Set[String] = Set(
    "JOB_STATE_SUCCEEDED",
    "JOB_STATE_FAILED",
    "JOB_STATE_CANCELLED",
    "JOB_STATE_EXPIRED"
  )

  case class BatchState(state: String, inlinedResponses: List[Json])

  def pollUntilTerminal(batchName: String, apiKey: String, baseUrl: URL): Task[BatchState] = {
    def loop(delay: FiniteDuration): Task[BatchState] =
      getBatchState(batchName, apiKey, baseUrl).flatMap { st =>
        if (TerminalStates.contains(st.state)) Task.pure(st)
        else Task.sleep(delay).flatMap(_ => loop((delay * 2).min(MaxPollInterval)))
      }
    loop(InitialPollInterval)
  }

  private def getBatchState(batchName: String, apiKey: String, baseUrl: URL): Task[BatchState] = {
    val req = HttpRequest(
      method = HttpMethod.Get,
      url = baseUrl.withPath(s"/v1beta/$batchName")
    ).withHeader("x-goog-api-key", apiKey)
    HttpClient.modify(_ => req).noFailOnHttpStatus.send().flatMap { resp =>
      resp.content match {
        case Some(c) => c.asString.flatMap { body =>
            if (resp.status.isSuccess) Task {
              val json = JsonParser(body)
              val state = json.get("state").map(_.asString).getOrElse("UNKNOWN")
              val responses = json
                .get("output")
                .flatMap(_.get("inlinedResponses"))
                .flatMap(_.get("inlinedResponses"))
                .map(_.asVector.toList)
                .getOrElse(Nil)
              BatchState(state, responses)
            }
            else Task.error(new RuntimeException(s"Gemini Batch: state fetch failed (${resp.status}): $body"))
          }
        case None => Task.error(new RuntimeException(s"Gemini Batch: empty body on state fetch (${resp.status})"))
      }
    }
  }
}
