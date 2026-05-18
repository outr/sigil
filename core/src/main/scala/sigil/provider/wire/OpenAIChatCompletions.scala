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

  /** Minimum wall-clock interval between two synthetic streaming
    * usage estimates. Throttles the per-second emission rate on
    * fast-streaming responses so the event log isn't drowned in
    * MessageDeltas. Paired with [[streamingEstimateMinDeltas]] — the
    * first trigger to fire emits. */
  private val streamingEstimateMinIntervalMs: Long = 250L

  /** Minimum number of accumulated content / reasoning deltas
    * between two synthetic streaming usage estimates. Catches
    * slow-streaming responses where the time-based trigger
    * wouldn't fire often enough to feel "live". */
  private val streamingEstimateMinDeltas: Int = 16

  /** Chars-per-token ratio for the synthetic streaming estimate.
    * A flat heuristic that's "close enough" for a live ticker
    * (5-15% off during stream; snaps to exact at end). Per-model
    * fidelity is out of scope at the wire-decoder layer. */
  private val streamingEstimateCharsPerToken: Double = 4.0

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

    /** Backend supports OpenAI-style `strict: true` on tool functions —
      * the wire flag that engages grammar-constrained decoding. When
      * true, [[renderTools]] dispatches per-tool: tools whose schema
      * is strict-compatible (no `DefType.Json` anywhere in the tree —
      * bug #64) ship with `strict: true` and [[StrictSchema.forOpenAIStrict]]
      * applied to their parameters. Tools that can't be strict (Json
      * fields are incompatible with strict mode's closed-object
      * requirement) fall through to [[nonStrictSchemaTransform]] and
      * omit the `strict` flag.
      *
      * Engaged for OpenAI-compatible backends that honour strict
      * mode (DeepSeek, DeepInfra, DigitalOcean, …); off for llama.cpp
      * (its GBNF translator handles the full schema and doesn't need
      * the strict flag). OpenAI's own strict-mode path lives on the
      * Responses wire, not this one. */
    strictModeCapable: Boolean = false,

    /** Schema transform applied to each tool's JSON schema when strict
      * mode is NOT engaged (either [[strictModeCapable]] is false OR
      * the tool's input schema contains a `DefType.Json` and can't be
      * strict). Default [[StrictSchema.stripUnsupportedKeys]] drops
      * `pattern` / `format` / numeric bounds that some validators
      * reject. Pass [[identity]] for backends that handle the full
      * schema (llama.cpp's GBNF translation). */
    nonStrictSchemaTransform: Json => Json = StrictSchema.stripUnsupportedKeys,

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
      * the agent loop's failure-surface path.
      *
      * Sigil bug #193 — default flipped from `false` to `true`. Every
      * OpenAI-compat upstream gateway observed in practice (OpenRouter,
      * DeepInfra, DigitalOcean, llama.cpp) emits these chunks on
      * provider-side timeouts / 502s; silently dropping them masked
      * the real failure mode behind the model-degenerated empty-
      * completion placeholder. Apps that want the old silent-drop
      * behaviour pass `inlineErrorThrows = false` explicitly. */
    inlineErrorThrows: Boolean = true,

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
    emptyBudgetBurnThrows: Boolean = false,

    /** When `false`, the per-function `"strict": true` flag is OMITTED
      * from the wire — strict-mode schema reshaping via
      * [[strictModeCapable]] still happens (so we ship a closed-object
      * schema that's safe for the validator regardless), but we don't
      * SEND the flag that asks the backend to grammar-constrain.
      *
      * Set `false` for providers that accept the flag silently but
      * don't actually enforce it (DeepInfra honors neither `strict`
      * nor `tool_choice: "required"` per their docs — verified
      * against captured wire logs where Kimi-K2.5 emitted JSON
      * arrays despite `strict: true` on every function). Distinct
      * from [[strictModeCapable]] which is Sigil-side ("should we
      * reshape the schema?"); this is provider-side ("should we
      * send the flag?"). For honest providers both stay `true`.
      *
      * Sigil bug #173. */
    honorsStrict: Boolean = true,

    /** Shape Sigil uses to express forced-call semantics on the
      * wire. `ToolChoice` ships the OpenAI-canonical `tool_choice`
      * field; `ResponseFormatJsonSchema` substitutes a
      * `response_format: json_schema` constraint over a synthesized
      * meta-schema and parses the assistant's content as a synthetic
      * tool call.
      *
      * Use `ResponseFormatJsonSchema` for providers whose documented
      * `tool_choice` vocabulary doesn't include `"required"` /
      * function-form (DeepInfra). The forced-call contract is Sigil's
      * structure-first invariant: every turn with tools demands the
      * model produce a tool call. When the backend won't honor
      * `tool_choice: "required"` natively, response_format is the
      * documented substitute.
      *
      * Sigil bug #173. */
    forcedCallShape: ForcedCallShape = ForcedCallShape.ToolChoice,

    /** Per-provider extra top-level wire fields appended to every
      * request body. Receives the resolved `ProviderCall` so the
      * fields can be call-shaped (e.g. derive from the model id or
      * tool roster); returns key/value pairs merged at the root of
      * the JSON body alongside `model` / `messages` / `tools`.
      *
      * Used by OpenRouter to inject the `provider` routing block
      * (ignore-list of Chinese-hosted slugs, sort policy, fallbacks).
      * Default `_ => Vector.empty` is a no-op for every other
      * chat-completions backend. */
    extraBody: ProviderCall => Vector[(String, Json)] = _ => Vector.empty
  )

  /** How Sigil expresses forced-call semantics on a chat-completions
    * wire. Default uses the OpenAI-canonical `tool_choice` field;
    * `ResponseFormatJsonSchema` substitutes a `response_format`
    * json_schema constraint over a synthesized meta-schema (and
    * stream-side parses the assistant content as a synthetic tool
    * call). Sigil bug #173. */
  enum ForcedCallShape {
    case ToolChoice
    case ResponseFormatJsonSchema
  }

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
    val rfMode: Option[ResponseFormatMode] = config.forcedCallShape match {
      case ForcedCallShape.ToolChoice => None
      case ForcedCallShape.ResponseFormatJsonSchema => input.toolChoice match {
        case ToolChoice.Specific(name) => Some(ResponseFormatMode.Specific(name))
        case ToolChoice.Required       => Some(ResponseFormatMode.Required)
        case _                         => None
      }
    }
    val state = new StreamState(new ToolCallAccumulator(input.tools, providerKey = config.providerName), rfMode)
    Stream.force(
      for {
        raw         <- buildHttpRequest(input, sigil, baseUrl, auth, config)
        intercepted <- sigil.wireInterceptor.before(raw)
        lines       <- HttpClient.modify(_ => intercepted).noFailOnHttpStatus.timeout(tokenIdleTimeout).streamLines()
      } yield {
        StreamWireInterceptor.attach(lines, sigil.wireInterceptor, intercepted, sigil.chunkLogger) { line =>
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

    val toolFields: Vector[(String, Json)] = (input.toolChoice, config.forcedCallShape) match {
      case (ToolChoice.None, _) => Vector.empty
      case (ToolChoice.Auto, _) =>
        Vector("tools" -> arr(toolsArr*), "tool_choice" -> str("auto"))

      // Sigil bug #173 — forced-call substitution. When the backend
      // doesn't honor `tool_choice: required` / function-form
      // (DeepInfra), express forced-call via `response_format:
      // json_schema` over a synthesized meta-schema. Sigil's structure-
      // first invariant (every turn with tools must produce a tool
      // call) is preserved through a documented substitute rather than
      // an undocumented wire flag the backend silently ignores.
      case (ToolChoice.Required, ForcedCallShape.ResponseFormatJsonSchema) =>
        Vector(
          "tools"           -> arr(toolsArr*),
          "response_format" -> buildRequiredMetaResponseFormat(input)
        )
      case (ToolChoice.Specific(name), ForcedCallShape.ResponseFormatJsonSchema) =>
        Vector(
          "tools"           -> arr(toolsArr*),
          "response_format" -> buildSpecificResponseFormat(input, name)
        )

      case (ToolChoice.Required, ForcedCallShape.ToolChoice) =>
        Vector("tools" -> arr(toolsArr*), "tool_choice" -> str("required"))
      case (ToolChoice.Specific(name), ForcedCallShape.ToolChoice) =>
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

    val extraFields = config.extraBody(input)

    obj((baseFields ++ toolFields ++ reasoningFields ++ generationFields ++ extraFields)*)
  }

  /** Sigil bug #173 — build a `response_format: json_schema` body
    * fragment for `ToolChoice.Specific`. The synthesized schema is
    * the named tool's input schema (closed-object, strict-shaped),
    * with `name = tool name`. Model emits a single JSON object
    * matching the tool's input directly as its assistant content;
    * stream-side parses that content as a synthetic
    * `ToolCallStart` + `ToolCallComplete` for the named tool. */
  private def buildSpecificResponseFormat(input: ProviderCall, name: sigil.tool.ToolName): Json = {
    val tool = input.tools.find(_.schema.name == name)
      .getOrElse(throw new IllegalStateException(
        s"ToolChoice.Specific(${name.value}) names a tool not in input.tools — wire layer can't synthesize response_format."
      ))
    val toolSchema = DefinitionToSchema(tool.schema.input)
    val strictShape = StrictSchema.forOpenAIStrict(toolSchema)
    obj(
      "type" -> str("json_schema"),
      "json_schema" -> obj(
        "name"   -> str(name.value),
        "strict" -> bool(true),
        "schema" -> strictShape
      )
    )
  }

  /** Sigil bug #173 — build a `response_format: json_schema` body
    * fragment for `ToolChoice.Required` (force ANY tool from the
    * roster). The synthesized meta-schema is:
    *   `{ tool_name: enum[<all roster names>], arguments: oneOf<…> }`
    * Model emits one JSON object matching this shape as its assistant
    * content; stream-side looks up `tool_name`, extracts `arguments`,
    * and emits synthetic `ToolCallStart` + `ToolCallComplete` events
    * the orchestrator processes identically to native tool calls. */
  private def buildRequiredMetaResponseFormat(input: ProviderCall): Json = {
    val names = input.tools.map(_.schema.name.value)
    val argSchemas = input.tools.map { t =>
      StrictSchema.forOpenAIStrict(DefinitionToSchema(t.schema.input))
    }
    val argumentsSchema =
      if (argSchemas.size == 1) argSchemas.head
      else obj("oneOf" -> arr(argSchemas*))
    obj(
      "type" -> str("json_schema"),
      "json_schema" -> obj(
        "name"   -> str("sigil_tool_call"),
        "strict" -> bool(true),
        "schema" -> obj(
          "type" -> str("object"),
          "properties" -> obj(
            "tool_name" -> obj(
              "type" -> str("string"),
              "enum" -> arr(names.map(str)*)
            ),
            "arguments" -> argumentsSchema
          ),
          "required" -> arr(str("tool_name"), str("arguments")),
          "additionalProperties" -> bool(false)
        )
      )
    )
  }

  /** Render the wire `tools` array. Per-tool dispatch on strict-mode
    * capability: when [[Config.strictModeCapable]] is true AND the
    * tool's input schema has no `DefType.Json` anywhere (bug #64 —
    * strict mode is incompatible with any-JSON-value fields), the
    * tool ships with `strict: true` and a [[StrictSchema.forOpenAIStrict]]-
    * shaped schema. Otherwise the schema runs through
    * [[Config.nonStrictSchemaTransform]] and `strict` is omitted. */
  def renderTools(input: ProviderCall, sigil: Sigil, config: Config): Vector[Json] =
    input.tools.map { t =>
      val s = t.schema
      val canBeStrict = config.strictModeCapable && !DefinitionToSchema.containsJson(s.input)
      val baseSchema = DefinitionToSchema(s.input)
      val parameters =
        if (canBeStrict) StrictSchema.forOpenAIStrict(baseSchema)
        else config.nonStrictSchemaTransform(baseSchema)
      val fnFields = Vector[(String, Json)](
        "name"        -> str(s.name.value),
        "description" -> str(renderDescription(t, input.currentMode, sigil)),
        "parameters"  -> parameters
      ) ++ (if (canBeStrict && config.honorsStrict) Vector("strict" -> bool(true)) else Vector.empty)
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
          case (MessageContent.Text(t), (ts, n))         => (t :: ts, n)
          case (_: MessageContent.Image, (ts, n))        => (ts, n + 1)
          case (_: MessageContent.ImageBytes, (ts, n))   => (ts, n + 1)
        }
        if (images > 0) scribe.warn(
          s"${config.providerName}Provider: dropped $images image block(s) — " +
            "this wire is text-only via chat-completions. Wire a multimodal-aware provider for vision."
        )
        obj("role" -> str("user"), "content" -> str(texts.mkString("\n")))
      case MultimodalPolicy.OpenAIArrayForm =>
        val hasImage = blocks.exists {
          case _: MessageContent.Image | _: MessageContent.ImageBytes => true
          case _                                                      => false
        }
        if (!hasImage) {
          val text = blocks.collect { case MessageContent.Text(t) => t }.mkString("\n")
          obj("role" -> str("user"), "content" -> str(text))
        } else {
          val parts = blocks.map {
            case MessageContent.Text(t) =>
              obj("type" -> str("text"), "text" -> str(t))
            case MessageContent.Image(u, _) =>
              obj("type" -> str("image_url"), "image_url" -> obj("url" -> str(u.toString)))
            case MessageContent.ImageBytes(mediaType, base64, _) =>
              // OpenAI's chat-completions `image_url` field accepts inline
              // data URLs (`data:<mime>;base64,<bytes>`). Construct one here
              // so apps with raw bytes don't need to detour through a host.
              obj(
                "type" -> str("image_url"),
                "image_url" -> obj("url" -> str(s"data:$mediaType;base64,$base64"))
              )
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
            // Sigil bug #173 — in response_format mode the content
            // stream is actually the synthesized tool-call payload,
            // not user-visible text. Buffer for end-of-stream
            // synthesis; suppress the TextDelta so the orchestrator
            // doesn't start a streaming user-visible Message. The
            // synthetic ToolCallStart/ContentBlockDelta/Complete pair
            // fired on finish_reason replaces it.
            state.responseFormatMode match {
              case Some(_) =>
                state.responseFormatBuf.append(text)
                state.hasUsefulOutput = true
              case None =>
                events += ProviderEvent.TextDelta(text)
                state.hasUsefulOutput = true
            }
            state.completionChars += text.length
            state.deltasSinceLastEstimate += 1
          }
        }
      }
      // Sigil bug #192 — accept either OpenAI's canonical
      // `reasoning_content` OR OpenRouter's `reasoning` field. The
      // OpenRouter wire shape (observed via Io Net upstream serving
      // moonshotai/kimi-k2.6) streams reasoning fragments under
      // `delta.reasoning` with a parallel `reasoning_details` array;
      // OpenAI's o1 / o3 / gpt-5 stream under `delta.reasoning_content`.
      // Prefer the canonical field when both are present so a provider
      // that emits both (defensive belt-and-suspenders) doesn't
      // double-emit ThinkingDeltas.
      delta.get("reasoning_content")
        .filter(!_.isNull)
        .orElse(delta.get("reasoning").filter(!_.isNull))
        .foreach { c =>
          val text = c.asString
          if (text.nonEmpty) {
            events += ProviderEvent.ThinkingDelta(text)
            // Reasoning tokens ARE charged by the provider (see
            // bug #196's runaway pathology), so they count toward
            // the synthetic estimate too — the ticker should
            // reflect total compute, not just user-visible content.
            state.completionChars += text.length
            state.deltasSinceLastEstimate += 1
          }
        }
      // Sigil audit H3 — OpenAI streams `delta.refusal` as a sibling to
      // `delta.content` when the model declines to comply with the
      // request (safety / policy refusal). The previous parser ignored
      // the field entirely, so a refusal produced an empty assistant
      // turn that the empty-budget-burn detector might or might not
      // catch (the model emits no content + finish_reason "stop", not
      // "length"). Buffer the refusal text and throw on flush so the
      // strategy can fall through to another candidate (typed exception
      // classifies as Fallthrough — H5).
      delta.get("refusal").foreach { r =>
        if (!r.isNull) {
          val text = r.asString
          if (text.nonEmpty) state.refusalBuf.append(text)
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
            // Sigil audit H8 — feed both fields through `observeHeader`
            // so split-header compat backends (vLLM, SGLang) don't
            // silently drop tool calls. The accumulator emits
            // ToolCallStart exactly once, when both id+name are
            // known. Args may arrive before the header completes;
            // they buffer on the pending state and fold into the
            // promoted CallState.
            if (idOpt.isDefined || nameOpt.isDefined) {
              events ++= state.acc.observeHeader(index, idOpt.map(CallId(_)), nameOpt)
              state.hasUsefulOutput = true
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
        // Some OpenAI-compat backends (observed: OpenRouter proxying
        // Kimi K2.5/K2.6 via Chutes) split end-of-stream across two
        // chunks — the first carries `finish_reason` alone; the second
        // re-announces `finish_reason` AND attaches `usage` as a
        // followup. Treat the second arrival as a usage-only
        // followup: don't re-emit a completion (the orchestrator's
        // dedupe would catch the duplicate but we'd rather not emit
        // it in the first place). The `usage` block in this chunk
        // still flows through — the `json.get("usage")` handler
        // below runs unconditionally.
        if (state.pendingDone.isEmpty) {
          if (sr == StopReason.ToolCall) events ++= state.acc.complete()
          // Sigil bug #173 — response_format substitution: the model
          // finished with `stop` (not `tool_calls`) because we asked
          // for structured content. Synthesize the tool-call events
          // from the buffered content so the orchestrator processes
          // it identically to a native tool call.
          else if (sr == StopReason.Complete) {
            state.responseFormatMode match {
              case Some(mode) =>
                events ++= synthesizeToolCallFromContent(state, mode, config)
              case None => ()
            }
          }
          state.pendingDone = Some(sr)
        }
      }
    }

    val hasRealUsage = json.get("usage").exists(!_.isNull)

    // Synthetic streaming token estimate. Fires when the cadence
    // threshold trips (time-based OR delta-count-based, whichever
    // comes first) AND no authoritative usage block rides in this
    // chunk (we'd rather emit the real number than a stale estimate
    // immediately followed by it). The pre-flight prompt token
    // count isn't plumbed to this layer; the synthetic emission
    // reports `promptTokens = 0` and lets `completionTokens` carry
    // the moving signal — that's the value consumer tickers display.
    if (!hasRealUsage && state.deltasSinceLastEstimate > 0) {
      val now = state.nowNanos()
      // Anchor the time-based clock on the first eligible delta so
      // the first synthetic emit fires `streamingEstimateMinIntervalMs`
      // after the stream actually starts producing tokens, not on
      // the very first chunk (which would emit a near-zero estimate
      // immediately).
      if (state.lastEstimateNanos == 0L) state.lastEstimateNanos = now
      val elapsedMs = (now - state.lastEstimateNanos) / 1000000L
      val deltaTrigger = state.deltasSinceLastEstimate >= streamingEstimateMinDeltas
      val timeTrigger = elapsedMs >= streamingEstimateMinIntervalMs
      if (deltaTrigger || timeTrigger) {
        val completionTokens = (state.completionChars.toDouble / streamingEstimateCharsPerToken).toInt
        events += ProviderEvent.Usage(TokenUsage(
          promptTokens     = 0,
          completionTokens = completionTokens,
          totalTokens      = completionTokens,
          isEstimated      = true
        ))
        state.lastEstimateNanos = now
        state.deltasSinceLastEstimate = 0
      }
    }

    json.get("usage").foreach { u =>
      if (!u.isNull) {
        val parsed = parseUsage(u)
        // Track the latest usage block so flushDone can detect a
        // no-finish-reason / no-content / no-tool-call degenerate-
        // completion (completion_tokens burned with no useful output)
        // and throw a typed exception the strategy can route around.
        state.lastUsage = Some(parsed)
        events += ProviderEvent.Usage(parsed)
      }
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
    * (llama.cpp's pre-flight, etc.) can share it.
    *
    * `responseFormatMode` carries the bug #173 forced-call substitution
    * shape (when active). `None` means standard tool_calls flow. The
    * stream-side handler suppresses TextDelta emission in that mode
    * (avoid creating a streaming Message UI for what is actually a
    * tool call) and buffers the content for end-of-stream synthesis
    * into ToolCallStart/Complete events. */
  final class StreamState(val acc: ToolCallAccumulator,
                          val responseFormatMode: Option[ResponseFormatMode] = None,
                          val nowNanos: () => Long = () => System.nanoTime()) {
    var pendingDone: Option[StopReason] = None
    val responseFormatBuf: StringBuilder = new StringBuilder

    /** Accumulates `delta.refusal` text. OpenAI streams this as a
      * sibling to `delta.content` when the model declines to
      * comply (safety / policy). The framework treats refusal as a
      * candidate-level failure: throw at stream close so the
      * strategy can route to another candidate (the typed exception
      * classifies as Fallthrough). */
    val refusalBuf: StringBuilder = new StringBuilder

    /** Tracks whether the stream emitted any TextDelta with non-empty
      * text OR a tool-call Start event. `reasoning_content` deltas
      * (ThinkingDelta) do NOT flip this — a stream of pure reasoning
      * with no content/tool emissions IS the no-useful-output failure
      * mode the empty-budget-burn detection surfaces. */
    var hasUsefulOutput: Boolean = false

    /** Latest `usage` block observed in the stream. Captured so
      * [[flushDone]] can detect a degenerate-empty completion shape
      * (the model burned `completion_tokens` but emitted no content,
      * no reasoning, no tool calls, and no `finish_reason`). */
    var lastUsage: Option[sigil.provider.TokenUsage] = None

    /** Total streamed characters (reasoning_content + content)
      * accumulated since the start of the response. Drives the
      * synthetic streaming usage estimate emitted at
      * [[streamingEstimateMinIntervalMs]] / [[streamingEstimateMinDeltas]]
      * cadence so consumer UIs can render a live token ticker
      * during long reasoning streams. */
    var completionChars: Long = 0L

    /** Number of content / reasoning deltas observed since the
      * last synthetic streaming usage emission. Reset to 0 each
      * time the cadence trigger fires. */
    var deltasSinceLastEstimate: Int = 0

    /** Wall-clock timestamp (System.nanoTime) of the last
      * synthetic streaming usage emission. Zero means no estimate
      * has fired yet (the first eligible delta triggers an
      * emission so the ticker shows movement promptly). */
    var lastEstimateNanos: Long = 0L

    def flushDone(config: Config): Vector[ProviderEvent] = pendingDone match {
      case Some(sr) =>
        pendingDone = None
        if (refusalBuf.nonEmpty) {
          val refusalText = refusalBuf.toString
          refusalBuf.clear()
          throw new ProviderStreamException(
            providerKey = config.providerNamespace,
            code        = 200,
            typ         = "refusal",
            message_    = s"${config.providerName} refused: $refusalText"
          )
        }
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
      case None =>
        // No finish_reason observed but the stream is closing. If the
        // model burned completion_tokens without emitting any useful
        // output, treat it as a degenerate completion — symmetric with
        // the `length`-finish empty-budget-burn detection above but
        // for the no-finish-reason flavor. Throw a typed exception so
        // ProviderStrategy can route around it; the typed dispatch
        // classifies as Fallthrough.
        if (config.emptyBudgetBurnThrows && !hasUsefulOutput &&
            lastUsage.exists(_.completionTokens > 0)) {
          val burned = lastUsage.map(_.completionTokens).getOrElse(0)
          throw new ProviderStreamException(
            providerKey = config.providerNamespace,
            code        = 200,
            typ         = "empty_completion",
            message_    = s"${config.providerName} closed the stream after burning $burned completion tokens " +
              "without emitting any content, reasoning, tool calls, or a finish_reason."
          )
        }
        Vector.empty
    }
  }

  /** Sigil bug #173 — at end-of-stream in response_format mode, parse
    * the buffered content and emit synthetic ToolCallStart +
    * appendArgs + complete events. The accumulator's downstream
    * processing (typed input materialisation, malformed-args
    * detection, etc.) runs identically to a native tool call. */
  private def synthesizeToolCallFromContent(state: StreamState,
                                            mode: ResponseFormatMode,
                                            config: Config): Vector[ProviderEvent] = {
    val raw = state.responseFormatBuf.toString
    state.responseFormatBuf.clear()
    if (raw.trim.isEmpty) return Vector.empty

    val (toolName: String, argsString: String) = mode match {
      case ResponseFormatMode.Specific(name) =>
        // Content IS the named tool's args (top-level JSON object).
        (name.value, raw)
      case ResponseFormatMode.Required =>
        // Content is `{tool_name, arguments}` per the meta-schema.
        try {
          val parsed = fabric.io.JsonParser(raw)
          val tn  = parsed.get("tool_name").map(_.asString).getOrElse {
            throw new ProviderStreamException(
              providerKey = config.providerNamespace, code = 200,
              typ = "malformed_response_format",
              message_ = s"response_format substitution: content lacks tool_name field. Got: ${raw.take(200)}"
            )
          }
          val ar  = parsed.get("arguments").map(j => fabric.io.JsonFormatter.Compact(j)).getOrElse("{}")
          (tn, ar)
        } catch {
          case e: ProviderStreamException => throw e
          case t: Throwable =>
            throw new ProviderStreamException(
              providerKey = config.providerNamespace, code = 200,
              typ = "malformed_response_format",
              message_ = s"response_format substitution: content failed to parse as {tool_name, arguments}. Error: ${t.getMessage}. Content: ${raw.take(200)}"
            )
        }
    }

    val callId = CallId(s"sigil-rf-${java.util.UUID.randomUUID().toString.take(8)}")
    val events = Vector.newBuilder[ProviderEvent]
    events ++= state.acc.start(0, callId, toolName)
    events ++= state.acc.appendArgs(0, argsString)
    events ++= state.acc.complete()
    state.hasUsefulOutput = true
    events.result()
  }

  /** Records the forced-call substitution that's active on this stream
    * so the end-of-stream handler can synthesize the right
    * `ToolCallStart` + `ToolCallComplete` events from the buffered
    * content. Sigil bug #173. */
  sealed trait ResponseFormatMode
  object ResponseFormatMode {
    /** `ToolChoice.Specific(name)` substituted to response_format.
      * The buffered content is the named tool's typed input JSON
      * directly — emit one synthetic ToolCallStart(name) + appendArgs
      * of the entire content. */
    final case class Specific(name: sigil.tool.ToolName) extends ResponseFormatMode
    /** `ToolChoice.Required` substituted to response_format with a
      * meta-schema. The buffered content is
      * `{tool_name, arguments}`; the synthesizer extracts both and
      * emits the corresponding pair of events. */
    case object Required extends ResponseFormatMode
  }
}
