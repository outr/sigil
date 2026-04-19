package sigil.provider.llamacpp

import fabric.*
import fabric.io.{JsonFormatter, JsonParser}
import fabric.rw.*
import rapid.{Stream, Task}
import sigil.db.Model
import sigil.event.Message
import sigil.provider.*
import sigil.tool.{DefinitionToSchema, ToolInput, ToolSchema}
import sigil.tool.model.ResponseContent
import spice.http.client.HttpClient
import spice.http.content.StringContent
import spice.net.*

case class LlamaCppProvider(url: URL, models: List[Model]) extends Provider {
  override def `type`: ProviderType = ProviderType.LlamaCpp

  /**
   * Streaming implementation: POST to `/v1/chat/completions` with `stream: true`,
   * parse the SSE line stream into [[ProviderEvent]]s.
   *
   * When `request.tools` is non-empty, `tool_choice: "required"` is sent so the
   * model is forced to emit a tool call. Tool-call argument streaming is
   * accumulated into a final `ToolCallComplete` event when `finish_reason`
   * arrives.
   */
  override def apply(request: ProviderRequest): Stream[ProviderEvent] = {
    val modelName = stripProviderPrefix(request.modelId.value)
    val body = buildBody(modelName, request)
    val bodyStr = JsonFormatter.Compact(body)
    val state = new StreamState(new ToolCallAccumulator(request.tools))

    Stream.force(
      HttpClient
        .url(url.withPath("/v1/chat/completions"))
        .post
        .content(StringContent(bodyStr, ContentType.`application/json`))
        .noFailOnHttpStatus
        .streamLines()
        .map(lines => lines.flatMap(line => Stream.emits(parseLine(line, state))))
    )
  }

  private def buildBody(modelName: String, request: ProviderRequest): Json = {
    val modePreamble = s"Current mode: ${request.currentMode} — ${request.currentMode.description}\n\n"
    val systemMsg = obj("role" -> str("system"), "content" -> str(modePreamble + request.instructions.render))
    val messages = request.events.collect { case m: Message =>
      val text = m.content
        .map {
          case ResponseContent.Text(t) => t
          case ResponseContent.Markdown(t) => t
          case ResponseContent.Code(c, lang) => s"```${lang.getOrElse("")}\n$c\n```"
          case other => other.toString
        }
        .mkString("\n")
      obj("role" -> str("user"), "content" -> str(text))
    }

    val toolsArr = request.tools.map { t =>
      val s = t.schema
      obj(
        "type" -> str("function"),
        "function" -> obj(
          "name" -> str(s.name),
          "description" -> str(renderDescription(s)),
          "parameters" -> DefinitionToSchema(s.input)
        )
      )
    }

    val baseFields = Vector[(String, Json)](
      "model" -> str(modelName),
      "messages" -> arr((Vector(systemMsg) ++ messages)*),
      "stream" -> bool(true),
      // Emit a final chunk with token usage before [DONE]
      "stream_options" -> obj("include_usage" -> bool(true)),
      // Qwen 3.x: suppress <think> blocks so the content field is non-empty
      "chat_template_kwargs" -> obj("enable_thinking" -> bool(false))
    )
    val toolFields: Vector[(String, Json)] =
      if (toolsArr.isEmpty) Vector.empty
      else
        Vector(
          "tools" -> arr(toolsArr*),
          "tool_choice" -> str("required")
        )
    val generationFields: Vector[(String, Json)] =
      request.generationSettings.temperature.toVector.map("temperature" -> num(_)) ++
        request.generationSettings.maxOutputTokens.toVector.map("max_tokens" -> num(_))

    obj((baseFields ++ toolFields ++ generationFields)*)
  }

  private def parseLine(line: String, state: StreamState): Vector[ProviderEvent] = {
    val trimmed = line.trim
    if (trimmed.isEmpty || trimmed.startsWith(":")) Vector.empty
    else if (trimmed == "data: [DONE]") state.flushDone()
    else if (trimmed.startsWith("data: ")) {
      val payload = trimmed.drop(6)
      try parseChunk(JsonParser(payload), state)
      catch {
        case t: Throwable =>
          Vector(ProviderEvent.Error(s"Failed to parse chunk: ${t.getMessage}"))
      }
    } else Vector.empty
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

    // finish_reason precedes usage in the emitted order. Flush any tool-call
    // completes now, but hold `Done` back until usage arrives (or [DONE]) so
    // `Done` remains terminal.
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

  private def mapFinishReason(reason: String): StopReason =
    reason match {
      case "stop" => StopReason.Complete
      case "length" => StopReason.MaxTokens
      case "tool_calls" => StopReason.ToolCall
      case "content_filter" => StopReason.ContentFiltered
      case other =>
        scribe.warn(s"Unmapped finish_reason from llama.cpp: '$other' — treating as Complete")
        StopReason.Complete
    }

  private def renderDescription[I <: ToolInput](schema: ToolSchema[I]): String =
    if (schema.examples.isEmpty) schema.description
    else {
      val rendered = schema.examples.map(e => s"- ${e.description}: ${e.input}").mkString("\n")
      s"${schema.description}\n\nExamples:\n$rendered"
    }

  private def stripProviderPrefix(id: String): String = {
    val prefix = s"${LlamaCpp.Provider}/"
    if (id.startsWith(prefix)) id.drop(prefix.length) else id
  }

  private final class StreamState(val acc: ToolCallAccumulator) {
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
  def apply(url: URL = url"http://localhost:8081"): Task[LlamaCppProvider] =
    LlamaCpp
      .loadModels(url)
      .map { models =>
        LlamaCppProvider(url, models)
      }
}
