package sigil.provider.digitalocean

import fabric.*
import fabric.io.JsonFormatter
import rapid.{Stream, Task}
import sigil.Sigil
import sigil.provider.*
import sigil.provider.sse.{SSELine, SSELineParser}
import sigil.tool.{DefinitionToSchema, Tool, ToolInput}
import sigil.tool.ToolInput.given
import spice.http.{HttpMethod, HttpRequest}
import spice.http.client.HttpClient
import spice.http.content.StringContent
import spice.net.*

import scala.concurrent.duration.*

/**
 * DigitalOcean Inference provider — OpenAI-compatible chat-completions
 * at `https://inference.do-ai.run/v1/chat/completions`. Hosted models
 * (kimi-k2.5, llama, mistral, …) all speak the same wire shape.
 *
 * Wire shape mirrors [[sigil.provider.deepseek.DeepSeekProvider]] but
 * conservative on optional features the hosted-model surface doesn't
 * universally support: no strict-mode tool schemas (DO documents
 * OpenAI compatibility, not the strict-mode extension), no
 * `reasoning_effort` / `reasoning_content` fields. Schemas still go
 * through `StrictSchema.stripUnsupportedKeys` so unknown keywords
 * (`pattern`, `format`, numeric bounds) don't reach the validator.
 * Apps targeting a specific model with richer feature support
 * subclass and override the bits they need.
 */
case class DigitalOceanProvider(apiKey: String,
                                sigilRef: Sigil,
                                baseUrl: URL = url"https://inference.do-ai.run",
                                /** Per-read idle timeout for the SSE stream. Fires
                                  * only when no bytes arrive for the duration —
                                  * slow-but-working streams keep going. */
                                tokenIdleTimeout: FiniteDuration = 120.seconds) extends Provider {
  override def `type`: ProviderType = ProviderType.DigitalOcean
  override val providerKey: String = DigitalOcean.Provider
  override protected def sigil: Sigil = sigilRef

  override def call(input: ProviderCall): Stream[ProviderEvent] = {
    val state = new StreamState(new ToolCallAccumulator(input.tools))
    Stream.force(
      for {
        raw         <- httpRequestFor(input)
        intercepted <- sigilRef.wireInterceptor.before(raw)
        lines       <- HttpClient.modify(_ => intercepted).noFailOnHttpStatus.timeout(tokenIdleTimeout).streamLines()
      } yield {
        _root_.sigil.provider.debug.StreamWireInterceptor.attach(
          lines, sigilRef.wireInterceptor, intercepted
        ) { line =>
          Stream.emits(parseLine(line, state))
        }
      }
    )
  }

  override def httpRequestFor(input: ProviderCall): Task[HttpRequest] = Task {
    val bodyStr = JsonFormatter.Compact(buildBody(input))
    HttpRequest(
      method = HttpMethod.Post,
      url = baseUrl.withPath("/v1/chat/completions"),
      content = Some(StringContent(bodyStr, ContentType.`application/json`))
    ).withHeader("Authorization", s"Bearer $apiKey")
  }

  private def buildBody(input: ProviderCall): Json = {
    val modelName = DigitalOcean.stripProviderPrefix(input.modelId.value)
    val systemMsg = obj("role" -> str("system"), "content" -> str(input.system))
    val rendered = renderMessages(input.messages)

    // OpenAI chat-completions tool shape, without `strict: true`.
    // DigitalOcean's docs promise OpenAI compatibility for the core
    // surface; strict-mode is an OpenAI extension a hosted open
    // model may or may not honor. Schemas still get cleaned of
    // unsupported keywords for safety; `ToolInputValidator` re-
    // checks decoded args after the model returns.
    val toolsArr = input.tools.map { t =>
      val s = t.schema
      obj(
        "type" -> str("function"),
        "function" -> obj(
          "name"        -> str(s.name.value),
          "description" -> str(renderDescription(t, input.currentMode)),
          "parameters"  -> StrictSchema.stripUnsupportedKeys(DefinitionToSchema(s.input))
        )
      )
    }

    val baseFields = Vector[(String, Json)](
      "model"          -> str(modelName),
      "messages"       -> arr((Vector(systemMsg) ++ rendered)*),
      "stream"         -> bool(true),
      "stream_options" -> obj("include_usage" -> bool(true))
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
    val genFields: Vector[(String, Json)] =
      gen.temperature.toVector.map("temperature" -> num(_)) ++
        gen.maxOutputTokens.toVector.map("max_tokens" -> num(_)) ++
        gen.topP.toVector.map("top_p" -> num(_)) ++
        (if (gen.stopSequences.nonEmpty) Vector("stop" -> arr(gen.stopSequences.map(str)*)) else Vector.empty)

    obj((baseFields ++ toolFields ++ genFields)*)
  }

  private def renderMessages(messages: Vector[ProviderMessage]): Vector[Json] =
    messages.flatMap {
      case ProviderMessage.System(content) =>
        Vector(obj("role" -> str("system"), "content" -> str(content)))
      case ProviderMessage.User(blocks) =>
        // DigitalOcean Inference VLMs (Nemotron Nano 12B v2 VL,
        // Kimi K2.5/K2.6, …) accept the OpenAI content-array shape:
        // an array of `{type: "text", text: ...}` and
        // `{type: "image_url", image_url: {url: ...}}` parts. The
        // URL is either an `https://` link or a `data:image/<png|jpg|jpeg|webp>;base64,…`
        // data URI. Text-only models accept the same shape and just
        // ignore the image parts, so we always emit the array form
        // when an image is present; pure-text messages stay on the
        // simpler string form for parity with non-vision deployments.
        val hasImage = blocks.exists(_.isInstanceOf[MessageContent.Image])
        if (!hasImage) {
          val text = blocks.collect { case MessageContent.Text(t) => t }.mkString("\n")
          Vector(obj("role" -> str("user"), "content" -> str(text)))
        } else {
          val parts = blocks.map {
            case MessageContent.Text(t) =>
              obj("type" -> str("text"), "text" -> str(t))
            case MessageContent.Image(u, _) =>
              obj("type" -> str("image_url"), "image_url" -> obj("url" -> str(u.toString)))
          }
          Vector(obj("role" -> str("user"), "content" -> arr(parts*)))
        }
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
        // Provider-internal reasoning state (currently OpenAI-only on
        // the wire). No DO chat-completions slot; drop silently.
        Vector.empty
    }

  private def renderDescription(tool: Tool, mode: Mode): String = {
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
      // Kimi K2.5 (when `/think` is in the system prompt) and Kimi
      // K2.6 (always) stream extended reasoning in a separate
      // `reasoning_content` delta field. Surface it as a ThinkingDelta
      // so UIs that visualise CoT render it distinctly from final
      // answer text; non-thinking models simply never emit the field.
      delta.get("reasoning_content").foreach { c =>
        if (!c.isNull) {
          val text = c.asString
          if (text.nonEmpty) events += ProviderEvent.ThinkingDelta(text)
        }
      }
      delta.get("tool_calls").foreach { tcs =>
        tcs.asVector.foreach { tc =>
          val index   = tc.get("index").map(_.asInt).getOrElse(0)
          val idOpt   = tc.get("id").flatMap(j => if (j.isNull) None else Some(j.asString))
          val nameOpt = tc.get("function").flatMap(_.get("name")).flatMap(j => if (j.isNull) None else Some(j.asString))
          (idOpt, nameOpt) match {
            case (Some(id), Some(n)) => events ++= state.acc.start(index, CallId(id), n)
            case _                   => ()
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
      promptTokens     = json.get("prompt_tokens").map(_.asInt).getOrElse(0),
      completionTokens = json.get("completion_tokens").map(_.asInt).getOrElse(0),
      totalTokens      = json.get("total_tokens").map(_.asInt).getOrElse(0)
    )

  final private class StreamState(val acc: ToolCallAccumulator) {
    var pendingDone: Option[StopReason] = None

    def flushDone(): Vector[ProviderEvent] = pendingDone match {
      case Some(sr) => pendingDone = None; Vector(ProviderEvent.Done(sr))
      case None     => Vector.empty
    }
  }
}

object DigitalOceanProvider {
  def create(sigil: Sigil, apiKey: String, baseUrl: URL = url"https://inference.do-ai.run"): Task[DigitalOceanProvider] =
    Task.pure(DigitalOceanProvider(apiKey, sigil, baseUrl))
}
