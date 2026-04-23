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
import sigil.tool.{DefinitionToSchema, ToolInput, ToolName, ToolSchema}
import sigil.tool.ToolInput.given
import spice.http.{HttpMethod, HttpRequest}
import spice.http.client.HttpClient
import spice.http.content.StringContent
import spice.net.*

case class LlamaCppProvider(url: URL, models: List[Model], sigilRef: Sigil) extends Provider {
  override def `type`: ProviderType = ProviderType.LlamaCpp

  override protected def sigil: Sigil = sigilRef

  /** Serialize the uniform [[ProviderCall]] to a llama.cpp / OpenAI-compatible
    * chat-completions request and run the streaming response through
    * [[SSELineParser]] + chunk parsing. */
  override protected def call(input: ProviderCall): Stream[ProviderEvent] = {
    val state = new StreamState(new ToolCallAccumulator(input.tools))
    Stream.force(
      httpRequestFor(input).map { httpRequest =>
        HttpClient.modify(_ => httpRequest)
          .noFailOnHttpStatus
          .streamLines()
          .map(lines => lines.flatMap(line => Stream.emits(parseLine(line, state))))
      }.flatMap(identity)
    )
  }

  /** Build the wire-level chat-completions HttpRequest from a uniform
    * ProviderCall. Used both by [[call]] and (via the trait's final
    * `requestConverter`) by inspect-only test paths. */
  override protected def httpRequestFor(input: ProviderCall): Task[HttpRequest] = Task {
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
    val systemMsg = obj("role" -> str("system"), "content" -> str(input.system))
    val rendered = renderMessages(input.messages)

    val toolsArr = input.tools.map { t =>
      val s = t.schema
      obj(
        "type" -> str("function"),
        "function" -> obj(
          "name" -> str(s.name.value),
          "description" -> str(renderDescription(s)),
          "parameters" -> DefinitionToSchema(s.input)
        )
      )
    }

    val baseFields = Vector[(String, Json)](
      "model" -> str(modelName),
      "messages" -> arr((Vector(systemMsg) ++ rendered)*),
      "stream" -> bool(true),
      // Emit a final chunk with token usage before [DONE]
      "stream_options" -> obj("include_usage" -> bool(true)),
      // Qwen 3.x: suppress <think> blocks. The content-pattern enforces the
      // multipart header, but thinking still shifts tool selection (e.g.
      // clarifying `respond` instead of `change_mode`). Revisit once thinking
      // is driven per-Mode.
      "chat_template_kwargs" -> obj("enable_thinking" -> bool(false))
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
    // `effort` is intentionally not forwarded — llama.cpp's chat-completions
    // surface has no reasoning-effort knob. Providers that do (Anthropic,
    // OpenAI reasoning models) will consume it in their own converters.

    obj((baseFields ++ toolFields ++ generationFields)*)
  }

  /** Render format-neutral [[ProviderMessage]]s into OpenAI chat-completions
    * message format. */
  private def renderMessages(messages: Vector[ProviderMessage]): Vector[Json] =
    messages.map {
      case ProviderMessage.System(content) =>
        obj("role" -> str("system"), "content" -> str(content))
      case ProviderMessage.User(content) =>
        obj("role" -> str("user"), "content" -> str(content))
      case ProviderMessage.Assistant(content, toolCalls) =>
        if (toolCalls.isEmpty) {
          obj("role" -> str("assistant"), "content" -> str(content))
        } else {
          obj(
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
        }
      case ProviderMessage.ToolResult(toolCallId, content) =>
        obj(
          "role" -> str("tool"),
          "tool_call_id" -> str(toolCallId),
          "content" -> str(content)
        )
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
          val callId = tc.get("id").flatMap(optString)
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

  private def renderDescription[I <: ToolInput](schema: ToolSchema[I]): String =
    if (schema.examples.isEmpty) schema.description
    else {
      val rendered = schema.examples.map { e =>
        val json = JsonFormatter.Compact(stripPolyDiscriminator(summon[RW[ToolInput]].read(e.input)))
        s"- ${e.description}: $json"
      }.mkString("\n")
      s"${schema.description}\n\nExamples:\n$rendered"
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
  def apply(sigil: Sigil, url: URL): Task[LlamaCppProvider] =
    LlamaCpp
      .loadModels(url)
      .map(models => LlamaCppProvider(url, models, sigil))
}
