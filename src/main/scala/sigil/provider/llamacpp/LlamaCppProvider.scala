package sigil.provider.llamacpp

import fabric.*
import fabric.io.{JsonFormatter, JsonParser}
import fabric.rw.*
import rapid.{Stream, Task}
import sigil.db.Model
import sigil.event.{Event, EventVisibility, Message, ModeChange, TitleChange, ToolInvoke, ToolResults}
import sigil.provider.*
import sigil.tool.{DefinitionToSchema, ToolInput, ToolSchema}
import sigil.tool.model.ResponseContent
import sigil.tool.ToolInput.given
import spice.http.{HttpMethod, HttpRequest}
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
  override def requestConverter(request: ProviderRequest): HttpRequest = {
    val modelName = stripProviderPrefix(request.modelId.value)
    val bodyStr = JsonFormatter.Compact(buildBody(modelName, request))
    HttpRequest(
      method = HttpMethod.Post,
      url = url.withPath("/v1/chat/completions"),
      content = Some(StringContent(bodyStr, ContentType.`application/json`))
    )
  }

  override def apply(request: ProviderRequest): Stream[ProviderEvent] = {
    val httpRequest = requestConverter(request)
    val state = new StreamState(new ToolCallAccumulator(request.tools))

    Stream.force(
      HttpClient.modify(_ => httpRequest)
        .noFailOnHttpStatus
        .streamLines()
        .map(lines => lines.flatMap(line => Stream.emits(parseLine(line, state))))
    )
  }

  private def buildBody(modelName: String, request: ProviderRequest): Json = {
    val agentId = request.chain.lastOption
    val systemMsg = obj("role" -> str("system"), "content" -> str(buildSystemContent(request)))
    val messages = renderHistory(request.context.events, agentId)

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
      // Qwen 3.x: suppress <think> blocks. The content-pattern enforces the
      // multipart header, but thinking still shifts tool selection (e.g.
      // clarifying `respond` instead of `change_mode`). Revisit once thinking
      // is driven per-Mode.
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

  /**
   * Compose the system message body from every contextually relevant
   * field on the request. Each section is omitted when its source is
   * empty so the wire payload stays compact.
   *
   *   - mode + description (always)
   *   - instructions (always)
   *   - critical memories (`ConversationContext.criticalMemories`)
   *   - earlier-conversation summaries (`ConversationContext.summaries`)
   *   - active memories (`ConversationContext.memories`)
   *   - referenced information catalog (`ConversationContext.information`)
   *   - active skills aggregated across chain participants
   *     (`ConversationContext.aggregatedSkills(chain)`)
   *   - recent / suggested tools per chain participant
   *   - app-supplied extra context, both conversation-wide
   *     (`ConversationContext.extraContext`) and per-participant
   *     (`ParticipantContext.extraContext`)
   *
   * Every Model-visible field on `ConversationContext` /
   * `ParticipantContext` MUST appear here. The companion
   * [[spec.LlamaCppRequestCoverageSpec]] is the regression guard.
   */
  private def buildSystemContent(request: ProviderRequest): String = {
    val ctx = request.context
    val chain = request.chain
    val sb = new StringBuilder

    sb.append(s"Current mode: ${request.currentMode} — ${request.currentMode.description}\n")

    val instr = request.instructions.render
    if (instr.nonEmpty) sb.append("\n").append(instr).append("\n")

    if (ctx.criticalMemories.nonEmpty) {
      sb.append("\n== Critical directives ==\n")
      ctx.criticalMemories.foreach(m => sb.append(s"- ${m.key}: ${m.fact}\n"))
    }

    if (ctx.summaries.nonEmpty) {
      sb.append("\n== Earlier in this conversation ==\n")
      ctx.summaries.foreach(s => sb.append(s.text).append("\n"))
    }

    if (ctx.memories.nonEmpty) {
      sb.append("\n== Memories ==\n")
      ctx.memories.foreach(m => sb.append(s"- ${m.key}: ${m.fact}\n"))
    }

    if (ctx.information.nonEmpty) {
      sb.append("\n== Referenced content (look up by id) ==\n")
      ctx.information.foreach(i => sb.append(s"- ${i.id.value} [${i.informationType.name}]: ${i.summary}\n"))
    }

    val skills = ctx.aggregatedSkills(chain)
    if (skills.nonEmpty) {
      sb.append("\n== Active skills ==\n")
      skills.foreach { s =>
        sb.append(s"- ${s.name}\n")
        if (s.content.nonEmpty) sb.append(s.content).append("\n")
      }
    }

    val recentTools = chain.flatMap(id => ctx.forParticipant(id).recentTools).distinct
    if (recentTools.nonEmpty) {
      sb.append("\n== Recently used tools ==\n")
      recentTools.foreach(t => sb.append(s"- $t\n"))
    }

    val suggestedTools = chain.flatMap(id => ctx.forParticipant(id).suggestedTools).distinct
    if (suggestedTools.nonEmpty) {
      sb.append("\n== Suggested tools ==\n")
      suggestedTools.foreach(t => sb.append(s"- $t\n"))
    }

    if (ctx.extraContext.nonEmpty) {
      sb.append("\n== Conversation context ==\n")
      ctx.extraContext.foreach { case (k, v) => sb.append(s"- ${k.value}: $v\n") }
    }

    val perParticipantExtras = chain.flatMap(id => ctx.forParticipant(id).extraContext.map(id -> _))
    if (perParticipantExtras.nonEmpty) {
      sb.append("\n== Participant context ==\n")
      perParticipantExtras.foreach { case (pid, (k, v)) =>
        sb.append(s"- ${pid.value} ${k.value}: $v\n")
      }
    }

    sb.toString
  }

  /**
   * Render the conversation event log into the OpenAI/llama.cpp message
   * format the chat-completions endpoint expects, so the LLM has full
   * memory of its own prior actions across iterations.
   *
   * Only Events whose `visibility` includes `EventVisibility.Model` are
   * candidates for inclusion — events the framework has explicitly marked
   * as UI-only (e.g. `TitleChange`, `AgentState` lifecycle markers) are
   * filtered out before any rendering happens.
   *
   * Mapping rules for the surviving events:
   *   - `Message` from the agent itself → `{role: "assistant", content}`
   *   - `Message` from anyone else      → `{role: "user", content}`
   *   - `ToolInvoke` from the agent for any tool *other than* `respond` →
   *     `{role: "assistant", tool_calls: [{id, function: {name, arguments}}]}`
   *     where `id` is the ToolInvoke's `_id` and `arguments` is the input
   *     serialized through the `ToolInput` poly.
   *   - `ToolInvoke` for `respond` is skipped — the following `Message` IS
   *     the response, and emitting both yields a tool_call without a
   *     matching tool_result, which models handle poorly.
   *   - The next `ModeChange` / `ToolResults` after a pending `ToolInvoke`
   *     is paired with it as `{role: "tool", tool_call_id, content}`.
   *     Without pairing the model sees a dangling assistant tool_call and
   *     may repeat it.
   */
  private[llamacpp] def renderHistory(events: Vector[Event], agentId: Option[sigil.participant.ParticipantId]): Vector[Json] = {
    val out = Vector.newBuilder[Json]
    var pendingToolCall: Option[(String, String)] = None  // (tool_call_id, tool name)

    def flushAsContent(content: String): Unit = {
      pendingToolCall.foreach { case (callId, _) =>
        out += obj(
          "role" -> str("tool"),
          "tool_call_id" -> str(callId),
          "content" -> str(content)
        )
      }
      pendingToolCall = None
    }

    events.filter(_.visibility.contains(EventVisibility.Model)).foreach {
      case m: Message =>
        val isAssistant = agentId.contains(m.participantId)
        val text = renderMessageText(m)
        if (isAssistant && pendingToolCall.isDefined) {
          // Streaming respond path: the agent's Message is the result of
          // its own preceding ToolInvoke (the `respond` ToolInvoke was
          // skipped, so this only fires for unusual non-respond streaming
          // tools). Pair with pending and also emit as assistant.
          flushAsContent(text)
          out += obj("role" -> str("assistant"), "content" -> str(text))
        } else if (isAssistant) {
          out += obj("role" -> str("assistant"), "content" -> str(text))
        } else {
          out += obj("role" -> str("user"), "content" -> str(text))
        }
      case ti: ToolInvoke if agentId.contains(ti.participantId) =>
        if (ti.toolName == "respond") {
          // Skip — the following Message is the actual response.
        } else {
          val callId = ti._id.value
          val argsJson = ti.input
            .map(i => JsonFormatter.Compact(stripPolyDiscriminator(summon[RW[ToolInput]].read(i))))
            .getOrElse("{}")
          out += obj(
            "role" -> str("assistant"),
            "tool_calls" -> arr(obj(
              "id" -> str(callId),
              "type" -> str("function"),
              "function" -> obj(
                "name" -> str(ti.toolName),
                "arguments" -> str(argsJson)
              )
            ))
          )
          pendingToolCall = Some((callId, ti.toolName))
        }
      case _: ToolInvoke =>
        // ToolInvoke from someone else — skip
      case mc: ModeChange =>
        flushAsContent(s"Mode changed to ${mc.mode}.")
      case tc: TitleChange =>
        flushAsContent(s"Title changed to: ${tc.title}")
      case tr: ToolResults =>
        val rendered =
          if (tr.schemas.isEmpty) "No matches."
          else tr.schemas.map(s => s"- ${s.name}: ${s.description}").mkString("\n")
        flushAsContent(rendered)
      case other =>
        // Model-visible Event subtype not yet handled by this provider.
        // Fail loud rather than silently dropping — every Model-visible
        // event MUST appear in the wire output (regression invariant).
        throw new RuntimeException(
          s"LlamaCppProvider.renderHistory: Model-visible Event ${other.getClass.getSimpleName} " +
            s"has no rendering rule. Add a case to renderHistory or remove EventVisibility.Model from the event."
        )
    }

    // Dangling tool_call without a result — fabricate a minimal "ok" so the
    // history isn't malformed. Defensive: shouldn't happen in correct
    // dispatcher operation.
    pendingToolCall.foreach { case (callId, _) =>
      out += obj(
        "role" -> str("tool"),
        "tool_call_id" -> str(callId),
        "content" -> str("(no result recorded)")
      )
    }

    out.result()
  }

  private def renderMessageText(m: Message): String =
    m.content
      .map {
        case ResponseContent.Text(t) => t
        case ResponseContent.Markdown(t) => t
        case ResponseContent.Code(c, lang) => s"```${lang.getOrElse("")}\n$c\n```"
        case other => other.toString
      }
      .mkString("\n")

  /** `ToolInput`'s poly RW tags each concrete input with a `"type"`
    * discriminator field. That's wire-correct for sigil but foreign to
    * OpenAI/llama.cpp tool_call `arguments` — they expect pure
    * parameter-schema JSON. Strip it so the chat template doesn't choke
    * on unexpected fields when echoing the tool_call back through the
    * conversation on a follow-up turn. */
  private def stripPolyDiscriminator(json: Json): Json = json match {
    case o: Obj => Obj(o.value - "type")
    case other  => other
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
