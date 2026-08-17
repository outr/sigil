package sigil.provider.google

import fabric.*
import fabric.io.{JsonFormatter, JsonParser}
import rapid.{Stream, Task}
import sigil.Sigil
import sigil.db.Model
import sigil.provider.*
import sigil.provider.sse.SSELineParser
import sigil.tool.{DefinitionToSchema, Tool, ToolInput, ToolSchema}
import sigil.tool.ToolInput.given
import spice.http.{HttpMethod, HttpRequest, HttpResponse, HttpStatus}
import spice.http.client.HttpClient
import spice.http.content.StringContent
import spice.net.*

import scala.concurrent.duration.*
import scala.util.Success

/**
 * Google Gemini provider. Uses
 * `generativelanguage.googleapis.com/v1beta/models/{model}:streamGenerateContent?alt=sse`
 * with the `x-goog-api-key` header for auth. Gemini's request shape
 * is distinct from OpenAI-style — content blocks with roles
 * `user` / `model`, a separate `systemInstruction`, and
 * `generationConfig` for sampling knobs.
 *
 * Explicit context caching: when [[contextCaching]] is on and the
 * target model supports it, the stable request prefix (system
 * instruction + tool-schema block) is registered once via
 * `cachedContents.create` and referenced on subsequent requests via
 * the `cachedContent` field, which omits the cached content inline and
 * bills it at a steep discount. Prefixes below
 * [[contextCacheMinTokens]] are sent inline — Gemini rejects
 * `cachedContents.create` for content below a minimum token count.
 */
case class GoogleProvider(apiKey: String,
                          sigilRef: Sigil,
                          baseUrl: URL = url"https://generativelanguage.googleapis.com",
                          /** Per-read idle timeout for the SSE stream. Fires
                            * only when no bytes arrive for the duration —
                            * slow-but-working streams keep going. */
                          tokenIdleTimeout: FiniteDuration = 120.seconds,
                          /** Register the stable request prefix (system
                            * instruction + tool schemas) as a Gemini
                            * `cachedContents` resource and reference it on
                            * subsequent turns so the unchanged prefix bills
                            * at the cache-hit discount. Default ON — every
                            * stable Gemini 2.x model supports it. Set
                            * `false` to disable (e.g. for a vendor mirror
                            * that doesn't honour the field, or to keep all
                            * content inline). */
                          contextCaching: Boolean = true,
                          /** Time-to-live requested when creating a
                            * `cachedContents` resource. Gemini lapses the
                            * resource server-side after this window; the
                            * next turn re-creates. ~10 minutes balances
                            * cache-hit reuse against holding stale prefixes
                            * for conversations that have gone quiet. */
                          contextCacheTtl: FiniteDuration = 10.minutes,
                          /** Minimum estimated prefix token count below
                            * which caching is skipped and the prefix is
                            * sent inline. Gemini's `cachedContents.create`
                            * rejects content below a model-dependent
                            * floor (a few thousand tokens for the 2.x
                            * families); 4096 stays comfortably above the
                            * documented minimums across those models. */
                          contextCacheMinTokens: Int = 4096) extends Provider {
  override def `type`: ProviderType = ProviderType.Google
  override val providerKey: String = Google.Provider
  override protected def sigil: Sigil = sigilRef
  override def schemaDialect: SchemaDialect = SchemaDialect.Gemini

  /** Per-provider-instance registry of live `cachedContents`
    * resources, keyed by stable-prefix hash. Survives across turns for
    * the lifetime of this provider instance. */
  private val contextCache: GeminiContextCache = new GeminiContextCache

  // ---- batch (sigil #299) ----

  /** Sigil #299 — Gemini Batch API supports ~50% cost reduction on
    * the stable 2.x families (Pro, Flash, Flash-Lite) with a 24-hour
    * SLA. Requests inline as `inlinedRequests`; outputs read back
    * from the terminal batch resource's `inlinedResponses`. */
  override def batchSupported: Boolean = true

  override def batch(requests: Stream[OneShotRequest]): Stream[OneShotResponse] =
    requests
      .chunk(GoogleBatch.MaxRequestsPerBatch)
      .flatMap(chunk => GoogleBatch.submitChunk(chunk.toList, apiKey, baseUrl))

  override def call(input: ProviderCall): Stream[ProviderEvent] = {
    val state = new StreamState(new ToolCallAccumulator(input.roster, providerKey = Google.Provider))
    Stream.force(
      for {
        raw         <- httpRequestFor(input)
        intercepted <- sigilRef.wireInterceptor.before(raw)
        handle      <- HttpClient.modify(_ => intercepted).noFailOnHttpStatus.timeout(tokenIdleTimeout).streamLinesHandle()
      } yield {
        // `track` registers the stream's cancel handle so a `Stop`
        // aborts the in-flight call mid-flight instead of draining it.
        val lines = sigilRef.providerStreams.track(input, handle)
        _root_.sigil.provider.debug.StreamWireInterceptor.attach(
          lines, sigilRef.wireInterceptor, intercepted, sigilRef.chunkLogger
        ) { line =>
          Stream.emits(parseLine(line, state))
        }
      }
    )
  }

  override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
    resolveCachedContent(input).map { cached =>
      val modelName = Google.stripProviderPrefix(input.modelId.value)
      val bodyStr = JsonFormatter.Compact(buildBody(input, cached))
      HttpRequest(
        method = HttpMethod.Post,
        url = baseUrl.withPath(s"/v1beta/models/$modelName:streamGenerateContent").withParam("alt", "sse"),
        content = Some(StringContent(bodyStr, ContentType.`application/json`))
      ).withHeader("x-goog-api-key", apiKey)
    }

  /** Render the system-instruction object for the request body — the
    * stable head of the prefix. Only [[ProviderCall.system]] renders
    * here: the volatile per-turn segment rides the `contents` tail (see
    * [[ProviderCall.messagesWithVolatileTail]]) so both Gemini's
    * implicit prefix caching and the explicit `cachedContents` key
    * (hashed from this text) stay stable across turns. Empty when the
    * call carries no system prompt. */
  private def systemInstructionObj(input: ProviderCall): Vector[(String, Json)] = {
    val stable = input.system
    if (stable.isEmpty) Vector.empty
    else Vector("systemInstruction" -> obj("parts" -> arr(obj("text" -> str(stable)))))
  }

  /** Render the `tools` array — custom function declarations grouped
    * into one `functionDeclarations` entry, plus a top-level entry per
    * built-in tool. The function-schema block is part of the stable
    * cacheable prefix. */
  private def renderToolsArr(input: ProviderCall): Vector[Json] = {
    val functionTools =
      if (input.tools.isEmpty) Vector.empty
      else Vector(obj("functionDeclarations" -> arr(input.tools.map(t => toFunctionDeclaration(t, input.currentMode))*)))
    val builtInTools = input.builtInTools.iterator.flatMap(renderBuiltIn).toVector
    functionTools ++ builtInTools
  }

  /** Render the `toolConfig` object derived from the call's
    * [[ToolChoice]]. Returned separately from the tool schemas because
    * `toolConfig` is request-specific (it can pin a function on a
    * given turn) and must stay on the inline request even when the
    * tool schemas themselves are served from a cached resource. */
  private def toolConfigField(input: ProviderCall): Vector[(String, Json)] = {
    val functionCallingConfig: Json = input.toolChoice match {
      case ToolChoice.None     => obj("mode" -> str("NONE"))
      case ToolChoice.Auto     => obj("mode" -> str("AUTO"))
      case ToolChoice.Required => obj("mode" -> str("ANY"))
      case ToolChoice.Specific(name) =>
        // Gemini: pin to a single function via `mode = "ANY"` +
        // `allowedFunctionNames` restricting the surface to one.
        obj(
          "mode"                 -> str("ANY"),
          "allowedFunctionNames" -> arr(str(name.value))
        )
    }
    Vector("toolConfig" -> obj("functionCallingConfig" -> functionCallingConfig))
  }

  /** Build the request body. When `cached` is set, the system
    * instruction and tool schemas are omitted from the inline body and
    * the `cachedContent` field references the resource instead.
    *
    * Gemini disallows mixing `cachedContent` with `system_instruction`,
    * `tools`, OR `tool_config` on a single request — the cached
    * resource owns all three. We honour the per-call `toolConfig`
    * (forced tool_choice etc.) over caching: when tools are present,
    * `cached` is silently ignored and the prefix is sent inline. The
    * cache resource still exists for reuse on future tool-less calls. */
  private def buildBody(input: ProviderCall, cached: Option[GeminiCachedPrefix]): Json = {
    val toolsArr = renderToolsArr(input)

    val effectiveCached = if (toolsArr.isEmpty) cached else None

    val prefixFields: Vector[(String, Json)] = effectiveCached match {
      case Some(prefix) =>
        Vector("cachedContent" -> str(prefix.resourceName))
      case None =>
        val toolsField =
          if (toolsArr.isEmpty) Vector.empty
          else Vector[(String, Json)]("tools" -> arr(toolsArr*))
        systemInstructionObj(input) ++ toolsField
    }

    // Sigil #396 — Gemini requires alternating roles ending in `user`; a
    // trailing content-only `model` turn (an agent's own prior Message as the
    // tail) is invalid. Anchor the tail with a user turn (shared with the
    // Anthropic / llama.cpp prefill guard).
    val contents = renderContents(ProviderMessage.ensureUserAnchor(input.messagesWithVolatileTail))

    // `toolConfig` is request-specific and stays inline (only valid
    // when not paired with `cachedContent`, which is guaranteed by the
    // `effectiveCached` fallback above).
    val toolConfig: Vector[(String, Json)] =
      if (toolsArr.isEmpty) Vector.empty else toolConfigField(input)

    val gen = input.generationSettings
    val genConfig = Vector.newBuilder[(String, Json)]
    gen.temperature.foreach(v => genConfig += ("temperature" -> num(v)))
    gen.explicitWireMaxTokens.foreach(v => genConfig += ("maxOutputTokens" -> num(v)))
    gen.topP.foreach(v => genConfig += ("topP" -> num(v)))
    if (gen.stopSequences.nonEmpty) genConfig += ("stopSequences" -> arr(gen.stopSequences.map(str)*))
    // Gemini 2.5 "thinking": off by default (budget = 0) because thinking
    // tokens are billed against maxOutputTokens and routinely truncate
    // tool-call responses before any function call is emitted. When the
    // caller sets `generationSettings.effort`, we translate that to a
    // positive (or -1 dynamic) budget — UNLESS `reasoningMode = Off`,
    // which forces budget back to 0 (Sigil audit H7 — `Off` must
    // suppress thinking on every provider that supports it).
    val thinkingBudget =
      if (gen.reasoningMode == ReasoningMode.Off) 0
      else gen.effort.fold(0)(Effort.googleThinkingBudget)
    genConfig += ("thinkingConfig" -> obj("thinkingBudget" -> num(thinkingBudget)))
    val genConfigFields: Vector[(String, Json)] =
      Vector("generationConfig" -> obj(genConfig.result()*))

    val base = Vector[(String, Json)]("contents" -> arr(contents*))
    obj((base ++ prefixFields ++ toolConfig ++ genConfigFields)*)
  }

  // ---- explicit context caching ----

  /** Whether explicit context caching is engaged for this call: the
    * provider toggle is on AND the target model supports it. */
  private def cachingEnabledFor(input: ProviderCall): Boolean =
    contextCaching && Google.supportsContextCaching(Google.stripProviderPrefix(input.modelId.value))

  /** Resolve the `cachedContents` resource for this call's stable
    * prefix, if any.
    *
    * Returns `None` — meaning "send the prefix inline" — when caching
    * is disabled, the model is not cache-capable, the call has nothing
    * cacheable (no system prompt and no tools), or the estimated
    * prefix is below [[contextCacheMinTokens]]. Otherwise it consults
    * the in-process [[contextCache]]: a live entry is reused directly;
    * a miss triggers a `cachedContents.create` call whose returned
    * resource name is stored and then referenced.
    *
    * A failed create is swallowed — the call falls back to sending the
    * prefix inline rather than failing the turn, so a transient
    * cache-API hiccup never blocks a generation. */
  private def resolveCachedContent(input: ProviderCall): Task[Option[GeminiCachedPrefix]] = {
    if (!cachingEnabledFor(input)) Task.pure(None)
    else {
      val systemObj = systemInstructionObj(input)
      val toolsArr = renderToolsArr(input)
      if (systemObj.isEmpty && toolsArr.isEmpty) Task.pure(None)
      else {
        // The prefix payload is exactly what would be sent inline —
        // hash it for the cache key and estimate its token cost.
        val systemText = input.system
        val toolsBlock = if (toolsArr.isEmpty) "" else JsonFormatter.Compact(arr(toolsArr*))
        val estimatedTokens = tokenizer.count(systemText) + tokenizer.count(toolsBlock)
        if (estimatedTokens < contextCacheMinTokens) Task.pure(None)
        else {
          val key = GeminiContextCache.hashOf(systemText, toolsBlock)
          contextCache.lookup(key) match {
            case Some(live) => Task.pure(Some(live))
            case None =>
              createCachedContent(input, key, systemObj, toolsArr)
                .map(Some(_))
                .handleError(_ => Task.pure(None))
          }
        }
      }
    }
  }

  /** POST the stable prefix to `cachedContents.create`, store the
    * returned resource, and yield the new [[GeminiCachedPrefix]]. The
    * cached resource carries the same `model`, `systemInstruction`,
    * and `tools` the inline request would otherwise have sent. */
  private def createCachedContent(input: ProviderCall,
                                  key: GeminiCacheKey,
                                  systemObj: Vector[(String, Json)],
                                  toolsArr: Vector[Json]): Task[GeminiCachedPrefix] = {
    val modelName = Google.stripProviderPrefix(input.modelId.value)
    val toolsField: Vector[(String, Json)] =
      if (toolsArr.isEmpty) Vector.empty else Vector("tools" -> arr(toolsArr*))
    // Gemini's `cachedContents.create` keys TTL as a duration string
    // of fractional seconds (e.g. "600s").
    val ttlSeconds = math.max(1L, contextCacheTtl.toSeconds)
    val body = obj(
      (Vector[(String, Json)](
        "model" -> str(s"models/$modelName"),
        "ttl"   -> str(s"${ttlSeconds}s")
      ) ++ systemObj ++ toolsField)*
    )
    HttpClient
      .url(baseUrl.withPath("/v1beta/cachedContents"))
      .header("x-goog-api-key", apiKey)
      .noFailOnHttpStatus
      .post
      .content(StringContent(JsonFormatter.Compact(body), ContentType.`application/json`))
      .send()
      .flatMap { response =>
        response.content match {
          case Some(content) =>
            content.asString.flatMap { raw =>
              Task {
                val parsed = JsonParser(raw)
                val status = response.status.code
                if (status >= 400 || parsed.get("error").exists(!_.isNull)) {
                  val msg = parsed.get("error").flatMap(_.get("message")).map(_.asString).getOrElse(raw)
                  throw new RuntimeException(s"Gemini cachedContents.create failed (HTTP $status): $msg")
                }
                val resourceName = parsed.get("name").map(_.asString).getOrElse {
                  throw new RuntimeException(s"Gemini cachedContents.create returned no resource name: $raw")
                }
                contextCache.store(key, resourceName, contextCacheTtl)
              }
            }
          case None =>
            Task.error(new RuntimeException("Gemini cachedContents.create returned an empty response"))
        }
      }
  }

  private def renderContents(messages: Vector[ProviderMessage]): Vector[Json] =
    messages.flatMap {
      case ProviderMessage.System(content) =>
        // Mid-conversation system frames — fold into a user message with a marker.
        Vector(obj("role" -> str("user"), "parts" -> arr(obj("text" -> str(s"[system] $content")))))

      case ProviderMessage.User(blocks) =>
        val parts = blocks.map {
          case MessageContent.Text(t) =>
            obj("text" -> str(t))
          case MessageContent.Image(u, _, _) =>
            // Gemini accepts two image shapes:
            //   - `fileData{fileUri, mimeType}` for files uploaded via
            //     the File API (returns `gs://` or
            //     `https://generativelanguage.googleapis.com/...` URIs).
            //   - `inlineData{mimeType, data: <base64>}` for inline bytes
            //     (use [[MessageContent.ImageBytes]] for that path).
            // Arbitrary HTTPS URLs as `fileUri` are accepted by some
            // Gemini endpoints but not all — apps that hit a 400 should
            // pre-upload via the File API and pass the returned URI.
            GoogleProvider.renderImageUrl(u)
          case MessageContent.ImageBytes(mediaType, base64, _, _) =>
            // Sigil #382 — Gemini has no native `detail` flag; bytes are
            // already downscaled to the quality tier upstream.
            obj("inlineData" -> obj("mimeType" -> str(mediaType), "data" -> str(base64)))
        }
        Vector(obj("role" -> str("user"), "parts" -> arr(parts*)))

      case ProviderMessage.Assistant(content, toolCalls) =>
        val parts = Vector.newBuilder[Json]
        if (content.nonEmpty) parts += obj("text" -> str(content))
        toolCalls.foreach { tc =>
          val args = scala.util.Try(fabric.io.JsonParser(tc.argsJson)).toOption.getOrElse(obj())
          parts += obj("functionCall" -> obj("name" -> str(tc.name), "args" -> args))
        }
        Vector(obj("role" -> str("model"), "parts" -> arr(parts.result()*)))

      case ProviderMessage.ToolResult(toolCallId, content) =>
        Vector(obj(
          "role" -> str("user"),
          "parts" -> arr(obj("functionResponse" -> obj(
            "name" -> str(toolCallId), // Gemini keys responses by name, not id
            "response" -> obj("output" -> str(content))
          )))
        ))

     case _: ProviderMessage.Reasoning =>
        // Provider-specific reasoning state from another provider's turn
        Vector.empty
    }

  /** Gemini's function-calling path is natively grammar-constrained —
    * the model emits args matching the parameters schema by virtue of
    * the function-call output mechanism, so an explicit `strict: true`
    * isn't required. The schema must still be the supported subset:
    * we strip `additionalProperties` (Gemini's validator rejects it)
    * and the unsupported keywords (`pattern`, `format`,
    * `minLength`/`maxLength`/numeric bounds) that don't compose with
    * token-level decoding. The latter are also stripped on OpenAI
    * strict mode — sigil preserves them on the Scala types for
    * post-decode validation. */
  private def toFunctionDeclaration(t: Tool, mode: Mode): Json = {
    val s = t.schema
    obj(
      "name"        -> str(s.name.value),
      "description" -> str(ToolDescriptionRenderer.render(t, mode, sigil)),
      "parameters"  -> schemaDialect(t)
    )
  }

  private def renderBuiltIn(tool: BuiltInTool): Option[Json] = tool match {
    case BuiltInTool.WebSearch => Some(obj("googleSearch" -> obj()))
    case BuiltInTool.CodeInterpreter => Some(obj("codeExecution" -> obj()))
    case _ => None
  }

  // ---- response parsing ----

  private[google] def parseLine(line: String, state: StreamState): Vector[ProviderEvent] =
    SSELineParser.dispatch(line)(
      onData = json => parseChunk(json, state),
      onDone = state.flushDone()
    )

  /** Parse a Gemini streamed chunk. Each chunk is a `GenerateContentResponse`
    * JSON object with `candidates`, optional `usageMetadata`, and
    * optional `finishReason` on a candidate. */
  private def parseChunk(json: Json, state: StreamState): Vector[ProviderEvent] = {
   // Handle error objects embedded in 200-OK streams (e.g. quota / safety pipeline failures
    // that don't fit `finishReason`). Throw a ProviderStreamException so the agent loop's handler
    // renders a user-visible Failure Message rather than dropping the chunk silently.
    json.get("error").foreach { err =>
      if (!err.isNull) {
        val code = err.get("code").map(_.asInt).getOrElse(0)
        val msg  = err.get("message").map(_.asString).getOrElse("(no message)")
        val typ  = err.get("status").map(_.asString).getOrElse("error")
        val metadata = ProviderErrorMetadata(errorType = Some(typ))
        throw new ProviderStreamException(
          providerKey = Google.Provider, code = code, typ = typ, message_ = msg,
          status = if (code > 0) Some(code) else None, errorMetadata = Some(metadata)
        )
      }
    }
    val events = Vector.newBuilder[ProviderEvent]
    val candidate = json.get("candidates").flatMap(_.asVector.headOption)

    candidate.foreach { cand =>
      cand.get("content").flatMap(_.get("parts")).foreach { parts =>
        parts.asVector.foreach { part =>
          part.get("text").foreach { t =>
            if (!t.isNull) {
              val text = t.asString
              if (text.nonEmpty) {
                // Ensure a Text content block is open for orchestrator routing.
                if (!state.textBlockOpen) {
                  state.textBlockOpen = true
                  events += ProviderEvent.ContentBlockStart(state.textCallId, "Text", None)
                }
                events += ProviderEvent.ContentBlockDelta(state.textCallId, text)
              }
            }
          }
          part.get("functionCall").foreach { fc =>
            if (!fc.isNull) {
              val name = fc.get("name").map(_.asString).getOrElse("")
              val args = fc.get("args").map(a => JsonFormatter.Compact(a)).getOrElse("{}")
              val idx = state.nextFunctionIndex
              state.nextFunctionIndex += 1
              val callId = CallId(s"g-fc-$idx")
              events ++= state.acc.start(idx, callId, name)
              events ++= state.acc.appendArgs(idx, args)
              state.sawFunctionCall = true
              state.completedIndexes += idx
            }
          }
        }
      }
      cand.get("finishReason").foreach { reason =>
        if (!reason.isNull) {
          val mapped = reason.asString match {
            case "STOP"          => StopReason.Complete
            case "MAX_TOKENS"    => StopReason.MaxTokens
            case "SAFETY" | "RECITATION" | "BLOCKLIST" | "PROHIBITED_CONTENT" | "SPII" => StopReason.ContentFiltered
            case other =>
              scribe.warn(s"Unmapped finishReason from Gemini: '$other' — treating as Complete")
              StopReason.Complete
          }
          val stopReason = if (state.sawFunctionCall) StopReason.ToolCall else mapped
          events ++= state.acc.complete()
          // Gemini terminates the stream at HTTP close; no `[DONE]`
          // sentinel. Stash Done; emit it last (after any Usage that
          // may arrive in the same chunk) so consumers see a
          // stable ordering: deltas → Usage → Done.
          state.pendingDone = Some(stopReason)
        }
      }
    }

    json.get("usageMetadata").foreach { u =>
      if (!u.isNull) events += ProviderEvent.Usage(parseUsage(u))
    }

    // After handling deltas/usage for this chunk, emit any pending
    // Done (set by finishReason on this or an earlier chunk). Once
    // emitted we don't need to re-flush on stream end.
    if (state.pendingDone.isDefined && !state.doneEmitted) {
      events += ProviderEvent.Done(state.pendingDone.get)
      state.doneEmitted = true
    }

    events.result()
  }

  /** Parse Gemini's `usageMetadata` block. The `cacheKeys` argument
    * reads `cachedContentTokenCount` — the count of prompt tokens
    * served from a `cachedContents` resource — into
    * [[TokenUsage.cacheReadTokens]]. Gemini reports no separate
    * cache-creation count on a generation response (the create call is
    * its own request), so `cacheCreationTokens` stays `0`. */
  private def parseUsage(json: Json): TokenUsage =
    TokenUsage.fromJson(
      json,
      "promptTokenCount",
      "candidatesTokenCount",
      Some("totalTokenCount"),
      CacheKeys.Google
    )

  final private[google] class StreamState(val acc: ToolCallAccumulator) {
    val textCallId: CallId = CallId("g-text")
    var textBlockOpen: Boolean = false
    var nextFunctionIndex: Int = 0
    var completedIndexes: Set[Int] = Set.empty
    var sawFunctionCall: Boolean = false
    var pendingDone: Option[StopReason] = None
    var doneEmitted: Boolean = false

    def flushDone(): Vector[ProviderEvent] =
      if (doneEmitted) Vector.empty
      else pendingDone match {
        case Some(sr) => pendingDone = None; doneEmitted = true; Vector(ProviderEvent.Done(sr))
        case None     => doneEmitted = true; Vector(ProviderEvent.Done(StopReason.Complete))
      }
  }
}

object GoogleProvider {
  def create(sigil: Sigil, apiKey: String, baseUrl: URL = url"https://generativelanguage.googleapis.com"): Task[GoogleProvider] =
    Task.pure(GoogleProvider(apiKey, sigil, baseUrl))

  /**
   * Render a Sigil `MessageContent.Image` URL into a Gemini parts entry.
   *
   * Emits `fileData{fileUri, mimeType}` with the MIME sniffed from the
   * URL extension (defaulting to `image/jpeg`). Apps passing public
   * HTTPS images that aren't File API URIs may hit a Gemini validation
   * error; the recommended path is to upload via the File API and pass
   * the returned URI here. For inline bytes use
   * [[sigil.provider.MessageContent.ImageBytes]].
   */
  def renderImageUrl(u: URL): Json = {
    val raw = u.toString
    obj("fileData" -> obj("fileUri" -> str(raw), "mimeType" -> str(sniffMimeFromUrl(raw))))
  }

  private def sniffMimeFromUrl(url: String): String = {
    val lower = url.toLowerCase
    val dot = lower.lastIndexOf('.')
    if (dot < 0) "image/jpeg"
    else lower.substring(dot + 1).takeWhile(c => c.isLetterOrDigit) match {
      case "jpg" | "jpeg" => "image/jpeg"
      case "png"          => "image/png"
      case "gif"          => "image/gif"
      case "webp"         => "image/webp"
      case "heic"         => "image/heic"
      case "heif"         => "image/heif"
      case _              => "image/jpeg"
    }
  }
}
