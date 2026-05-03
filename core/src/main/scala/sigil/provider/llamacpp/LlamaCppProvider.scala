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
                            streamTimeout: FiniteDuration = 120.seconds) extends Provider {
  override def `type`: ProviderType = ProviderType.LlamaCpp
  override val providerKey: String = LlamaCpp.Provider

  override protected def sigil: Sigil = sigilRef

  /** Serialize the uniform [[ProviderCall]] to a llama.cpp / OpenAI-compatible
    * chat-completions request and run the streaming response through
    * [[SSELineParser]] + chunk parsing. */
  override def call(input: ProviderCall): Stream[ProviderEvent] = {
    val state = new StreamState(new ToolCallAccumulator(input.tools))
    Stream.force(
      for {
        raw         <- httpRequestFor(input)
        intercepted <- sigilRef.wireInterceptor.before(raw)
        lines       <- HttpClient.modify(_ => intercepted).noFailOnHttpStatus.timeout(streamTimeout).streamLines()
      } yield {
        // Bug #77 — overall stream-lifetime deadline; see OpenAIProvider rationale.
        // Wire-interceptor capture: tap the stream to accumulate the full SSE
        // body and dispatch Success/Failure to `.after()` based on whether the
        // stream completed cleanly or raised. Spice's `streamLines()` would
        // otherwise bypass the interceptor chain entirely.
        _root_.sigil.provider.debug.StreamWireInterceptor.attach(
          lines.timeout(streamTimeout), sigilRef.wireInterceptor, intercepted
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

  private def buildBody(input: ProviderCall): Json = {
    val modelName = stripProviderPrefix(input.modelId.value)

    // Collapse any leading-System tail of `input.messages` into the
    // framework's `input.system` BEFORE rendering. Greet-on-join turns
    // typically arrive with `input.messages` consisting only of
    // ContextFrame.System entries (TopicChange / ModeChange history,
    // role descriptions). Without this collapse the placeholder-user
    // injection below ends up between the framework system and the
    // remaining System frames, yielding `[system, user, System1,
    // System2, ...]` which Qwen3.5's chat template rejects with
    // "System message must be at the beginning". `foldMidArraySystems`
    // (in `renderMessages`) still handles the other case — System
    // frames that appear AFTER non-System content during a live
    // conversation (mid-array TopicChange settles).
    val (leadingSystem, nonLeading) = input.messages.span {
      case _: ProviderMessage.System => true
      case _ => false
    }
    val combinedSystem = (input.system +: leadingSystem.collect {
      case ProviderMessage.System(c) => c
    }).filter(_.nonEmpty).mkString("\n\n")
    val systemMsg = obj("role" -> str("system"), "content" -> str(combinedSystem))
    val rendered = renderMessages(nonLeading)

    // Some llama.cpp chat templates (notably Qwen3.5) enforce a
    // minimum-one-user-message invariant — the Jinja template raises
    // "No user query found in messages" when the messages array has
    // only system/assistant entries. The greet-on-join turn is the
    // legitimate case: the agent is introducing itself before any
    // user input, so by construction there's no user content to
    // surface. Inject a synthetic placeholder so the template's
    // required-user-anchor is satisfied. The agent's role
    // descriptions / skill text drive the actual greeting content;
    // this placeholder is just appeasing Jinja.
    val withUserAnchor =
      if (rendered.exists(m => m.get("role").exists(_.asString == "user"))) rendered
      else placeholderUserMessage +: rendered

    // llama.cpp's chat-completions endpoint translates the FULL JSON
    // Schema into a GBNF grammar — including `pattern`, `format`,
    // `minLength`/`maxLength`, numeric bounds, and array bounds. Pass
    // `DefinitionToSchema` straight through; the model is grammar-
    // constrained at generation time on every annotation that lives
    // on the Scala types (e.g. `RespondInput.content` must start with
    // `▶<TYPE>\n`). `ToolInputValidator` re-checks post-decode for
    // safety but the generation-time enforcement is the real win.
    val toolsArr = input.tools.map { t =>
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
      "messages" -> arr((Vector(systemMsg) ++ withUserAnchor)*),
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

  private def parseLine(line: String, state: StreamState): Vector[ProviderEvent] =
    SSELineParser.parse(line) match {
      case SSELine.Data(json) => parseChunk(json, state)
      case SSELine.Done       => state.flushDone()
      case SSELine.MalformedData(_, reason) =>
        Vector(ProviderEvent.Error(s"Failed to parse chunk: $reason"))
      case SSELine.Blank | SSELine.Comment | _: SSELine.Other => Vector.empty
    }

  private def parseChunk(json: Json, state: StreamState): Vector[ProviderEvent] = {
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
    val base = tool.descriptionFor(mode, sigil)
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

  final private class StreamState(val acc: ToolCallAccumulator) {
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
