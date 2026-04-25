package sigil.provider.anthropic

import fabric.*
import fabric.io.JsonFormatter
import rapid.{Stream, Task}
import sigil.Sigil
import sigil.db.Model
import sigil.provider.*
import sigil.provider.sse.{SSELine, SSELineParser}
import sigil.tool.{DefinitionToSchema, ToolInput, ToolSchema}
import sigil.tool.ToolInput.given
import spice.http.{HttpMethod, HttpRequest, HttpResponse, HttpStatus}
import spice.http.client.HttpClient
import spice.http.content.StringContent
import spice.net.*

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
                             baseUrl: URL = url"https://api.anthropic.com") extends Provider {
  override def `type`: ProviderType = ProviderType.Anthropic
  override val providerKey: String = Anthropic.Provider
  override protected def sigil: Sigil = sigilRef

  override protected def call(input: ProviderCall): Stream[ProviderEvent] = {
    val state = new StreamState(new ToolCallAccumulator(input.tools))
    Stream.force(
      for {
        raw         <- httpRequestFor(input)
        intercepted <- sigilRef.wireInterceptor.before(raw)
        lines       <- HttpClient.modify(_ => intercepted).noFailOnHttpStatus.streamLines()
      } yield {
        val bodyBuf = new StringBuilder
        lines
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

  override protected def httpRequestFor(input: ProviderCall): Task[HttpRequest] = Task {
    val bodyStr = JsonFormatter.Compact(buildBody(input))
    HttpRequest(
      method = HttpMethod.Post,
      url = baseUrl.withPath("/v1/messages"),
      content = Some(StringContent(bodyStr, ContentType.`application/json`))
    )
      .withHeader("x-api-key", apiKey)
      .withHeader("anthropic-version", Anthropic.ApiVersion)
  }

  // ---- request body ----

  private def buildBody(input: ProviderCall): Json = {
    val modelName = Anthropic.stripProviderPrefix(input.modelId.value)
    // max_tokens is required by Anthropic's API. Fall back to 4096 if
    // the caller didn't set one.
    val maxTokens = input.generationSettings.maxOutputTokens.getOrElse(4096)

    val messages = renderMessages(input.messages)
    val toolsArr = renderTools(input)

    val base = Vector[(String, Json)](
      "model" -> str(modelName),
      "max_tokens" -> num(maxTokens),
      "messages" -> arr(messages*),
      "stream" -> bool(true)
    )
    val systemField: Vector[(String, Json)] =
      if (input.system.isEmpty) Vector.empty else Vector("system" -> str(input.system))

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
    }

    val gen = input.generationSettings
    // Anthropic thinking: when enabled, temperature must be 1.0 and
    // top_p/top_k are ignored. Fail fast on a conflicting temperature
    // rather than silently overriding the caller's setting.
    val thinkingField: Vector[(String, Json)] = gen.effort match {
      case None => Vector.empty
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
        (if (gen.effort.isDefined) Vector.empty
         else gen.topP.toVector.map("top_p" -> num(_))) ++
        (if (gen.stopSequences.nonEmpty) Vector("stop_sequences" -> arr(gen.stopSequences.map(str)*)) else Vector.empty)

    obj((base ++ systemField ++ toolFields ++ thinkingField ++ genFields)*)
  }

  private def renderMessages(messages: Vector[ProviderMessage]): Vector[Json] =
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
    }

  private def renderTools(input: ProviderCall): Vector[Json] = {
    val fns = input.tools.map { t =>
      val s = t.schema
      obj(
        "name" -> str(s.name.value),
        "description" -> str(renderDescription(s)),
        "input_schema" -> DefinitionToSchema(s.input)
      )
    }
    val builtIn = input.builtInTools.iterator.flatMap(renderBuiltIn).toVector
    fns ++ builtIn
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

  private def renderDescription[I <: ToolInput](schema: ToolSchema): String =
    if (schema.examples.isEmpty) schema.description
    else {
      val rendered = schema.examples.map { e =>
        val json = JsonFormatter.Compact(stripPolyDiscriminator(summon[fabric.rw.RW[ToolInput]].read(e.input)))
        s"- ${e.description}: $json"
      }.mkString("\n")
      s"${schema.description}\n\nExamples:\n$rendered"
    }

  private def stripPolyDiscriminator(json: Json): Json = json match {
    case o: Obj => Obj(o.value - "type")
    case other  => other
  }

  // ---- response parsing ----

  private def parseLine(line: String, state: StreamState): Vector[ProviderEvent] =
    SSELineParser.parse(line) match {
      case SSELine.Data(json)                    => parseEvent(json, state)
      case SSELine.Done                          => state.flushDone()
      case SSELine.MalformedData(_, r)           => Vector(ProviderEvent.Error(s"parse: $r"))
      case SSELine.Blank | SSELine.Comment | _: SSELine.Other => Vector.empty
    }

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
            val toolName = block.get("name").map(_.asString).getOrElse("")
            val callId = CallId(s"anthro-st-$index")
            state.indexToCallId += (index -> callId)
            val mapped = if (toolName == "web_search") BuiltInTool.WebSearch else BuiltInTool.WebSearch
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
        val msg = json.get("error").flatMap(_.get("message")).map(_.asString).getOrElse("unknown error")
        Vector(ProviderEvent.Error(msg))

      case _ => Vector.empty
    }
  }

  private def parseUsage(json: Json): TokenUsage =
    TokenUsage(
      promptTokens = json.get("input_tokens").map(_.asInt).getOrElse(0),
      completionTokens = json.get("output_tokens").map(_.asInt).getOrElse(0),
      totalTokens = json.get("input_tokens").map(_.asInt).getOrElse(0) + json.get("output_tokens").map(_.asInt).getOrElse(0)
    )

  final private class StreamState(val acc: ToolCallAccumulator) {
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
  /** Construct an AnthropicProvider. Models are read from
    * [[sigil.cache.ModelRegistry]] at access time. */
  def create(sigil: Sigil, apiKey: String, baseUrl: URL = url"https://api.anthropic.com"): Task[AnthropicProvider] =
    Task.pure(AnthropicProvider(apiKey, sigil, baseUrl))
}
