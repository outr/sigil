package sigil.provider.deepseek

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
 * DeepSeek provider — uses OpenAI-compatible chat-completions at
 * `https://api.deepseek.com/v1/chat/completions`. The wire shape is
 * the same as LlamaCppProvider's, minus the Qwen-specific
 * `chat_template_kwargs.enable_thinking` knob. When the caller sets
 * `GenerationSettings.effort`, we forward it as `reasoning_effort`
 * (low|medium|high); the reasoner model honors it, chat models ignore
 * it. Thinking tokens stream back as `reasoning_content`.
 *
 * Note: live testing requires a funded DeepSeek account — the API
 * returns HTTP 402 "Insufficient Balance" for unfunded keys, which
 * looks like a provider bug but isn't. See the test under
 * `DeepSeekRequestCoverageSpec` for deterministic wire coverage that
 * doesn't require balance.
 */
case class DeepSeekProvider(apiKey: String,
                            sigilRef: Sigil,
                            baseUrl: URL = url"https://api.deepseek.com",
                            streamTimeout: FiniteDuration = 120.seconds) extends Provider {
  override def `type`: ProviderType = ProviderType.DeepSeek
  override val providerKey: String = DeepSeek.Provider
  override protected def sigil: Sigil = sigilRef

  override protected def call(input: ProviderCall): Stream[ProviderEvent] = {
    val state = new StreamState(new ToolCallAccumulator(input.tools))
    Stream.force(
      for {
        raw         <- httpRequestFor(input)
        intercepted <- sigilRef.wireInterceptor.before(raw)
        lines       <- HttpClient.modify(_ => intercepted).noFailOnHttpStatus.timeout(streamTimeout).streamLines()
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
      url = baseUrl.withPath("/v1/chat/completions"),
      content = Some(StringContent(bodyStr, ContentType.`application/json`))
    ).withHeader("Authorization", s"Bearer $apiKey")
  }

  private def buildBody(input: ProviderCall): Json = {
    val modelName = DeepSeek.stripProviderPrefix(input.modelId.value)
    val systemMsg = obj("role" -> str("system"), "content" -> str(input.system))
    val rendered = renderMessages(input.messages)

    // DeepSeek mirrors the OpenAI chat-completions wire format,
    // including its strict-mode shape (`strict: true` inside the
    // `function` object). Strict mode enables grammar-constrained
    // decoding so the model can't produce malformed args. The
    // schema is rewritten by `StrictSchema` to satisfy the dialect's
    // requirements (every property required, optionals nullable,
    // no `pattern` / `format` / numeric-bound keywords).
    val toolsArr = input.tools.map { t =>
      val s = t.schema
      obj(
        "type" -> str("function"),
        "function" -> obj(
          "name"        -> str(s.name.value),
          "description" -> str(renderDescription(t, input.currentMode)),
          "strict"      -> bool(true),
          "parameters"  -> StrictSchema.forDeepSeek(DefinitionToSchema(s.input))
        )
      )
    }

    val baseFields = Vector[(String, Json)](
      "model" -> str(modelName),
      "messages" -> arr((Vector(systemMsg) ++ rendered)*),
      "stream" -> bool(true),
      "stream_options" -> obj("include_usage" -> bool(true))
    )
    val toolFields: Vector[(String, Json)] = input.toolChoice match {
      case ToolChoice.None => Vector.empty
      case ToolChoice.Auto =>
        Vector("tools" -> arr(toolsArr*), "tool_choice" -> str("auto"))
      case ToolChoice.Required =>
        Vector("tools" -> arr(toolsArr*), "tool_choice" -> str("required"))
    }
    val gen = input.generationSettings
    // DeepSeek accepts OpenAI-style `reasoning_effort` on the reasoner
    // model; chat models ignore it. Forward when the caller sets effort.
    val reasoningField: Vector[(String, Json)] = gen.effort match {
      case None    => Vector.empty
      case Some(e) => Vector("reasoning_effort" -> str(Effort.openAIEffortLevel(e)))
    }
    val genFields: Vector[(String, Json)] =
      gen.temperature.toVector.map("temperature" -> num(_)) ++
        gen.maxOutputTokens.toVector.map("max_tokens" -> num(_)) ++
        gen.topP.toVector.map("top_p" -> num(_)) ++
        (if (gen.stopSequences.nonEmpty) Vector("stop" -> arr(gen.stopSequences.map(str)*)) else Vector.empty)

    obj((baseFields ++ toolFields ++ reasoningField ++ genFields)*)
  }

  private def renderMessages(messages: Vector[ProviderMessage]): Vector[Json] =
    messages.map {
      case ProviderMessage.System(content) =>
        obj("role" -> str("system"), "content" -> str(content))
      case ProviderMessage.User(blocks) =>
        val text = blocks.iterator.collect { case MessageContent.Text(t) => t }.mkString("\n")
        obj("role" -> str("user"), "content" -> str(text))
      case ProviderMessage.Assistant(content, toolCalls) =>
        if (toolCalls.isEmpty) obj("role" -> str("assistant"), "content" -> str(content))
        else obj(
          "role" -> str("assistant"),
          "tool_calls" -> arr(toolCalls.map { tc =>
            obj(
              "id" -> str(tc.id),
              "type" -> str("function"),
              "function" -> obj("name" -> str(tc.name), "arguments" -> str(tc.argsJson))
            )
          }*)
        )
      case ProviderMessage.ToolResult(toolCallId, content) =>
        obj("role" -> str("tool"), "tool_call_id" -> str(toolCallId), "content" -> str(content))
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

  private def parseLine(line: String, state: StreamState): Vector[ProviderEvent] =
    SSELineParser.parse(line) match {
      case SSELine.Data(json) => parseChunk(json, state)
      case SSELine.Done       => state.flushDone()
      case SSELine.MalformedData(_, r) => Vector(ProviderEvent.Error(s"parse: $r"))
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
      delta.get("tool_calls").foreach { tcs =>
        tcs.asVector.foreach { tc =>
          val index = tc.get("index").map(_.asInt).getOrElse(0)
          val idOpt = tc.get("id").flatMap(j => if (j.isNull) None else Some(j.asString))
          val nameOpt = tc.get("function").flatMap(_.get("name")).flatMap(j => if (j.isNull) None else Some(j.asString))
          (idOpt, nameOpt) match {
            case (Some(id), Some(n)) => events ++= state.acc.start(index, CallId(id), n)
            case _ => ()
          }
          tc.get("function").flatMap(_.get("arguments"))
            .flatMap(j => if (j.isNull) None else Some(j.asString))
            .foreach(a => events ++= state.acc.appendArgs(index, a))
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
          case _                => StopReason.Complete
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
      promptTokens = json.get("prompt_tokens").map(_.asInt).getOrElse(0),
      completionTokens = json.get("completion_tokens").map(_.asInt).getOrElse(0),
      totalTokens = json.get("total_tokens").map(_.asInt).getOrElse(0)
    )

  final private class StreamState(val acc: ToolCallAccumulator) {
    var pendingDone: Option[StopReason] = None

    def flushDone(): Vector[ProviderEvent] = pendingDone match {
      case Some(sr) => pendingDone = None; Vector(ProviderEvent.Done(sr))
      case None     => Vector.empty
    }
  }
}

object DeepSeekProvider {
  def create(sigil: Sigil, apiKey: String, baseUrl: URL = url"https://api.deepseek.com"): Task[DeepSeekProvider] =
    Task.pure(DeepSeekProvider(apiKey, sigil, baseUrl))
}
