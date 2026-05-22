package sigil.provider.anthropic

import fabric.*
import fabric.io.JsonFormatter
import rapid.{Stream, Task}
import sigil.Sigil
import sigil.db.Model
import sigil.provider.*
import sigil.provider.sse.SSELineParser
import sigil.tokenize.{JtokkitTokenizer, Tokenizer}
import sigil.tool.{DefinitionToSchema, Tool, ToolInput, ToolSchema}
import sigil.tool.ToolInput.given
import spice.http.{HttpMethod, HttpRequest, HttpResponse, HttpStatus}
import spice.http.client.HttpClient
import spice.http.content.StringContent
import spice.net.*

import scala.concurrent.duration.*
import scala.util.Success

/**
 * Anthropic provider targeting the Messages API (`/v1/messages`).
 *
 * Request shape:
 *   - `system`: top-level string
 *   - `messages`: role=user|assistant with typed content blocks
 *   - `tools`: array of {name, description, input_schema}
 *   - `tool_choice`: {type: auto|any|tool|none}
 *   - Built-in tools use `{type: "web_search_20241210"}` shape
 *
 * Streaming uses its own event taxonomy (`message_start`,
 * `content_block_start`, `content_block_delta`, `content_block_stop`,
 * `message_delta`, `message_stop`).
 */
case class AnthropicProvider(apiKey: String,
                             sigilRef: Sigil,
                             baseUrl: URL = url"https://api.anthropic.com",
                             /** Per-read idle timeout for the SSE stream. Fires
                               * only when no bytes arrive for the duration —
                               * slow-but-working streams keep going. */
                             tokenIdleTimeout: FiniteDuration = 120.seconds,
                             /** How to send the credential. Anthropic's direct API
                               * uses `x-api-key` + `anthropic-version`; vendor
                               * mirrors that expose `/v1/messages` (DigitalOcean
                               * Inference for `anthropic-claude-*` models) use the
                               * OpenAI-style `Authorization: Bearer`. The wire
                               * body is identical across both modes — only the
                               * auth + version headers differ. */
                             authMode: AnthropicAuthMode = AnthropicAuthMode.XApiKey,
                             /** Emit `cache_control` breakpoints on the stable
                               * request prefix (tool roster, system prompt, the
                               * stable span of conversation history) so Anthropic
                               * serves the unchanged prefix from its prompt cache.
                               * Default ON — every current Claude model supports
                               * prompt caching. Set `false` to disable (e.g. for
                               * a vendor mirror that doesn't honour the field). */
                             promptCaching: Boolean = true) extends Provider {
  override def `type`: ProviderType = ProviderType.Anthropic
  override val providerKey: String = Anthropic.Provider
  override protected def sigil: Sigil = sigilRef

  /** Anthropic's tokenizer isn't published; cl100k_base is within
    * ~10% of empirical Claude token counts for English prose, which
    * is plenty of accuracy for budget headroom checks. The pre-flight
    * gate errs conservative anyway, and overshoot only triggers
    * extra (already-cheap) shedding stages. */
  override def tokenizer: Tokenizer = JtokkitTokenizer.OpenAIChatGpt

  override def call(input: ProviderCall): Stream[ProviderEvent] = {
    val state = new StreamState(new ToolCallAccumulator(input.tools, providerKey = "anthropic"))
    Stream.force(
      for {
        raw         <- httpRequestFor(input)
        intercepted <- sigilRef.wireInterceptor.before(raw)
        handle      <- HttpClient.modify(_ => intercepted).noFailOnHttpStatus.timeout(tokenIdleTimeout).streamLinesHandle()
      } yield {
        // okhttp's per-read timeout already catches network stalls
        // (no bytes for the duration); slow-but-working streams keep
        // going as long as tokens flow. `track` registers the
        // stream's cancel handle so a `Stop` aborts the call
        // mid-flight instead of draining it to completion.
        val lines = sigilRef.providerStreams.track(input, handle)
        _root_.sigil.provider.debug.StreamWireInterceptor.attach(
          lines, sigilRef.wireInterceptor, intercepted, sigilRef.chunkLogger
        ) { line =>
          Stream.emits(parseLine(line, state))
        }
      }
    )
  }

  override def httpRequestFor(input: ProviderCall): Task[HttpRequest] = Task {
    val bodyStr = JsonFormatter.Compact(buildBody(input))
    val base = HttpRequest(
      method = HttpMethod.Post,
      url = baseUrl.withPath("/v1/messages"),
      content = Some(StringContent(bodyStr, ContentType.`application/json`))
    )
    val authed = authMode match {
      case AnthropicAuthMode.XApiKey =>
        base
          .withHeader("x-api-key", apiKey)
          .withHeader("anthropic-version", Anthropic.ApiVersion)
      case AnthropicAuthMode.Bearer =>
        base.withHeader("Authorization", s"Bearer $apiKey")
    }
    // The 1-hour `cache_control` TTL on the tool / system breakpoints
    // needs the extended-cache-ttl beta opt-in; send it only when
    // caching is actually engaged for this call.
    if (cachingEnabledFor(input))
      authed.withHeader("anthropic-beta", Anthropic.ExtendedCacheTtlBeta)
    else authed
  }

  // ---- request body ----

  /** Whether `cache_control` breakpoints should be emitted for this
    * call: the provider toggle is on AND the target model supports
    * prompt caching. */
  private def cachingEnabledFor(input: ProviderCall): Boolean =
    promptCaching && Anthropic.supportsPromptCaching(Anthropic.stripProviderPrefix(input.modelId.value))

  private def buildBody(input: ProviderCall): Json = {
    val modelName = Anthropic.stripProviderPrefix(input.modelId.value)
    // max_tokens is required by Anthropic's API. Fall back to 4096 if
    // the caller didn't set one.
    val maxTokens = input.generationSettings.maxOutputTokens.getOrElse(4096)

    val caching = cachingEnabledFor(input)
    val messages = renderMessages(input.messages, caching)
    val toolsArr = renderTools(input, caching)

    val base = Vector[(String, Json)](
      "model" -> str(modelName),
      "max_tokens" -> num(maxTokens),
      "messages" -> arr(messages*),
      "stream" -> bool(true)
    )
    // The system prompt is the second cache breakpoint. When caching is
    // on it must be rendered as a content-block array so the trailing
    // block can carry `cache_control`; the plain-string form has no
    // slot for the field. Both encodings are wire-equivalent for the
    // model.
    val systemField: Vector[(String, Json)] =
      if (input.system.isEmpty) Vector.empty
      else if (caching)
        Vector("system" -> arr(obj(
          "type" -> str("text"),
          "text" -> str(input.system),
          "cache_control" -> AnthropicProvider.cacheControl(extendedTtl = true)
        )))
      else Vector("system" -> str(input.system))

    // Anthropic rejects `tool_choice: any|tool` when thinking is on.
    // Downgrade `Required → Auto` silently so the caller can enable
    // thinking without having to reach into the provider's internal
    // tool_choice derivation.
    val effectiveChoice =
      if (input.generationSettings.effort.isDefined && input.toolChoice == ToolChoice.Required)
        ToolChoice.Auto
      else input.toolChoice
    val toolFields: Vector[(String, Json)] = effectiveChoice match {
      case ToolChoice.None if toolsArr.isEmpty => Vector.empty
      case ToolChoice.None =>
        Vector("tools" -> arr(toolsArr*), "tool_choice" -> obj("type" -> str("none")))
      case ToolChoice.Auto =>
        Vector("tools" -> arr(toolsArr*), "tool_choice" -> obj("type" -> str("auto")))
      case ToolChoice.Required =>
        Vector("tools" -> arr(toolsArr*), "tool_choice" -> obj("type" -> str("any")))
      case ToolChoice.Specific(name) =>
        Vector(
          "tools" -> arr(toolsArr*),
          "tool_choice" -> obj("type" -> str("tool"), "name" -> str(name.value))
        )
    }

    val gen = input.generationSettings
    // Anthropic thinking: when enabled, temperature must be 1.0 and
    // top_p/top_k are ignored. Fail fast on a conflicting temperature
    // rather than silently overriding the caller's setting.
    //
    // Sigil audit H7 — `reasoningMode = Off` suppresses thinking even
    // when `effort` is set. Anthropic has no hard off switch on the
    // wire, so "Off" maps to "don't emit the thinking block at all"
    // (the model's default = thinking disabled). This lets a strategy
    // route an Off intent past Anthropic without the caller having to
    // unset `effort` on a per-provider basis.
    val thinkingEnabled = gen.effort.isDefined && gen.reasoningMode != ReasoningMode.Off
    val thinkingField: Vector[(String, Json)] = gen.effort match {
      case None => Vector.empty
      case Some(_) if !thinkingEnabled => Vector.empty
      case Some(e) =>
        gen.temperature.foreach { t =>
          if (t != 1.0) throw new IllegalArgumentException(
            s"Anthropic requires temperature=1.0 when thinking is enabled; got $t. " +
              s"Set temperature=None or =Some(1.0), or disable thinking for this call."
          )
        }
        val budget = Effort.anthropicBudgetTokens(e, maxTokens)
        Vector("thinking" -> obj("type" -> str("enabled"), "budget_tokens" -> num(budget)))
    }
    // When thinking is on, Anthropic ignores top_p — silently omit it
    // to avoid surprising the caller about a value that has no effect.
    val genFields: Vector[(String, Json)] =
      gen.temperature.toVector.map("temperature" -> num(_)) ++
        (if (thinkingEnabled) Vector.empty
         else gen.topP.toVector.map("top_p" -> num(_))) ++
        (if (gen.stopSequences.nonEmpty) Vector("stop_sequences" -> arr(gen.stopSequences.map(str)*)) else Vector.empty)

    obj((base ++ systemField ++ toolFields ++ thinkingField ++ genFields)*)
  }

  private def renderMessages(messages: Vector[ProviderMessage], caching: Boolean): Vector[Json] = {
    val rendered = renderMessageBlocks(messages)
    // Third cache breakpoint: the stable span of conversation history.
    // The final rendered message is the current turn's volatile tail
    // (the latest user / tool-result content the agent is reacting to);
    // everything before it is stable across the next few turns. Anchor
    // `cache_control` on the last content block of the second-to-last
    // message so the cached segment runs tools → system → history up to
    // that point. Skip when there's only the current turn — the tools
    // and system breakpoints already cover the whole stable prefix.
    if (caching && rendered.size >= 2) {
      val breakpointIdx = rendered.size - 2
      rendered.zipWithIndex.map {
        case (msg, idx) if idx == breakpointIdx =>
          AnthropicProvider.withHistoryCacheControl(msg)
        case (msg, _) => msg
      }
    } else rendered
  }

  private def renderMessageBlocks(messages: Vector[ProviderMessage]): Vector[Json] =
    messages.flatMap {
      case ProviderMessage.System(content) =>
        // Anthropic has no system role mid-conversation. Encode as a
        // user message with a marker prefix — matches the pattern the
        // OpenAI Responses adapter uses for the same case.
        Vector(obj(
          "role" -> str("user"),
          "content" -> arr(obj("type" -> str("text"), "text" -> str(s"[system] $content")))
        ))

      case ProviderMessage.User(blocks) =>
        val contentItems = blocks.map {
          case MessageContent.Text(t) =>
            obj("type" -> str("text"), "text" -> str(t))
          case MessageContent.Image(u, _) =>
            obj(
              "type" -> str("image"),
              "source" -> obj("type" -> str("url"), "url" -> str(u.toString))
            )
          case MessageContent.ImageBytes(mediaType, base64, _) =>
            obj(
              "type" -> str("image"),
              "source" -> obj(
                "type" -> str("base64"),
                "media_type" -> str(mediaType),
                "data" -> str(base64)
              )
            )
        }
        Vector(obj("role" -> str("user"), "content" -> arr(contentItems*)))

      case ProviderMessage.Assistant(content, toolCalls) =>
        val blocks = Vector.newBuilder[Json]
        if (content.nonEmpty)
          blocks += obj("type" -> str("text"), "text" -> str(content))
        toolCalls.foreach { tc =>
          val args = scala.util.Try(fabric.io.JsonParser(tc.argsJson)).toOption.getOrElse(obj())
          blocks += obj(
            "type" -> str("tool_use"),
            "id" -> str(tc.id),
            "name" -> str(tc.name),
            "input" -> args
          )
        }
        Vector(obj("role" -> str("assistant"), "content" -> arr(blocks.result()*)))

      case ProviderMessage.ToolResult(toolCallId, content) =>
        Vector(obj(
          "role" -> str("user"),
          "content" -> arr(obj(
            "type" -> str("tool_result"),
            "tool_use_id" -> str(toolCallId),
            "content" -> str(content)
          ))
        ))

      case _: ProviderMessage.Reasoning =>
        // Provider-specific reasoning state from another provider's turn
        // (bug #61 — currently OpenAI-only). Anthropic's request format
        // has no slot for it; drop silently.
        Vector.empty
    }

  private def renderTools(input: ProviderCall, caching: Boolean): Vector[Json] = {
    val fns = input.tools.map { t =>
      val s = t.schema
      // Anthropic has no `strict` flag for tools — generation isn't
      // grammar-constrained, and the schema's `pattern`/`format`/length
      // bounds are advisory at best (the model may produce values that
      // violate them). Sigil's safety net is two-layered: strip the
      // unsupported keywords from the wire schema (same as OpenAI
      // strict mode and Gemini) so the API can't reject the schema for
      // unrecognized keys, and rely on `ToolInputValidator` (run by the
      // `ToolCallAccumulator` for every provider) to re-check the
      // parsed args against those constraints post-decode.
      Vector[(String, Json)](
        "name"         -> str(s.name.value),
        "description"  -> str(ToolDescriptionRenderer.render(t, input.currentMode, sigil)),
        "input_schema" -> StrictSchema.forAnthropic(DefinitionToSchema(s.input))
      )
    }
    val builtIn = input.builtInTools.iterator.flatMap(renderBuiltIn).map(_.asObj.value.toVector).toVector
    val all = fns ++ builtIn
    // First cache breakpoint: the tool roster. `cache_control` rides on
    // the final tool object so the whole roster forms one cache
    // segment. Extended 1-hour TTL: the tool schema is the most stable
    // part of the request.
    if (caching && all.nonEmpty) {
      val withBreakpoint = all.init :+
        (all.last :+ ("cache_control" -> AnthropicProvider.cacheControl(extendedTtl = true)))
      withBreakpoint.map(fields => obj(fields*))
    } else all.map(fields => obj(fields*))
  }

  private def renderBuiltIn(tool: BuiltInTool): Option[Json] = tool match {
    case BuiltInTool.WebSearch =>
      Some(obj(
        "type" -> str("web_search_20250305"),
        "name" -> str("web_search"),
        "max_uses" -> num(3)
      ))
    case _ => None // Anthropic doesn't ship the others as built-ins.
  }

  // ---- response parsing ----

  // `private[anthropic]` rather than `private` so canned-SSE decode
  // specs in this package can drive the streaming parser without a
  // live Anthropic round-trip.
  private[anthropic] def parseLine(line: String, state: StreamState): Vector[ProviderEvent] =
    SSELineParser.dispatch(line)(
      onData = json => parseEvent(json, state),
      onDone = state.flushDone()
    )

  private def parseEvent(json: Json, state: StreamState): Vector[ProviderEvent] = {
    val eventType = json.get("type").map(_.asString).getOrElse("")
    eventType match {
      case "content_block_start" =>
        val index = json.get("index").map(_.asInt).getOrElse(0)
        val block = json.get("content_block").getOrElse(Obj.empty)
        val blockType = block.get("type").map(_.asString).getOrElse("")
        blockType match {
          case "tool_use" =>
            val id = block.get("id").map(_.asString).getOrElse(s"anthro-$index")
            val name = block.get("name").map(_.asString).getOrElse("")
            state.sawFunctionCall = true
            state.indexToCallId += (index -> CallId(id))
            state.acc.start(index, CallId(id), name)
          case "text" =>
            val callId = CallId(s"anthro-text-$index")
            state.indexToCallId += (index -> callId)
            Vector(ProviderEvent.ContentBlockStart(callId, "Text", None))
          case "thinking" =>
            Vector.empty
          case "server_tool_use" =>
            val callId = CallId(s"anthro-st-$index")
            state.indexToCallId += (index -> callId)
            val mapped = BuiltInTool.WebSearch
            Vector(ProviderEvent.ServerToolStart(callId, mapped, None))
          case "web_search_tool_result" =>
            val callId = state.indexToCallId.getOrElse(index, CallId(s"anthro-wsr-$index"))
            Vector(ProviderEvent.ServerToolComplete(callId, BuiltInTool.WebSearch))
          case _ => Vector.empty
        }

      case "content_block_delta" =>
        val index = json.get("index").map(_.asInt).getOrElse(0)
        val delta = json.get("delta").getOrElse(Obj.empty)
        val deltaType = delta.get("type").map(_.asString).getOrElse("")
        deltaType match {
          case "text_delta" =>
            val text = delta.get("text").map(_.asString).getOrElse("")
            if (text.isEmpty) Vector.empty
            else {
              val callId = state.indexToCallId.getOrElse(index, CallId(s"anthro-text-$index"))
              Vector(ProviderEvent.ContentBlockDelta(callId, text))
            }
          case "input_json_delta" =>
            val partial = delta.get("partial_json").map(_.asString).getOrElse("")
            state.acc.appendArgs(index, partial)
          case "thinking_delta" =>
            val text = delta.get("thinking").map(_.asString).getOrElse("")
            if (text.isEmpty) Vector.empty
            else Vector(ProviderEvent.ThinkingDelta(text))
          case _ => Vector.empty
        }

      case "content_block_stop" =>
        // Individual block close. The tool-call accumulator flushes on
        // message_delta's `stop_reason`, so no-op here.
        Vector.empty

      case "message_delta" =>
        val delta = json.get("delta").getOrElse(Obj.empty)
        val stopReason = delta.get("stop_reason").map(_.asString)
        val usage = json.get("usage").map(parseUsage)
        val usageEv = usage.toVector.map(ProviderEvent.Usage(_))
        stopReason match {
          case Some(s) =>
            val mapped = s match {
              case "end_turn"    => StopReason.Complete
              case "max_tokens"  => StopReason.MaxTokens
              case "tool_use"    => StopReason.ToolCall
              case "stop_sequence" => StopReason.Complete
              case _             => StopReason.Complete
            }
            state.pendingDone = Some(mapped)
            usageEv
          case None => usageEv
        }

      case "message_stop" =>
        val completes = state.acc.complete()
        val done = state.pendingDone.getOrElse(if (state.sawFunctionCall) StopReason.ToolCall else StopReason.Complete)
        completes :+ ProviderEvent.Done(done)

      case "ping" | "message_start" =>
        Vector.empty

      case "error" =>
        // Bug #8 — Anthropic embeds unrecoverable mid-stream failures
        // (overloaded_error, etc.) as `event: error\ndata: {...}` on a
        // 200-OK SSE stream. Throw a ProviderStreamException so
        // runAgentLoop's handler (Bug #6) surfaces a user-visible
        // Failure Message instead of leaving the chat with the
        // "(agent completed without a reply)" placeholder.
        val err  = json.get("error").getOrElse(Obj.empty)
        val msg  = err.get("message").map(_.asString).getOrElse("unknown error")
        val typ  = err.get("type").map(_.asString).getOrElse("error")
        val metadata = ProviderErrorMetadata(errorType = Some(typ))
        throw new ProviderStreamException(
          providerKey = Anthropic.Provider, code = 0, typ = typ, message_ = msg,
          status = None, errorMetadata = Some(metadata)
        )

      case _ => Vector.empty
    }
  }

  private def parseUsage(json: Json): TokenUsage =
    TokenUsage.fromJson(json, "input_tokens", "output_tokens", cacheKeys = CacheKeys.Anthropic)

  final private[anthropic] class StreamState(val acc: ToolCallAccumulator) {
    var indexToCallId: Map[Int, CallId] = Map.empty
    var pendingDone: Option[StopReason] = None
    var sawFunctionCall: Boolean = false

    def flushDone(): Vector[ProviderEvent] = pendingDone match {
      case Some(sr) => pendingDone = None; Vector(ProviderEvent.Done(sr))
      case None     => Vector.empty
    }
  }
}

object AnthropicProvider {
  import fabric.*

  /** Construct an AnthropicProvider. Models are read from
    * [[sigil.cache.ModelRegistry]] at access time. */
  def create(sigil: Sigil, apiKey: String, baseUrl: URL = url"https://api.anthropic.com"): Task[AnthropicProvider] =
    Task.pure(AnthropicProvider(apiKey, sigil, baseUrl))

  /** A `cache_control` value. `extendedTtl = true` requests the
    * 1-hour cache lifetime (used for the tool roster and system
    * prompt — the most stable request segments); `false` leaves the
    * default 5-minute ephemeral cache (used for the history
    * breakpoint, which turns over faster). */
  private[anthropic] def cacheControl(extendedTtl: Boolean): Json =
    if (extendedTtl) obj("type" -> str("ephemeral"), "ttl" -> str("1h"))
    else obj("type" -> str("ephemeral"))

  /** Attach a default-TTL `cache_control` to the last content block of
    * a rendered message. The message's `content` is always an array of
    * blocks in this provider's encoding; the breakpoint rides the
    * trailing block so the cached segment extends through it. A
    * message with an empty content array is returned unchanged. */
  private[anthropic] def withHistoryCacheControl(message: Json): Json = {
    val fields = message.asObj.value
    fields.get("content").map(_.asVector) match {
      case Some(blocks) if blocks.nonEmpty =>
        val lastBlock = blocks.last.asObj.value.toVector :+
          ("cache_control" -> cacheControl(extendedTtl = false))
        val rewritten = blocks.init :+ obj(lastBlock*)
        obj((fields.toVector.filterNot(_._1 == "content") :+ ("content" -> arr(rewritten*)))*)
      case _ => message
    }
  }
}
