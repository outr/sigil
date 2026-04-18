package sigil.provider.llamacpp

import fabric.*
import fabric.io.{JsonFormatter, JsonParser}
import fabric.rw.*
import rapid.{Stream, Task}
import sigil.db.Model
import sigil.event.Message
import sigil.provider.*
import sigil.tool.model.ResponseContent
import spice.http.client.HttpClient
import spice.http.content.StringContent
import spice.net.*

case class LlamaCppProvider(url: URL, models: List[Model]) extends Provider {
  override def `type`: ProviderType = ProviderType.LlamaCpp

  /** Streaming implementation: POST to `/v1/chat/completions` with `stream: true`,
    * parse the SSE line stream into [[ProviderEvent]]s.
    *
    * Each `data: {chunk}` line produces 0+ events — a `TextDelta` for non-empty
    * delta content, a `Usage` when usage is reported (typically the last chunk),
    * and a `Done` when `finish_reason` arrives. The sentinel `data: [DONE]` line
    * is a no-op terminator; `Done` is driven by `finish_reason`. */
  override def apply(request: ProviderRequest): Stream[ProviderEvent] = {
    val modelName = stripProviderPrefix(request.modelId.value)
    val body = buildBody(modelName, request)
    val bodyStr = JsonFormatter.Compact(body)

    Stream.force(
      HttpClient
        .url(url.withPath("/v1/chat/completions"))
        .post
        .content(StringContent(bodyStr, ContentType.`application/json`))
        .noFailOnHttpStatus
        .streamLines()
        .map(lines => lines.flatMap(line => Stream.emits(parseLine(line))))
    )
  }

  private def buildBody(modelName: String, request: ProviderRequest): Json = {
    val systemMsg = obj("role" -> str("system"), "content" -> str(request.instructions.system))
    val devMsg = request.instructions.developer.toVector.map(d =>
      obj("role" -> str("developer"), "content" -> str(d))
    )
    val messages = request.events.collect {
      case m: Message =>
        val text = m.content.map {
          case ResponseContent.Text(t)       => t
          case ResponseContent.Markdown(t)   => t
          case ResponseContent.Code(c, lang) => s"```${lang.getOrElse("")}\n$c\n```"
          case other                          => other.toString
        }.mkString("\n")
        obj("role" -> str("user"), "content" -> str(text))
    }

    val fields = Vector[(String, Json)](
      "model" -> str(modelName),
      "messages" -> arr((Vector(systemMsg) ++ devMsg ++ messages)*),
      "stream" -> bool(true),
      // Qwen 3.x: suppress <think> blocks so the content field is non-empty
      "chat_template_kwargs" -> obj("enable_thinking" -> bool(false))
    ) ++ request.generationSettings.temperature.toVector.map("temperature" -> num(_)) ++
      request.generationSettings.maxOutputTokens.toVector.map("max_tokens" -> num(_))

    obj(fields*)
  }

  private def parseLine(line: String): Vector[ProviderEvent] = {
    val trimmed = line.trim
    if (trimmed.isEmpty || trimmed.startsWith(":")) Vector.empty
    else if (trimmed == "data: [DONE]") Vector.empty
    else if (trimmed.startsWith("data: ")) {
      val payload = trimmed.drop(6)
      try {
        parseChunk(JsonParser(payload))
      } catch {
        case t: Throwable =>
          Vector(ProviderEvent.Error(s"Failed to parse chunk: ${t.getMessage}"))
      }
    } else Vector.empty
  }

  private def parseChunk(json: Json): Vector[ProviderEvent] = {
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
    }

    json.get("usage").foreach { usage =>
      if (!usage.isNull) events += ProviderEvent.Usage(parseUsage(usage))
    }

    choice.flatMap(_.get("finish_reason")).foreach { reason =>
      if (!reason.isNull) events += ProviderEvent.Done(mapFinishReason(reason.asString))
    }

    events.result()
  }

  private def parseUsage(json: Json): TokenUsage = TokenUsage(
    promptTokens = json.get("prompt_tokens").map(_.asInt).getOrElse(0),
    completionTokens = json.get("completion_tokens").map(_.asInt).getOrElse(0),
    totalTokens = json.get("total_tokens").map(_.asInt).getOrElse(0)
  )

  private def mapFinishReason(reason: String): StopReason = reason match {
    case "stop"           => StopReason.Complete
    case "length"         => StopReason.MaxTokens
    case "tool_calls"     => StopReason.ToolCall
    case "content_filter" => StopReason.ContentFiltered
    case other            => StopReason.Unknown(other)
  }

  private def stripProviderPrefix(id: String): String = {
    val prefix = s"${LlamaCpp.Provider}/"
    if (id.startsWith(prefix)) id.drop(prefix.length) else id
  }
}

object LlamaCppProvider {
  def apply(url: URL = url"http://localhost:8081"): Task[LlamaCppProvider] = LlamaCpp.loadModels(url)
    .map { models =>
      LlamaCppProvider(url, models)
    }
}
