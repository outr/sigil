package sigil.provider.openai

import fabric.*
import fabric.io.{JsonFormatter, JsonParser}
import rapid.{Stream, Task}
import sigil.provider.{OneShotRequest, OneShotResponse, TokenUsage}
import sigil.tool.model.ResponseContent
import spice.http.{HttpMethod, HttpRequest, HttpStatus, Headers}
import spice.http.client.HttpClient
import spice.http.content.{FormDataContent, StringContent}
import spice.net.{ContentType, URL}

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.concurrent.duration.*

/**
 * Sigil #299 — OpenAI Batch API integration. The wire flow:
 *
 *   1. Render each [[OneShotRequest]] as a JSONL line with shape
 *      `{custom_id, method: "POST", url: "/v1/chat/completions", body}`.
 *   2. Upload the JSONL bytes via `POST /v1/files` (multipart form,
 *      `purpose=batch`); upstream returns a `file_id`.
 *   3. Create the batch via `POST /v1/batches` with the input file id,
 *      `endpoint=/v1/chat/completions`, `completion_window=24h`.
 *   4. Poll `GET /v1/batches/{id}` with exponential backoff until the
 *      status is terminal (`completed` / `failed` / `expired` /
 *      `cancelled`).
 *   5. Download the output file (`GET /v1/files/{output_file_id}/content`)
 *      and parse each JSONL line into a [[OneShotResponse]] keyed by
 *      `custom_id` (the originating `OneShotRequest.requestId`).
 *   6. Best-effort delete the input + output files to keep the account
 *      clean (no impact on the streamed responses).
 *
 * The OpenAI Batch API guarantees ~50% cost reduction on chat
 * completions (the Responses API surface isn't yet a Batch endpoint)
 * with a 24-hour SLA — typical real-world completion is minutes for
 * small batches up to a few hours for max-size batches. Per-batch
 * limits: 50K requests OR 200MB file size; callers' input streams
 * are chunked accordingly.
 *
 * Multimodal content (image bytes / URLs) is supported via the
 * standard `content: [{type: text}, {type: image_url}]` user-message
 * shape. Tools / `tool_choice` are NOT supported in batch yet
 * (deferred — most real-world batch workloads — classification,
 * embedding, vision rewrite — don't use tools).
 *
 * No restart-survival yet. A killed process loses the batch id;
 * follow-up `Provider.attachBatch(id)` lands separately once the
 * primary surface is in.
 */
object OpenAIBatch {

  /** OpenAI's documented per-batch ceiling (lines, not file bytes). The
    * 200MB byte ceiling is enforced by upload; we chunk by line count
    * since that's the dominant constraint for typical text + small
    * image-URL bodies. */
  val MaxRequestsPerBatch: Int = 50_000

  /** Number of concurrent in-flight batches per `batch(...)` call. A
    * higher number cuts wall-clock when the input stream is large
    * enough to fill multiple chunks, at the cost of more files in
    * the OpenAI account simultaneously. */
  val MaxConcurrentBatches: Int = 4

  /** Initial poll interval. OpenAI batches that complete quickly
    * (small chat-completions batches, well within rate caps) often
    * settle within 30-90 seconds — fast first polls capture them
    * without holding the consumer waiting on a fixed long delay. */
  val InitialPollInterval: FiniteDuration = 5.seconds

  /** Hard cap on the poll interval — exponential backoff doubles the
    * wait between checks but never exceeds this. 60s gives a 24-hour
    * batch only ~1500 polls maximum across the lifetime. */
  val MaxPollInterval: FiniteDuration = 60.seconds

  /** Render a single request as one JSONL line for the OpenAI Batch
    * input file. The body uses `/v1/chat/completions` shape (the
    * Batch API's most reliable endpoint surface at this writing). */
  def renderJsonlLine(request: OneShotRequest): String = {
    val modelName = stripNamespacePrefix(request.model._id.value)
    val systemMsg = obj(
      "role" -> str("system"),
      "content" -> str(request.systemPrompt)
    )
    val userContent: Vector[Json] =
      if (request.userContent.isEmpty)
        Vector(obj("type" -> str("text"), "text" -> str(request.userPrompt)))
      else
        request.userContent.flatMap(rcToOpenAIContent)
    val userMsg = obj(
      "role" -> str("user"),
      "content" -> arr(userContent*)
    )
    val baseBody = Vector[(String, Json)](
      "model"    -> str(modelName),
      "messages" -> arr(systemMsg, userMsg)
    )
    val genFields: Vector[(String, Json)] = {
      val gs = request.generationSettings
      val maxTokens = gs.effectiveCap match {
        case sigil.provider.OutputTokenCap.Below(n) => Vector(("max_completion_tokens", num(n)))
        case _                                       => Vector.empty
      }
      val temp = gs.temperature.map(t => ("temperature", num(t))).toVector
      maxTokens ++ temp
    }
    val body = obj((baseBody ++ genFields)*)
    val line = obj(
      "custom_id" -> str(request.requestId.value),
      "method"    -> str("POST"),
      "url"       -> str("/v1/chat/completions"),
      "body"      -> body
    )
    JsonFormatter.Compact(line)
  }

  /** Convert a [[ResponseContent]] block into one or more OpenAI
    * chat-completions content entries. Text → `text` entry. Image
    * (URL) → `image_url` entry. ImageBytes → `image_url` carrying a
    * `data:` URL (the bytes inlined). Other variants flatten through
    * markdown to a text entry. */
  private def rcToOpenAIContent(rc: ResponseContent): Vector[Json] = rc match {
    case ResponseContent.Text(t) =>
      Vector(obj("type" -> str("text"), "text" -> str(t)))
    case ResponseContent.Image(url, _) =>
      Vector(obj(
        "type" -> str("image_url"),
        "image_url" -> obj("url" -> str(url.toString))
      ))
    case ResponseContent.ImageBytes(mt, b64, _) =>
      Vector(obj(
        "type" -> str("image_url"),
        "image_url" -> obj("url" -> str(s"data:$mt;base64,$b64"))
      ))
    case other =>
      Vector(obj(
        "type" -> str("text"),
        "text" -> str(sigil.render.MarkdownRenderer.renderBlock(other))
      ))
  }

  /** Parse one JSONL line from the OpenAI Batch output file into a
    * [[OneShotResponse]]. Each line carries `custom_id` (our request
    * id), `response.body` (a chat-completions response) on success,
    * or `error` on per-request failure. */
  def parseResultLine(line: String): Option[OneShotResponse] = {
    if (line.trim.isEmpty) return None
    scala.util.Try(JsonParser(line)).toOption.flatMap { json =>
      json.get("custom_id").map(_.asString).map { customId =>
        val reqId = lightdb.id.Id[sigil.provider.ProviderRequest](customId)
        json.get("error").filter(_ != fabric.Null) match {
          case Some(errJson) =>
            val msg  = errJson.get("message").map(_.asString).getOrElse("unknown batch error")
            val code = errJson.get("code").map(_.asString)
            OneShotResponse(
              requestId = reqId,
              error     = Some(OneShotResponse.Error(message = msg, code = code, recoverable = false))
            )
          case None =>
            json.get("response").flatMap(_.get("body")) match {
              case Some(body) =>
                val text = body.get("choices")
                  .flatMap(_.asVector.headOption)
                  .flatMap(_.get("message"))
                  .flatMap(_.get("content"))
                  .map(_.asString)
                  .getOrElse("")
                val usage = body.get("usage").map { u =>
                  TokenUsage(
                    promptTokens     = u.get("prompt_tokens").map(_.asInt).getOrElse(0),
                    completionTokens = u.get("completion_tokens").map(_.asInt).getOrElse(0),
                    totalTokens      = u.get("total_tokens").map(_.asInt).getOrElse(0)
                  )
                }
                OneShotResponse(
                  requestId = reqId,
                  content   = if (text.isEmpty) Vector.empty else Vector(ResponseContent.Text(text)),
                  usage     = usage
                )
              case None =>
                OneShotResponse(
                  requestId = reqId,
                  error     = Some(OneShotResponse.Error(
                    message     = "batch result line carried neither response.body nor error",
                    recoverable = false
                  ))
                )
            }
        }
      }
    }
  }

  /** Strip the provider namespace prefix from a model id (`openai/gpt-4o` → `gpt-4o`).
    * Mirrors the same helper inside [[OpenAIProvider]]; duplicated here to
    * avoid a private-access dependency. */
  private def stripNamespacePrefix(modelId: String): String = {
    val prefix = "openai/"
    if (modelId.toLowerCase.startsWith(prefix)) modelId.drop(prefix.length) else modelId
  }

  /** Submit one chunk of requests as a single OpenAI batch, poll
    * until completion, return the parsed responses as a stream. The
    * polling loop runs inside the returned Task; results stream out
    * only after the batch completes. */
  def submitChunk(chunk: List[OneShotRequest],
                  apiKey: String,
                  baseUrl: URL): Stream[OneShotResponse] =
    if (chunk.isEmpty) Stream.empty
    else Stream.force(runChunk(chunk, apiKey, baseUrl).map(Stream.emits))

  private def runChunk(chunk: List[OneShotRequest],
                       apiKey: String,
                       baseUrl: URL): Task[List[OneShotResponse]] = {
    val jsonl = chunk.iterator.map(renderJsonlLine).mkString("\n") + "\n"
    val customIds = chunk.map(_.requestId).toSet
    for {
      inputFileId  <- uploadJsonl(jsonl, apiKey, baseUrl)
      batchId      <- createBatch(inputFileId, apiKey, baseUrl)
      finalState   <- pollUntilTerminal(batchId, apiKey, baseUrl)
      results      <- finalState.outputFileId match {
        case Some(outId) =>
          downloadFile(outId, apiKey, baseUrl).map { jsonlOut =>
            jsonlOut.split('\n').iterator.flatMap(parseResultLine).toList
          }
        case None =>
          // No output file — the whole batch failed at the wire level.
          // Surface a fail-shaped OneShotResponse per request so the
          // consumer's stream stays consistent (every input gets exactly
          // one output) rather than silently dropping the chunk.
          Task.pure(chunk.map { r =>
            OneShotResponse(
              requestId = r.requestId,
              error     = Some(OneShotResponse.Error(
                message     = finalState.errorSummary.getOrElse(s"batch finished with no output (status=${finalState.status})"),
                code        = Some(finalState.status),
                recoverable = finalState.status == "expired"
              ))
            )
          })
      }
      _ <- bestEffortDelete(inputFileId, apiKey, baseUrl)
      _ <- finalState.outputFileId.map(id => bestEffortDelete(id, apiKey, baseUrl)).getOrElse(Task.unit)
      // Fill in any request whose `custom_id` didn't appear in the
      // output (defensive — OpenAI shouldn't drop entries, but if a
      // future API change does we don't want consumers to wait forever
      // for missing responses).
      seenIds = results.map(_.requestId).toSet
      missing = customIds.diff(seenIds).toList.map { rid =>
        OneShotResponse(
          requestId = rid,
          error     = Some(OneShotResponse.Error(
            message     = "request did not appear in batch output",
            recoverable = false
          ))
        )
      }
    } yield results ++ missing
  }

  /** Upload the JSONL string via `/v1/files` with `purpose=batch`.
    * Returns the upstream file id. Writes to a temp file because
    * spice's `FormDataContent.withFile` requires a `File` — the
    * temp file is deleted in the same call. */
  def uploadJsonl(jsonl: String, apiKey: String, baseUrl: URL): Task[String] = Task {
    val temp = File.createTempFile("openai-batch-", ".jsonl")
    try {
      Files.writeString(temp.toPath, jsonl, StandardCharsets.UTF_8)
      val form = FormDataContent
        .withFile("file", "batch.jsonl", temp)
        .withString("purpose", "batch")
      HttpRequest(
        method  = HttpMethod.Post,
        url     = baseUrl.withPath("/v1/files"),
        content = Some(form)
      ).withHeader("Authorization", s"Bearer $apiKey")
    } finally temp.delete()
  }.flatMap { req =>
    HttpClient.modify(_ => req).noFailOnHttpStatus.send().flatMap { resp =>
      resp.content match {
        case Some(c) =>
          c.asString.flatMap { body =>
            if (resp.status.isSuccess) Task {
              JsonParser(body).get("id").map(_.asString).getOrElse {
                throw new RuntimeException(s"OpenAI Batch: /v1/files response missing `id` field: $body")
              }
            }
            else Task.error(new RuntimeException(s"OpenAI Batch: file upload failed (${resp.status}): $body"))
          }
        case None =>
          Task.error(new RuntimeException(s"OpenAI Batch: /v1/files returned empty body (${resp.status})"))
      }
    }
  }

  /** Create the batch via `/v1/batches` with the uploaded file id. */
  def createBatch(inputFileId: String, apiKey: String, baseUrl: URL): Task[String] = {
    val body = obj(
      "input_file_id"     -> str(inputFileId),
      "endpoint"          -> str("/v1/chat/completions"),
      "completion_window" -> str("24h")
    )
    val req = HttpRequest(
      method  = HttpMethod.Post,
      url     = baseUrl.withPath("/v1/batches"),
      content = Some(StringContent(JsonFormatter.Compact(body), ContentType.`application/json`))
    ).withHeader("Authorization", s"Bearer $apiKey")
    HttpClient.modify(_ => req).noFailOnHttpStatus.send().flatMap { resp =>
      resp.content match {
        case Some(c) => c.asString.flatMap { resBody =>
          if (resp.status.isSuccess) Task {
            JsonParser(resBody).get("id").map(_.asString).getOrElse {
              throw new RuntimeException(s"OpenAI Batch: /v1/batches response missing `id`: $resBody")
            }
          }
          else Task.error(new RuntimeException(s"OpenAI Batch: batch creation failed (${resp.status}): $resBody"))
        }
        case None => Task.error(new RuntimeException(s"OpenAI Batch: /v1/batches returned empty body (${resp.status})"))
      }
    }
  }

  /** Terminal batch states from OpenAI. */
  private val TerminalStates: Set[String] = Set("completed", "failed", "expired", "cancelled")

  /** Snapshot of a batch's poll-state — the status and the
    * downloadable output file id when present. */
  case class BatchState(status: String,
                        outputFileId: Option[String],
                        errorFileId: Option[String],
                        errorSummary: Option[String])

  /** Poll the batch until it reaches a terminal state. Exponential
    * backoff between polls, capped at [[MaxPollInterval]]. */
  def pollUntilTerminal(batchId: String, apiKey: String, baseUrl: URL): Task[BatchState] = {
    def loop(delay: FiniteDuration): Task[BatchState] =
      getBatchState(batchId, apiKey, baseUrl).flatMap { st =>
        if (TerminalStates.contains(st.status)) Task.pure(st)
        else Task.sleep(delay).flatMap(_ =>
          loop((delay * 2).min(MaxPollInterval))
        )
      }
    loop(InitialPollInterval)
  }

  private def getBatchState(batchId: String, apiKey: String, baseUrl: URL): Task[BatchState] = {
    val req = HttpRequest(
      method = HttpMethod.Get,
      url    = baseUrl.withPath(s"/v1/batches/$batchId")
    ).withHeader("Authorization", s"Bearer $apiKey")
    HttpClient.modify(_ => req).noFailOnHttpStatus.send().flatMap { resp =>
      resp.content match {
        case Some(c) => c.asString.flatMap { body =>
          if (resp.status.isSuccess) Task {
            val json = JsonParser(body)
            BatchState(
              status       = json.get("status").map(_.asString).getOrElse("unknown"),
              outputFileId = json.get("output_file_id").filter(_ != fabric.Null).map(_.asString),
              errorFileId  = json.get("error_file_id").filter(_ != fabric.Null).map(_.asString),
              errorSummary = json.get("errors")
                .flatMap(_.get("data"))
                .flatMap(_.asVector.headOption)
                .flatMap(_.get("message"))
                .map(_.asString)
            )
          }
          else Task.error(new RuntimeException(s"OpenAI Batch: /v1/batches/$batchId GET failed (${resp.status}): $body"))
        }
        case None => Task.error(new RuntimeException(s"OpenAI Batch: /v1/batches/$batchId returned empty body"))
      }
    }
  }

  /** Download a file's content as a UTF-8 string. */
  def downloadFile(fileId: String, apiKey: String, baseUrl: URL): Task[String] = {
    val req = HttpRequest(
      method = HttpMethod.Get,
      url    = baseUrl.withPath(s"/v1/files/$fileId/content")
    ).withHeader("Authorization", s"Bearer $apiKey")
    HttpClient.modify(_ => req).noFailOnHttpStatus.send().flatMap { resp =>
      resp.content match {
        case Some(c) =>
          c.asString.flatMap { body =>
            if (resp.status.isSuccess) Task.pure(body)
            else Task.error(new RuntimeException(s"OpenAI Batch: file download failed (${resp.status}): $body"))
          }
        case None => Task.error(new RuntimeException(s"OpenAI Batch: file download returned empty body (${resp.status})"))
      }
    }
  }

  /** Delete an uploaded file; failures are swallowed (the batch's
    * primary outputs are already in hand and the file would expire
    * automatically anyway). */
  def bestEffortDelete(fileId: String, apiKey: String, baseUrl: URL): Task[Unit] = {
    val req = HttpRequest(
      method = HttpMethod.Delete,
      url    = baseUrl.withPath(s"/v1/files/$fileId")
    ).withHeader("Authorization", s"Bearer $apiKey")
    HttpClient.modify(_ => req).noFailOnHttpStatus.send()
      .map(_ => ())
      .handleError(_ => Task.unit)
  }
}
