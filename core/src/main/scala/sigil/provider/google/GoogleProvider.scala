package sigil.provider.google

import fabric.*
import fabric.io.JsonFormatter
import rapid.{Stream, Task}
import sigil.Sigil
import sigil.db.Model
import sigil.provider.*
import sigil.provider.sse.{SSELine, SSELineParser}
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
 */
case class GoogleProvider(apiKey: String,
                          sigilRef: Sigil,
                          baseUrl: URL = url"https://generativelanguage.googleapis.com",
                          streamTimeout: FiniteDuration = 120.seconds) extends Provider {
  override def `type`: ProviderType = ProviderType.Google
  override val providerKey: String = Google.Provider
  override protected def sigil: Sigil = sigilRef

  override def call(input: ProviderCall): Stream[ProviderEvent] = {
    val state = new StreamState(new ToolCallAccumulator(input.tools))
    Stream.force(
      for {
        raw         <- httpRequestFor(input)
        intercepted <- sigilRef.wireInterceptor.before(raw)
        lines       <- HttpClient.modify(_ => intercepted).noFailOnHttpStatus.timeout(streamTimeout).streamLines()
      } yield {
        val bodyBuf = new StringBuilder
        // Bug #77 — overall stream-lifetime deadline; see OpenAIProvider rationale.
        lines
          .timeout(streamTimeout)
          .flatMap { line =>
            bodyBuf.append(line).append('\n')
            Stream.emits(parseLine(line, state))
          }
          .onFinalize(Task.defer {
            val response = HttpResponse(
              status = HttpStatus.OK,
              content = Some(StringContent(bodyBuf.toString, ContentType("text", "event-stream")))
            )
            sigilRef.wireInterceptor.after(intercepted, Success(response)).unit
          })
      }
    )
  }

  override def httpRequestFor(input: ProviderCall): Task[HttpRequest] = Task {
    val modelName = Google.stripProviderPrefix(input.modelId.value)
    val bodyStr = JsonFormatter.Compact(buildBody(input))
    HttpRequest(
      method = HttpMethod.Post,
      url = baseUrl.withPath(s"/v1beta/models/$modelName:streamGenerateContent").withParam("alt", "sse"),
      content = Some(StringContent(bodyStr, ContentType.`application/json`))
    ).withHeader("x-goog-api-key", apiKey)
  }

  private def buildBody(input: ProviderCall): Json = {
    val systemObj: Vector[(String, Json)] =
      if (input.system.isEmpty) Vector.empty
      else Vector("systemInstruction" -> obj("parts" -> arr(obj("text" -> str(input.system)))))

    val contents = renderContents(input.messages)

    // Custom function tools go in one `functionDeclarations` group;
    // built-in tools get their own top-level entries in the `tools` array.
    val functionTools =
      if (input.tools.isEmpty) Vector.empty
      else Vector(obj("functionDeclarations" -> arr(input.tools.map(t => toFunctionDeclaration(t, input.currentMode))*)))
    val builtInTools = input.builtInTools.iterator.flatMap(renderBuiltIn).toVector
    val toolsArr = functionTools ++ builtInTools

    val toolFields: Vector[(String, Json)] =
      if (toolsArr.isEmpty) Vector.empty
      else Vector(
        "tools" -> arr(toolsArr*),
        "toolConfig" -> obj("functionCallingConfig" -> obj(
          "mode" -> str(input.toolChoice match {
            case ToolChoice.None     => "NONE"
            case ToolChoice.Auto     => "AUTO"
            case ToolChoice.Required => "ANY"
          })
        ))
      )

    val gen = input.generationSettings
    val genConfig = Vector.newBuilder[(String, Json)]
    gen.temperature.foreach(v => genConfig += ("temperature" -> num(v)))
    gen.maxOutputTokens.foreach(v => genConfig += ("maxOutputTokens" -> num(v)))
    gen.topP.foreach(v => genConfig += ("topP" -> num(v)))
    if (gen.stopSequences.nonEmpty) genConfig += ("stopSequences" -> arr(gen.stopSequences.map(str)*))
    // Gemini 2.5 "thinking": off by default (budget = 0) because thinking
    // tokens are billed against maxOutputTokens and routinely truncate
    // tool-call responses before any function call is emitted. When the
    // caller sets `generationSettings.effort`, we translate that to a
    // positive (or -1 dynamic) budget.
    val thinkingBudget = gen.effort.fold(0)(Effort.googleThinkingBudget)
    genConfig += ("thinkingConfig" -> obj("thinkingBudget" -> num(thinkingBudget)))
    val genConfigFields: Vector[(String, Json)] =
      Vector("generationConfig" -> obj(genConfig.result()*))

    val base = Vector("contents" -> arr(contents*))
    obj((base ++ systemObj ++ toolFields ++ genConfigFields)*)
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
          case MessageContent.Image(u, _) =>
            // Gemini accepts `fileData` references; for URL inputs the shape is:
            obj("fileData" -> obj("fileUri" -> str(u.toString), "mimeType" -> str("image/png")))
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
        // (bug #61 — currently OpenAI-only). Gemini has no analogous slot;
        // drop silently.
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
      "description" -> str(renderDescription(t, mode)),
      "parameters"  -> StrictSchema.forGemini(DefinitionToSchema(s.input))
    )
  }

  private def renderBuiltIn(tool: BuiltInTool): Option[Json] = tool match {
    case BuiltInTool.WebSearch => Some(obj("googleSearch" -> obj()))
    case BuiltInTool.CodeInterpreter => Some(obj("codeExecution" -> obj()))
    case _ => None
  }

  private def renderDescription(tool: Tool, mode: Mode): String = {
    val base = tool.descriptionFor(mode, sigil)
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

  // ---- response parsing ----

  private def parseLine(line: String, state: StreamState): Vector[ProviderEvent] =
    SSELineParser.parse(line) match {
      case SSELine.Data(json) => parseChunk(json, state)
      case SSELine.Done       => state.flushDone()
      case SSELine.MalformedData(_, r) => Vector(ProviderEvent.Error(s"parse: $r"))
      case SSELine.Blank | SSELine.Comment | _: SSELine.Other => Vector.empty
    }

  /** Parse a Gemini streamed chunk. Each chunk is a `GenerateContentResponse`
    * JSON object with `candidates`, optional `usageMetadata`, and
    * optional `finishReason` on a candidate. */
  private def parseChunk(json: Json, state: StreamState): Vector[ProviderEvent] = {
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
            case _               => StopReason.Complete
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

  private def parseUsage(json: Json): TokenUsage =
    TokenUsage(
      promptTokens = json.get("promptTokenCount").map(_.asInt).getOrElse(0),
      completionTokens = json.get("candidatesTokenCount").map(_.asInt).getOrElse(0),
      totalTokens = json.get("totalTokenCount").map(_.asInt).getOrElse(0)
    )

  final private class StreamState(val acc: ToolCallAccumulator) {
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
}
