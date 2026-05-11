package sigil.provider.llamacpp

import fabric.*
import fabric.io.JsonFormatter
import fabric.rw.*
import lightdb.id.Id
import rapid.{Stream, Task}
import sigil.Sigil
import sigil.db.Model
import sigil.provider.*
import sigil.provider.sse.{SSELine, SSELineParser}
import sigil.tool.{DefinitionToSchema, Tool, ToolInput, ToolName, ToolSchema}
import sigil.tool.ToolInput.given
import spice.http.{HttpMethod, HttpRequest, HttpResponse, HttpStatus}
import spice.http.client.HttpClient
import spice.http.content.StringContent
import spice.net.*

import scala.concurrent.duration.*
import scala.util.Success

case class LlamaCppProvider(url: URL,
                            override val models: List[Model],
                            sigilRef: Sigil,
                            /** Per-read idle timeout for the SSE stream. spice's
                              * okhttp client wires this through to okhttp's
                              * `readTimeout`, which fires only when NO bytes
                              * arrive for the duration. Slow-but-working models
                              * (tokens trickling steadily) keep streaming
                              * indefinitely; genuinely stuck streams fail
                              * within `tokenIdleTimeout`. Default 120s. */
                            tokenIdleTimeout: FiniteDuration = 120.seconds) extends Provider {
  override def `type`: ProviderType = ProviderType.LlamaCpp
  override val providerKey: String = LlamaCpp.Provider

  override protected def sigil: Sigil = sigilRef

  /** Bug #45 — backend-exact tokenizer via llama.cpp's
    * `POST /tokenize`. Falls back to the heuristic on transient
    * failures so a degraded backend doesn't crash pre-flight. The
    * heuristic under-counts JSON / chat-template content by ~7-15%
    * which let oversized requests slip past the gate. */
  override val tokenizer: _root_.sigil.tokenize.Tokenizer =
    LlamaCppTokenizer(url, interceptor = sigilRef.wireInterceptor)

  /** Bug #49 — capacity from llama.cpp's `total_slots`. Read at
    * provider construction; falls back to 1 when `/props` is
    * unreachable (single-slot is the safe assumption for an
    * unknown deployment — `total_slots` only matters when it's
    * greater than 1, and assuming 1 just means more work serializes
    * client-side, which is correct for an unknown server). */
  override val maxConcurrent: Int =
    LlamaCpp.fetchProps(url).map(_.map(_.totalSlots.toInt).getOrElse(1))
      .handleError(_ => Task.pure(1))
      .sync()

  /** Bug #46 / #47 — backend-exact pre-flight estimate in a single
    * round-trip. Sends both `messages` and `tools` to
    * `POST /apply-template` so the rendered prompt includes every
    * inline tool-declaration block the chat template emits (gemma:
    * `<|tool>declaration:...<tool|>`; qwen / llama: model-specific).
    * One subsequent `/tokenize` call against the whole rendered
    * string produces an exact estimate.
    *
    * Cost: 2 HTTP round-trips per pre-flight regardless of how many
    * tools the agent has. Pre-#47 the roster pass alone made
    * 3-per-tool tokenizer calls (~30 round-trips for a 10-tool
    * agent), each susceptible to spice connection-pool stale-reuse
    * resets that triggered 1-second retry stalls. Collapsing to one
    * call eliminates the cumulative latency and the reset surface
    * area.
    *
    * Falls back to the piecewise default on `/apply-template`
    * failure — caller still gets an estimate, just less accurate. */
  override protected def estimateRequest(call: ProviderCall): Int = {
    val body = obj(
      "messages" -> arr(renderMessagesArray(call)*),
      "tools"    -> arr(renderToolsArray(call)*)
    )
    val req = HttpRequest(
      method = HttpMethod.Post,
      url = url.withPath("/apply-template"),
      content = Some(StringContent(JsonFormatter.Compact(body), ContentType.`application/json`))
    )
      // Bug #56 — `Connection: close` to encourage the backend to
      // drop the TCP slot rather than letting it sit warm in the
      // pool only to go stale before the next pre-flight call.
      .withHeader("Connection", "close")
    // Bug #54 — hard timeout so a stalled `/apply-template` call
    // can't block the agent loop indefinitely. On timeout / error,
    // fall through to the parent's piecewise estimator, which still
    // produces a usable (less accurate) number from the tokenizer
    // pass over individual sections.
    //
    // Bug #56 — single fast retry. The default 2-retry × 1s-delay
    // policy adds a needless 2s of dead time when the first pool-
    // acquire hits a stale connection; one 100ms-delay retry
    // recovers cleanly without padding the wall-clock cost.
    val task: Task[Int] = HttpClient.modify(_ => req)
      .interceptor(sigilRef.wireInterceptor)
      .timeout(estimateTimeout)
      .retryManager(spice.http.client.RetryManager.simple(retries = 1, delay = 100.millis, warnRetries = false))
      .call[Json].map { json =>
        val rendered = json.get("prompt").map(_.asString).getOrElse("")
        tokenizer.count(rendered)
      }.handleError(_ => Task.pure(super.estimateRequest(call)))
    task.sync()
  }

  /** Hard wall-clock cap on `/apply-template`. Pre-flight estimate
    * is single-roundtrip work that must fail fast on backend
    * stall — distinct from the streaming chat-completions path
    * (which uses the per-read idle timeout). */
  protected def estimateTimeout: FiniteDuration = 5.seconds

  /** Serialize the uniform [[ProviderCall]] to a llama.cpp / OpenAI-compatible
    * chat-completions request and run the streaming response through
    * [[SSELineParser]] + chunk parsing. */
  override def call(input: ProviderCall): Stream[ProviderEvent] = {
    val state = new StreamState(new ToolCallAccumulator(input.tools))
    Stream.force(
      for {
        raw         <- httpRequestFor(input)
        intercepted <- sigilRef.wireInterceptor.before(raw)
        lines       <- HttpClient.modify(_ => intercepted).noFailOnHttpStatus.timeout(tokenIdleTimeout).streamLines()
      } yield {
        // okhttp's per-read timeout (configured via HttpClient.timeout)
        // already errors the stream when no bytes arrive for
        // `tokenIdleTimeout` — slow-but-working models keep streaming;
        // genuinely stuck streams fail naturally. No additional
        // wall-clock kill at the rapid-Stream layer.
        _root_.sigil.provider.debug.StreamWireInterceptor.attach(
          lines, sigilRef.wireInterceptor, intercepted
        ) { line =>
          Stream.emits(parseLine(line, state))
        }
      }
    )
  }

  /** Build the wire-level chat-completions HttpRequest from a uniform
    * ProviderCall. Used both by [[call]] and (via the trait's final
    * `requestConverter`) by inspect-only test paths. */
  override def httpRequestFor(input: ProviderCall): Task[HttpRequest] = Task {
    val bodyStr = JsonFormatter.Compact(buildBody(input))
    HttpRequest(
      method = HttpMethod.Post,
      url = url.withPath("/v1/chat/completions"),
      content = Some(StringContent(bodyStr, ContentType.`application/json`))
    )
  }

  // ---- request body construction ----

  /** Render the wire `messages` array exactly as `buildBody` produces
    * it — leading-system fold, mid-array system fold via
    * `foldMidArraySystems`, placeholder-user injection. Extracted so
    * `estimateRequest` can ship the identical array to
    * `/apply-template` for backend-exact pre-flight tokenization
    * (bug #46). */
  private def renderMessagesArray(input: ProviderCall): Vector[Json] = {
    val (leadingSystem, nonLeading) = input.messages.span {
      case _: ProviderMessage.System => true
      case _ => false
    }
    val combinedSystem = (input.system +: leadingSystem.collect {
      case ProviderMessage.System(c) => c
    }).filter(_.nonEmpty).mkString("\n\n")
    val systemMsg = obj("role" -> str("system"), "content" -> str(combinedSystem))
    val rendered = renderMessages(nonLeading)
    val withUserAnchor =
      if (rendered.exists(m => m.get("role").exists(_.asString == "user"))) rendered
      else placeholderUserMessage +: rendered
    Vector(systemMsg) ++ withUserAnchor
  }

  /** Render the wire `tools` array. Extracted so `estimateRequest`
    * (bug #46 / #47) can ship the identical array to
    * `/apply-template` for backend-exact pre-flight tokenization
    * — gemma-class chat templates emit tool-declaration blocks
    * inline in the rendered prompt, so passing both messages and
    * tools to `/apply-template` collapses the entire pre-flight
    * estimate into a single HTTP round-trip. */
  private def renderToolsArray(input: ProviderCall): Vector[Json] =
    input.tools.map { t =>
      val s = t.schema
      obj(
        "type" -> str("function"),
        "function" -> obj(
          "name"        -> str(s.name.value),
          "description" -> str(renderDescription(t, input.currentMode)),
          "parameters"  -> DefinitionToSchema(s.input)
        )
      )
    }

  private def buildBody(input: ProviderCall): Json = {
    val modelName = stripProviderPrefix(input.modelId.value)
    val finalMessages = renderMessagesArray(input)

    // llama.cpp's chat-completions endpoint translates the FULL JSON
    // Schema into a GBNF grammar — including `pattern`, `format`,
    // `minLength`/`maxLength`, numeric bounds, and array bounds. Pass
    // `DefinitionToSchema` straight through; the model is grammar-
    // constrained at generation time on every annotation that lives
    // on the Scala types (e.g. `RespondInput.content` must start with
    // `▶<TYPE>\n`). `ToolInputValidator` re-checks post-decode for
    // safety but the generation-time enforcement is the real win.
    val toolsArr = renderToolsArray(input)

    // Qwen3 toggles thinking via chat_template_kwargs.enable_thinking.
    // On when the caller sets `effort`; off otherwise (default keeps
    // tool selection tight — thinking on Qwen3 nudges the model toward
    // clarifying tools like `respond` instead of `change_mode`). Other
    // llama.cpp-hosted models (DeepSeek-R1, gpt-oss) ignore this kwarg
    // and drive reasoning from their own chat template — which is fine,
    // because `reasoning_content` parsing below handles either path.
    val thinkingEnabled = input.generationSettings.effort.isDefined
    val baseFields = Vector[(String, Json)](
      "model" -> str(modelName),
      "messages" -> arr(finalMessages*),
      "stream" -> bool(true),
      // Emit a final chunk with token usage before [DONE]
      "stream_options" -> obj("include_usage" -> bool(true)),
      "chat_template_kwargs" -> obj("enable_thinking" -> bool(thinkingEnabled))
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
    val generationFields: Vector[(String, Json)] =
      gen.temperature.toVector.map("temperature" -> num(_)) ++
        gen.maxOutputTokens.toVector.map("max_tokens" -> num(_)) ++
        gen.topP.toVector.map("top_p" -> num(_)) ++
        (if (gen.stopSequences.nonEmpty) Vector("stop" -> arr(gen.stopSequences.map(str)*)) else Vector.empty)

    obj((baseFields ++ toolFields ++ generationFields)*)
  }

  /** Render format-neutral [[ProviderMessage]]s into OpenAI chat-completions
    * message format.
    *
    * **System-message folding.** Some llama.cpp chat templates (notably
    * Qwen3.5) reject any `system`-role message that isn't the very
    * first in the array — they raise "System message must be at the
    * beginning" with an HTTP 500. Sigil's `FrameBuilder` legitimately
    * emits mid-conversation `System` frames for things like
    * `TopicChange` settles, which then surface as `ProviderMessage.System`
    * mid-array. We pre-process by folding any non-leading System
    * content into the next non-system message as a `[system: ...]`
    * prefix on its content; if there is no following message we fold
    * into the previous assistant/user message. The framework's leading
    * system prompt (assembled in `buildBody`) is untouched.
    */
  /** Single placeholder user-role message used to satisfy chat-template
    * invariants (e.g. Qwen3.5's "No user query found") when a turn
    * legitimately has no user content (greet-on-join). The text is
    * incidental — what matters is that a user-role entry exists. */
  private val placeholderUserMessage: Json =
    obj("role" -> str("user"), "content" -> str("(begin conversation)"))

  private def renderMessages(messages: Vector[ProviderMessage]): Vector[Json] = {
    val folded = foldMidArraySystems(messages)
    folded.flatMap {
      case ProviderMessage.System(content) =>
        Vector(obj("role" -> str("system"), "content" -> str(content)))
      case ProviderMessage.User(blocks) =>
        // LlamaCpp is text-only via this client; collapse multipart content
        // to a plain string, dropping any image blocks. Multimodal llama
        // builds (LLaVA, etc.) live behind a different upstream surface and
        // would need their own provider. Surface a WARN per drop so apps
        // using vision-capable Sigil features notice the gap.
        val (texts, images) = blocks.foldRight((List.empty[String], 0)) {
          case (MessageContent.Text(t), (ts, n))     => (t :: ts, n)
          case (MessageContent.Image(_, _), (ts, n)) => (ts, n + 1)
        }
        if (images > 0) scribe.warn(
          s"LlamaCppProvider: dropped $images image block(s) — this client speaks only the " +
            s"text-only OpenAI-compatible surface. Wire a multimodal-aware provider for vision."
        )
        Vector(obj("role" -> str("user"), "content" -> str(texts.mkString("\n"))))
      case ProviderMessage.Assistant(content, toolCalls) =>
        Vector(
          if (toolCalls.isEmpty) obj("role" -> str("assistant"), "content" -> str(content))
          else obj(
            "role" -> str("assistant"),
            "tool_calls" -> arr(toolCalls.map { tc =>
              obj(
                "id" -> str(tc.id),
                "type" -> str("function"),
                "function" -> obj(
                  "name" -> str(tc.name),
                  "arguments" -> str(tc.argsJson)
                )
              )
            }*)
          )
        )
      case ProviderMessage.ToolResult(toolCallId, content) =>
        Vector(obj(
          "role" -> str("tool"),
          "tool_call_id" -> str(toolCallId),
          "content" -> str(content)
        ))
      case _: ProviderMessage.Reasoning =>
        // Provider-specific reasoning state from another provider's turn
        // (bug #61 — currently OpenAI-only). llama.cpp's chat-completions
        // surface has no slot for it; drop silently.
        Vector.empty
    }
  }

  /** Walk the message array; collect mid-array System content into a
    * pending buffer; flush the buffer onto the next non-system message
    * (User / Assistant / ToolResult) as a `[system: ...]` prefix. If
    * the array ends with pending System content, fold it into the last
    * non-system message instead. Leading System messages pass through
    * unchanged. */
  private def foldMidArraySystems(messages: Vector[ProviderMessage]): Vector[ProviderMessage] = {
    val out = scala.collection.mutable.ArrayBuffer.empty[ProviderMessage]
    val pending = scala.collection.mutable.ListBuffer.empty[String]
    var seenNonSystem = false
    messages.foreach {
      case ProviderMessage.System(content) if !seenNonSystem =>
        out += ProviderMessage.System(content)
      case ProviderMessage.System(content) =>
        pending += content
      case r: ProviderMessage.Reasoning =>
        // Reasoning state is foreign to llama.cpp; pass it through
        // untouched so `renderMessages` drops it. Don't treat it as a
        // textual carrier for pending system content — it has no text
        // surface to prepend to.
        out += r
      case other =>
        seenNonSystem = true
        out += (if (pending.nonEmpty) prependSystem(pending.toList, other) else other)
        pending.clear()
    }
    if (pending.nonEmpty) {
      // Trailing system content with nothing to fold into — append onto
      // the last entry (or just drop if `out` is empty).
      out.lastOption match {
        case Some(last) =>
          out(out.size - 1) = prependSystem(pending.toList, last)
        case None => ()
      }
    }
    out.toVector
  }

  private def prependSystem(systems: List[String], target: ProviderMessage): ProviderMessage = {
    val prefix = systems.map(s => s"[system: $s]").mkString("\n") + "\n"
    target match {
      case ProviderMessage.User(blocks) =>
        ProviderMessage.User(MessageContent.Text(prefix) +: blocks)
      case ProviderMessage.Assistant(content, toolCalls) =>
        ProviderMessage.Assistant(prefix + content, toolCalls)
      case ProviderMessage.ToolResult(id, content) =>
        ProviderMessage.ToolResult(id, prefix + content)
      case s: ProviderMessage.System => s  // shouldn't happen — caller filters
      case r: ProviderMessage.Reasoning => r  // shouldn't happen — caller filters
    }
  }

  // ---- streaming response parsing ----

  // `private[llamacpp]` so dedicated parser tests can drive the
  // chunk-level paths (especially Bug #8's inline-error detection)
  // without spinning up a stub HTTP server. Not part of the public
  // surface — apps invoke through `call(...)` like any other provider.
  private[llamacpp] def parseLine(line: String, state: StreamState): Vector[ProviderEvent] =
    SSELineParser.parse(line) match {
      case SSELine.Data(json) => parseChunk(json, state)
      case SSELine.Done       => state.flushDone()
      case SSELine.MalformedData(_, reason) =>
        Vector(ProviderEvent.Error(s"Failed to parse chunk: $reason"))
      case SSELine.Blank | SSELine.Comment | _: SSELine.Other => Vector.empty
    }

  private[llamacpp] def parseChunk(json: Json, state: StreamState): Vector[ProviderEvent] = {
    // Bug #8 — llama.cpp embeds server-side mid-stream failures as
    // `data: {"error": {...}}` events on a 200-OK chat-completions
    // stream. The choices-only parser would silently drop them and
    // the agent loop would see a no-op turn (→ "(agent completed
    // without a reply)" placeholder). Throw a ProviderStreamException
    // so the runAgentLoop handler from Bug #6 surfaces the upstream
    // error as a user-visible Failure Message instead.
    json.get("error").foreach { err =>
      if (!err.isNull) {
        val code = err.get("code").map(_.asInt).getOrElse(0)
        val msg  = err.get("message").map(_.asString).getOrElse("(no message)")
        val typ  = err.get("type").map(_.asString).getOrElse("error")
        throw new ProviderStreamException(LlamaCpp.Provider, code, typ, msg)
      }
    }
    val events = Vector.newBuilder[ProviderEvent]
    val choice = json.get("choices").flatMap(_.asVector.headOption)

    choice.flatMap(_.get("delta")).foreach { delta =>
      delta.get("content").foreach { c =>
        if (!c.isNull) {
          val text = c.asString
          if (text.nonEmpty) events += ProviderEvent.TextDelta(text)
        }
      }
      delta.get("reasoning_content").foreach { c =>
        if (!c.isNull) {
          val text = c.asString
          if (text.nonEmpty) events += ProviderEvent.ThinkingDelta(text)
        }
      }
      delta.get("tool_calls").foreach { toolCallsJson =>
        toolCallsJson.asVector.foreach { tc =>
          val index = tc.get("index").map(_.asInt).getOrElse(0)
          val callId = tc.get("id").flatMap(optString).map(LlamaCppProvider.normalizeWireId)
          val name = tc.get("function").flatMap(_.get("name")).flatMap(optString)
          (callId, name) match {
            case (Some(id), Some(nm)) => events ++= state.acc.start(index, CallId(id), nm)
            case _ =>
          }
          tc.get("function")
            .flatMap(_.get("arguments"))
            .flatMap(optString)
            .foreach(args => events ++= state.acc.appendArgs(index, args))
        }
      }
    }

    // finish_reason precedes usage. Flush any tool-call completes now,
    // but hold `Done` back until usage arrives (or [DONE]) so `Done`
    // remains terminal.
    choice.flatMap(_.get("finish_reason")).foreach { reason =>
      if (!reason.isNull) {
        val stopReason = mapFinishReason(reason.asString)
        if (stopReason == StopReason.ToolCall) events ++= state.acc.complete()
        state.pendingDone = Some(stopReason)
      }
    }

    json.get("usage").foreach { usage =>
      if (!usage.isNull) events += ProviderEvent.Usage(parseUsage(usage))
    }

    events.result()
  }

  private def optString(j: Json): Option[String] = if (j.isNull) None else Some(j.asString)

  private def parseUsage(json: Json): TokenUsage =
    TokenUsage(
      promptTokens = json.get("prompt_tokens").map(_.asInt).getOrElse(0),
      completionTokens = json.get("completion_tokens").map(_.asInt).getOrElse(0),
      totalTokens = json.get("total_tokens").map(_.asInt).getOrElse(0)
    )

  private def mapFinishReason(reason: String): StopReason = reason match {
    case "stop"           => StopReason.Complete
    case "length"         => StopReason.MaxTokens
    case "tool_calls"     => StopReason.ToolCall
    case "content_filter" => StopReason.ContentFiltered
    case other =>
      scribe.warn(s"Unmapped finish_reason from llama.cpp: '$other' — treating as Complete")
      StopReason.Complete
  }

  private def renderDescription(tool: Tool, mode: Mode): String = {
    val base = tool.wireDescription(mode, sigil)
    if (tool.examples.isEmpty) base
    else {
      val rendered = tool.examples.map { e =>
        val json = JsonFormatter.Compact(stripPolyDiscriminator(summon[RW[ToolInput]].read(e.input)))
        s"- ${e.description}: $json"
      }.mkString("\n")
      s"$base\n\nExamples:\n$rendered"
    }
  }

  private def stripPolyDiscriminator(json: Json): Json = json match {
    case o: Obj => Obj(o.value - "type")
    case other  => other
  }

  private def stripProviderPrefix(id: String): String = {
    val prefix = s"${LlamaCpp.Provider}/"
    if (id.startsWith(prefix)) id.drop(prefix.length) else id
  }

  final private[llamacpp] class StreamState(val acc: ToolCallAccumulator) {
    var pendingDone: Option[StopReason] = None

    def flushDone(): Vector[ProviderEvent] = pendingDone match {
      case Some(sr) =>
        pendingDone = None
        Vector(ProviderEvent.Done(sr))
      case None => Vector.empty
    }
  }
}

object LlamaCppProvider {

  /** Construct a [[LlamaCppProvider]] and seed its model catalog
    * into [[sigil.cache.ModelRegistry]]. The cache merge is the
    * registry's contract for every provider that carries its own
    * model list at construction (vs. relying on
    * [[sigil.controller.OpenRouter.refreshModels]] for the catalog) —
    * the curator and other consumers query the cache by id, so any
    * model the provider can serve must be visible there before a
    * turn runs against it. */
  def apply(sigil: Sigil, url: URL): Task[LlamaCppProvider] =
    LlamaCpp.loadModels(url).flatMap { models =>
      sigil.cache.merge(models).map(_ => LlamaCppProvider(url, models, sigil))
    }

  /** Map any tool-call id from the wire to a 9-char alphanumeric, applied
    * at parse time so the framework stores the canonical form throughout.
    * Some local-model chat templates (e.g. Mistral NeMo) hard-validate
    * this length on subsequent turns; coercing at parse means later
    * write-time emits the same form the chat template expects, and
    * ContextFrame projections / replays carry consistent ids. Already-
    * conformant ids (9 alphanumeric chars) pass through unchanged. */
  def normalizeWireId(id: String): String =
    if (id.length == 9 && id.forall(_.isLetterOrDigit)) id
    else {
      val hash = java.util.UUID.nameUUIDFromBytes(id.getBytes(java.nio.charset.StandardCharsets.UTF_8))
      hash.toString.replace("-", "").take(9)
    }
}
