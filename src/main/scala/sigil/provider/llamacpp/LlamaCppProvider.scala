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
import spice.http.{HttpMethod, HttpRequest, HttpResponse, HttpStatus}
import spice.http.client.HttpClient
import spice.http.content.StringContent
import spice.net.*

import scala.util.Success

case class LlamaCppProvider(url: URL, models: List[Model], sigilRef: Sigil) extends Provider {
  override def `type`: ProviderType = ProviderType.LlamaCpp

  override protected def sigil: Sigil = sigilRef

  /** Serialize the uniform [[ProviderCall]] to a llama.cpp / OpenAI-compatible
    * chat-completions request and run the streaming response through
    * [[SSELineParser]] + chunk parsing. */
  override protected def call(input: ProviderCall): Stream[ProviderEvent] = {
    val state = new StreamState(new ToolCallAccumulator(input.tools))
    Stream.force(
      for {
        raw         <- httpRequestFor(input)
        intercepted <- sigilRef.wireInterceptor.before(raw)
        lines       <- HttpClient.modify(_ => intercepted).noFailOnHttpStatus.streamLines()
      } yield {
        // Tap the stream to accumulate the full SSE body; invoke
        // `.after()` at stream completion so wireInterceptor records
        // the response too. Spice's `streamLines()` otherwise bypasses
        // the interceptor chain entirely.
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
      "messages" -> arr((Vector(systemMsg) ++ rendered)*),
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
    * message format. */
  private def renderMessages(messages: Vector[ProviderMessage]): Vector[Json] =
    messages.map {
      case ProviderMessage.System(content) =>
        obj("role" -> str("system"), "content" -> str(content))
      case ProviderMessage.User(blocks) =>
        // LlamaCpp is text-only; collapse multipart content to a plain
        // string, dropping any image blocks. Vision-capable providers
        // will render each block as the API expects.
        val text = blocks.iterator.collect { case MessageContent.Text(t) => t }.mkString("\n")
        obj("role" -> str("user"), "content" -> str(text))
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

  private def renderDescription[I <: ToolInput](schema: ToolSchema): String =
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
