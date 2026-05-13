package sigil.provider.wire

import fabric.*
import fabric.io.JsonFormatter
import rapid.{Stream, Task}
import sigil.Sigil
import sigil.provider.*
import sigil.provider.debug.StreamWireInterceptor
import sigil.provider.sse.{SSELine, SSELineParser}
import sigil.tool.{DefinitionToSchema, Tool, ToolInput}
import sigil.tool.ToolInput.given
import spice.http.{HttpMethod, HttpRequest}
import spice.http.client.HttpClient
import spice.http.content.StringContent
import spice.net.*

import scala.concurrent.duration.*

/**
 * Shared implementation of the OpenAI-compatible chat-completions wire
 * (`POST /v1/chat/completions` with SSE streaming). Used by every
 * provider whose backend speaks this format — DeepSeek, llama.cpp,
 * DigitalOcean, and any OpenAI-derived hosted offering.
 *
 * Concrete provider classes delegate `httpRequestFor` and `call` to
 * [[buildHttpRequest]] and [[streamCall]] here, passing a [[Config]]
 * that captures the per-provider variations (strict mode, schema
 * transform, reasoning forwarding, etc.). Provider-specific behaviour
 * that doesn't fit a Config knob — pre-flight tokenization, custom
 * tool-id normalisation, custom concurrency capacity — stays on the
 * provider class itself.
 */
object OpenAIChatCompletions {

  /** How a provider forwards [[GenerationSettings.effort]] (if set) to the wire. */
  enum ReasoningPolicy {

    /** Don't forward effort at the wire. */
    case None

    /** Emit top-level `reasoning_effort: low|medium|high`. DeepSeek. */
    case ReasoningEffortField

    /** Emit `chat_template_kwargs: {enable_thinking: true}` whenever
      * `effort` is set; otherwise `false`. llama.cpp / Qwen3. */
    case ChatTemplateEnableThinking
  }

  /** How a provider renders multimodal [[MessageContent]] on the wire. */
  enum MultimodalPolicy {

    /** Collapse all content blocks to text; drop images with a WARN.
      * Used by text-only chat-completions backends (DeepSeek, llama.cpp). */
    case TextOnlyWithWarning

    /** Emit OpenAI's content-array shape when images are present:
      * `[{type: "text", text: ...}, {type: "image_url", image_url: {url: ...}}]`.
      * Pure-text messages stay on the simpler string form. */
    case OpenAIArrayForm
  }

  /** Per-provider configuration. Default values match the bare OpenAI
    * chat-completions wire; concrete providers override only what
    * differs. */
  case class Config(

    /** Namespace prefix on `Model.canonicalSlug` and `Model._id`
      * (e.g. `"deepseek"`). Stripped before sending to the wire. */
    providerNamespace: String,

    /** Provider name used in error messages and warnings. */
    providerName: String,

    /** Endpoint path. Defaults to `/v1/chat/completions`. */
    path: String = "/v1/chat/completions",

    /** Emit `"strict": true` on tool functions. Enables grammar-constrained
      * decoding on backends that honour it (DeepSeek; OpenAI strict-mode
      * lives on the Responses wire — not this one). */
    strictMode: Boolean = false,

    /** Schema transform applied to each tool's JSON schema before it
      * ships. Use [[StrictSchema.forDeepSeek]] (or `forOpenAI` if
      * applicable) when [[strictMode]] is on; otherwise
      * [[StrictSchema.stripUnsupportedKeys]] is the conservative
      * choice (drops `pattern` / `format` / numeric bounds that some
      * validators reject). Pass [[identity]] for backends that handle
      * the full schema (llama.cpp's GBNF translation). */
    schemaTransform: Json => Json = StrictSchema.stripUnsupportedKeys,

    /** Forwarding policy for `GenerationSettings.effort`. */
    reasoningPolicy: ReasoningPolicy = ReasoningPolicy.None,

    /** Multimodal rendering policy. */
    multimodalPolicy: MultimodalPolicy = MultimodalPolicy.TextOnlyWithWarning,

    /** Override the (systemPrompt, messages) pair pre-render. The
      * default returns the call's own system prompt and messages
      * unchanged. Use this for provider-specific reshaping —
      * llama.cpp's mid-array system folding + placeholder-user
      * injection, DigitalOcean's kimi `/think` directive. */
    preprocess: ProviderCall => Preprocessed = call => Preprocessed(call.system, call.messages),

    /** Normalise tool-call ids received from the wire before they
      * become [[CallId]]s. Default identity. llama.cpp uses this to
      * coerce non-conforming ids (e.g. Mistral NeMo's long form) into
      * the 9-char alphanumeric the chat template expects on later
      * turns. */
    toolCallIdNormalizer: String => String = identity,

    /** When `true`, an inline `data: {"error": {...}}` event in the SSE
      * stream raises [[ProviderStreamException]] instead of being
      * silently ignored. Surfaces backend-side mid-stream failures
      * (HTTP 200 + JSON error envelope) as user-visible failures via
      * the agent loop's failure-surface path. llama.cpp. */
    inlineErrorThrows: Boolean = false,

    /** When `true`, a stream that closes with `finish_reason: length`
      * having emitted zero content text AND zero tool calls raises
      * [[ProviderStreamException]] instead of producing a silent
      * `Done(MaxTokens)`. Surfaces deployment-level degeneration —
      * e.g. DigitalOcean's `kimi-k2.5` will sometimes burn the entire
      * `max_tokens` budget emitting `reasoning_content: " The!!!!"`
      * garbage or `content: null` padding with no usable output. The
      * agent can't recover from this (there's nothing to react to),
      * so the framework raises so [[ProviderStrategy.errorClassifier]]
      * can fall through to the next candidate. Reasoning-only output
      * still counts as no useful output. Sigil bug #161. */
    emptyBudgetBurnThrows: Boolean = false
  )

  /** Output of [[Config.preprocess]] — the system content + messages to
    * render. Either field may differ from `ProviderCall.system` /
    * `ProviderCall.messages`. */
  case class Preprocessed(systemPrompt: String, messages: Vector[ProviderMessage])

  // ---- entry points ----

  /** Build the wire `POST` for a call. Used by `Provider.httpRequestFor`
    * (inspect-without-send paths). Auth is applied by `auth`. */
  def buildHttpRequest(input: ProviderCall,
                       sigil: Sigil,
                       baseUrl: URL,
                       auth: HttpRequest => HttpRequest,
                       config: Config): Task[HttpRequest] = Task {
    val bodyStr = JsonFormatter.Compact(buildBody(input, sigil, config))
    auth(HttpRequest(
      method = HttpMethod.Post,
      url = baseUrl.withPath(config.path),
      content = Some(StringContent(bodyStr, ContentType.`application/json`))
    ))
  }

  /** Drive a streaming chat-completions call end-to-end. Returns a
    * [[Stream]] of `ProviderEvent` translated from the SSE chunks. */
  def streamCall(input: ProviderCall,
                 sigil: Sigil,
                 baseUrl: URL,
                 auth: HttpRequest => HttpRequest,
                 tokenIdleTimeout: FiniteDuration,
                 config: Config): Stream[ProviderEvent] = {
    val state = new StreamState(new ToolCallAccumulator(input.tools))
    Stream.force(
      for {
        raw         <- buildHttpRequest(input, sigil, baseUrl, auth, config)
        intercepted <- sigil.wireInterceptor.before(raw)
        lines       <- HttpClient.modify(_ => intercepted).noFailOnHttpStatus.timeout(tokenIdleTimeout).streamLines()
      } yield {
        StreamWireInterceptor.attach(lines, sigil.wireInterceptor, intercepted) { line =>
          Stream.emits(parseLine(line, state, config))
        }
      }
    )
  }

  // ---- body construction ----

  /** Build the JSON request body. Public so providers with bespoke
    * pre-flight tokenization (llama.cpp's `/apply-template`) can
    * inspect the rendered messages without firing the network call. */
  def buildBody(input: ProviderCall, sigil: Sigil, config: Config): Json = {
    val modelName = stripNamespace(input.modelId.value, config.providerNamespace)
    val pre = config.preprocess(input)
    val systemMsg = obj("role" -> str("system"), "content" -> str(pre.systemPrompt))
    val rendered = renderMessages(pre.messages, config)
    val toolsArr = renderTools(input, sigil, config)

    val baseFields = Vector[(String, Json)](
      "model"          -> str(modelName),
      "messages"       -> arr((Vector(systemMsg) ++ rendered)*),
      "stream"         -> bool(true),
      "stream_options" -> obj("include_usage" -> bool(true))
    )

    val toolFields: Vector[(String, Json)] = input.toolChoice match {
      case ToolChoice.None => Vector.empty
      case ToolChoice.Auto =>
        Vector("tools" -> arr(toolsArr*), "tool_choice" -> str("auto"))
      case ToolChoice.Required =>
        Vector("tools" -> arr(toolsArr*), "tool_choice" -> str("required"))
      case ToolChoice.Specific(name) =>
        Vector(
          "tools" -> arr(toolsArr*),
          "tool_choice" -> obj(
            "type"     -> str("function"),
            "function" -> obj("name" -> str(name.value))
          )
        )
    }

    val gen = input.generationSettings
    val reasoningFields: Vector[(String, Json)] = config.reasoningPolicy match {
      case ReasoningPolicy.None => Vector.empty
      case ReasoningPolicy.ReasoningEffortField =>
        // GenerationSettings.reasoningMode is the user-facing toggle;
        // effort is the optional intensity hint. Auto + no effort →
        // omit (model default fires). On + no effort → "high" (the
        // canonical "force thinking on" mapping). Off → "none"
        // (OpenAI / DeepSeek both accept this enum value).
        gen.reasoningMode match {
          case ReasoningMode.Off  => Vector("reasoning_effort" -> str("none"))
          case ReasoningMode.On   =>
            val level = gen.effort.map(Effort.openAIEffortLevel).getOrElse("high")
            Vector("reasoning_effort" -> str(level))
          case ReasoningMode.Auto =>
            gen.effort.toVector.map(e => "reasoning_effort" -> str(Effort.openAIEffortLevel(e)))
        }
      case ReasoningPolicy.ChatTemplateEnableThinking =>
        // vLLM-style chat-template kwarg honored by Qwen3 + kimi.
        // Read ReasoningMode (the user-facing abstraction), not the
        // orthogonal effort axis. Auto → omit (let template default
        // fire); On/Off → explicit boolean.
        gen.reasoningMode match {
          case ReasoningMode.Auto => Vector.empty
          case ReasoningMode.On   => Vector("chat_template_kwargs" -> obj("enable_thinking" -> bool(true)))
          case ReasoningMode.Off  => Vector("chat_template_kwargs" -> obj("enable_thinking" -> bool(false)))
        }
    }
    val generationFields: Vector[(String, Json)] =
      gen.temperature.toVector.map("temperature" -> num(_)) ++
        gen.maxOutputTokens.toVector.map("max_tokens" -> num(_)) ++
        gen.topP.toVector.map("top_p" -> num(_)) ++
        (if (gen.stopSequences.nonEmpty) Vector("stop" -> arr(gen.stopSequences.map(str)*)) else Vector.empty)

    obj((baseFields ++ toolFields ++ reasoningFields ++ generationFields)*)
  }

  /** Render the wire `tools` array. Each tool's input schema runs through
    * [[Config.schemaTransform]] (the place to strip dialect-unfriendly
    * keywords, or apply the strict-mode reshaping). */
  def renderTools(input: ProviderCall, sigil: Sigil, config: Config): Vector[Json] =
    input.tools.map { t =>
      val s = t.schema
      val fnFields = Vector[(String, Json)](
        "name"        -> str(s.name.value),
        "description" -> str(renderDescription(t, input.currentMode, sigil)),
        "parameters"  -> config.schemaTransform(DefinitionToSchema(s.input))
      ) ++ (if (config.strictMode) Vector("strict" -> bool(true)) else Vector.empty)
      obj(
        "type"     -> str("function"),
        "function" -> obj(fnFields*)
      )
    }

  /** Translate the framework's [[ProviderMessage]] sequence into the
    * OpenAI chat-completions `messages` array. */
  def renderMessages(messages: Vector[ProviderMessage], config: Config): Vector[Json] =
    messages.flatMap {
      case ProviderMessage.System(content) =>
        Vector(obj("role" -> str("system"), "content" -> str(content)))
      case ProviderMessage.User(blocks) =>
        Vector(renderUserMessage(blocks, config))
      case ProviderMessage.Assistant(content, toolCalls) =>
        Vector(
          if (toolCalls.isEmpty) obj("role" -> str("assistant"), "content" -> str(content))
          else obj(
            "role" -> str("assistant"),
            "tool_calls" -> arr(toolCalls.map { tc =>
              obj(
                "id"       -> str(tc.id),
                "type"     -> str("function"),
                "function" -> obj("name" -> str(tc.name), "arguments" -> str(tc.argsJson))
              )
            }*)
          )
        )
      case ProviderMessage.ToolResult(toolCallId, content) =>
        Vector(obj("role" -> str("tool"), "tool_call_id" -> str(toolCallId), "content" -> str(content)))
      case _: ProviderMessage.Reasoning =>
        // Reasoning state is OpenAI-Responses-only on the wire; the
        // chat-completions surface has no slot for it.
        Vector.empty
    }

  private def renderUserMessage(blocks: Vector[MessageContent], config: Config): Json =
    config.multimodalPolicy match {
      case MultimodalPolicy.TextOnlyWithWarning =>
        val (texts, images) = blocks.foldRight((List.empty[String], 0)) {
          case (MessageContent.Text(t), (ts, n))     => (t :: ts, n)
          case (MessageContent.Image(_, _), (ts, n)) => (ts, n + 1)
        }
        if (images > 0) scribe.warn(
          s"${config.providerName}Provider: dropped $images image block(s) — " +
            "this wire is text-only via chat-completions. Wire a multimodal-aware provider for vision."
        )
        obj("role" -> str("user"), "content" -> str(texts.mkString("\n")))
      case MultimodalPolicy.OpenAIArrayForm =>
        val hasImage = blocks.exists(_.isInstanceOf[MessageContent.Image])
        if (!hasImage) {
          val text = blocks.collect { case MessageContent.Text(t) => t }.mkString("\n")
          obj("role" -> str("user"), "content" -> str(text))
        } else {
          val parts = blocks.map {
            case MessageContent.Text(t) =>
              obj("type" -> str("text"), "text" -> str(t))
            case MessageContent.Image(u, _) =>
              obj("type" -> str("image_url"), "image_url" -> obj("url" -> str(u.toString)))
          }
          obj("role" -> str("user"), "content" -> arr(parts*))
        }
    }

  private def renderDescription(tool: Tool, mode: Mode, sigil: Sigil): String = {
    val base = tool.wireDescription(mode, sigil)
    if (tool.examples.isEmpty) base
    else {
      val rendered = tool.examples.map { e =>
        val json = JsonFormatter.Compact(stripPolyDiscriminator(summon[fabric.rw.RW[ToolInput]].read(e.input)))
        s"- ${e.description}: $json"
      }.mkString("\n")
      s"$base\n\nExamples:\n$rendered"
    }
  }

  private def stripPolyDiscriminator(json: Json): Json = json match {
    case o: Obj => Obj(o.value - "type")
    case other  => other
  }

  private def stripNamespace(modelId: String, namespace: String): String = {
    val prefix = s"$namespace/"
    if (modelId.startsWith(prefix)) modelId.drop(prefix.length) else modelId
  }

  // ---- SSE parsing ----

  /** Parse a single SSE line into [[ProviderEvent]]s. Public so per-
    * provider specs can drive the chunk-level paths (notably inline-error
    * detection) without spinning up a stub HTTP server. */
  def parseLine(line: String, state: StreamState, config: Config): Vector[ProviderEvent] =
    SSELineParser.parse(line) match {
      case SSELine.Data(json) => parseChunk(json, state, config)
      case SSELine.Done       => state.flushDone(config)
      case SSELine.MalformedData(_, reason) =>
        Vector(ProviderEvent.Error(s"parse: $reason"))
      case SSELine.Blank | SSELine.Comment | _: SSELine.Other => Vector.empty
    }

  /** Parse a single decoded SSE chunk's JSON payload into
    * [[ProviderEvent]]s. Public for the same reason as [[parseLine]]. */
  def parseChunk(json: Json, state: StreamState, config: Config): Vector[ProviderEvent] = {
    // Some backends embed mid-stream failures as `data: {"error": {...}}`
    // events on a 200-OK chat-completions stream. When configured, surface
    // them as a thrown exception so the agent loop's failure-handler can
    // emit a user-visible Failure Message instead of an empty turn.
    if (config.inlineErrorThrows) {
      json.get("error").foreach { err =>
        if (!err.isNull) {
          val code = err.get("code").map(_.asInt).getOrElse(0)
          val msg  = err.get("message").map(_.asString).getOrElse("(no message)")
          val typ  = err.get("type").map(_.asString).getOrElse("error")
          throw new ProviderStreamException(config.providerNamespace, code, typ, msg)
        }
      }
    }

    val events = Vector.newBuilder[ProviderEvent]
    val choice = json.get("choices").flatMap(_.asVector.headOption)

    choice.flatMap(_.get("delta")).foreach { delta =>
      delta.get("content").foreach { c =>
        if (!c.isNull) {
          val text = c.asString
          if (text.nonEmpty) {
            events += ProviderEvent.TextDelta(text)
            state.hasUsefulOutput = true
          }
        }
      }
      delta.get("reasoning_content").foreach { c =>
        if (!c.isNull) {
          val text = c.asString
          if (text.nonEmpty) events += ProviderEvent.ThinkingDelta(text)
        }
      }
      delta.get("tool_calls").foreach { tcs =>
        // DeepInfra streams `tool_calls: null` on no-tool-call deltas
        // (the role: "assistant" warmup chunk emits it before any
        // content). DO and OpenAI omit the field entirely. Both shapes
        // are wire-spec valid — null-guard so we tolerate both. Sigil
        // bug #163.
        if (!tcs.isNull) {
          tcs.asVector.foreach { tc =>
            val index   = tc.get("index").map(_.asInt).getOrElse(0)
            val idOpt   = tc.get("id").flatMap(j => if (j.isNull) None else Some(j.asString)).map(config.toolCallIdNormalizer)
            val nameOpt = tc.get("function").flatMap(_.get("name")).flatMap(j => if (j.isNull) None else Some(j.asString))
            (idOpt, nameOpt) match {
              case (Some(id), Some(n)) =>
                events ++= state.acc.start(index, CallId(id), n)
                state.hasUsefulOutput = true
              case _                   => ()
            }
            tc.get("function").flatMap(_.get("arguments"))
              .flatMap(j => if (j.isNull) None else Some(j.asString))
              .foreach(a => events ++= state.acc.appendArgs(index, a))
          }
        }
      }
    }

    choice.flatMap(_.get("finish_reason")).foreach { reason =>
      if (!reason.isNull) {
        val sr = reason.asString match {
          case "stop"           => StopReason.Complete
          case "length"         => StopReason.MaxTokens
          case "tool_calls"     => StopReason.ToolCall
          case "content_filter" => StopReason.ContentFiltered
          case other =>
            scribe.warn(s"Unmapped finish_reason from ${config.providerName}: '$other' — treating as Complete")
            StopReason.Complete
        }
        if (sr == StopReason.ToolCall) events ++= state.acc.complete()
        state.pendingDone = Some(sr)
      }
    }

    json.get("usage").foreach { u =>
      if (!u.isNull) events += ProviderEvent.Usage(parseUsage(u))
    }

    events.result()
  }

  private def parseUsage(json: Json): TokenUsage =
    TokenUsage(
      promptTokens     = json.get("prompt_tokens").map(_.asInt).getOrElse(0),
      completionTokens = json.get("completion_tokens").map(_.asInt).getOrElse(0),
      totalTokens      = json.get("total_tokens").map(_.asInt).getOrElse(0)
    )

  /** Streaming state: pending [[StopReason]] held back until the
    * trailing `usage` chunk (or `[DONE]`) arrives, plus the tool-call
    * accumulator. Public so callers with bespoke pre/post handling
    * (llama.cpp's pre-flight, etc.) can share it. */
  final class StreamState(val acc: ToolCallAccumulator) {
    var pendingDone: Option[StopReason] = None

    /** Tracks whether the stream emitted any TextDelta with non-empty
      * text OR a tool-call Start event. `reasoning_content` deltas
      * (ThinkingDelta) do NOT flip this — a stream of pure reasoning
      * with no content/tool emissions IS the no-useful-output failure
      * mode the empty-budget-burn detection (sigil bug #161)
      * surfaces. */
    var hasUsefulOutput: Boolean = false

    def flushDone(config: Config): Vector[ProviderEvent] = pendingDone match {
      case Some(sr) =>
        pendingDone = None
        if (config.emptyBudgetBurnThrows && sr == StopReason.MaxTokens && !hasUsefulOutput) {
          throw new ProviderStreamException(
            providerKey = config.providerNamespace,
            code        = 200,
            typ         = "empty_budget_burn",
            message_    = s"${config.providerName} consumed max_tokens budget without emitting any content or tool calls" +
              " — likely a deployment-level degeneration (e.g. reasoning-only output or null-padded stream)."
          )
        }
        Vector(ProviderEvent.Done(sr))
      case None     => Vector.empty
    }
  }
}
