package sigil.provider.llamacpp

import fabric.*
import fabric.io.{JsonFormatter, JsonParser}
import fabric.rw.*
import rapid.{Stream, Task}
import sigil.Sigil
import sigil.conversation.{ContextFrame, ContextMemory, ContextSummary}
import sigil.db.Model
import sigil.provider.*
import sigil.tool.{DefinitionToSchema, ToolInput, ToolSchema}
import sigil.tool.ToolInput.given
import spice.http.{HttpMethod, HttpRequest}
import spice.http.client.HttpClient
import spice.http.content.StringContent
import spice.net.*

case class LlamaCppProvider(url: URL, models: List[Model], sigilRef: Sigil) extends Provider {
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
  override def requestConverter(request: ProviderRequest): Task[HttpRequest] = {
    val modelName = stripProviderPrefix(request.modelId.value)
    resolveReferences(request).map { resolved =>
      val bodyStr = JsonFormatter.Compact(buildBody(modelName, request, resolved))
      HttpRequest(
        method = HttpMethod.Post,
        url = url.withPath("/v1/chat/completions"),
        content = Some(StringContent(bodyStr, ContentType.`application/json`))
      )
    }
  }

  override def apply(request: ProviderRequest): Stream[ProviderEvent] = {
    val state = new StreamState(new ToolCallAccumulator(request.tools))

    Stream.force(
      requestConverter(request).map { httpRequest =>
        HttpClient.modify(_ => httpRequest)
          .noFailOnHttpStatus
          .streamLines()
          .map(lines => lines.flatMap(line => Stream.emits(parseLine(line, state))))
      }.flatMap(identity)
    )
  }

  /**
   * Resolve the ids in `TurnInput.criticalMemories`, `.memories`, and
   * `.summaries` to full records by looking each one up in the
   * corresponding store. Ids that don't resolve (deleted, stale) are
   * dropped silently — the curator keeps the referenced set consistent.
   */
  private def resolveReferences(request: ProviderRequest): Task[ResolvedReferences] = {
    val turn = request.turnInput
    for {
      crit <- Task.sequence(turn.criticalMemories.toList.map(id => sigilRef.withDB(_.memories.transaction(_.get(id)))))
      regular <- Task.sequence(turn.memories.toList.map(id => sigilRef.withDB(_.memories.transaction(_.get(id)))))
      summaries <- Task.sequence(turn.summaries.toList.map(id => sigilRef.withDB(_.summaries.transaction(_.get(id)))))
    } yield ResolvedReferences(
      criticalMemories = crit.flatten.toVector,
      memories = regular.flatten.toVector,
      summaries = summaries.flatten.toVector
    )
  }

  private def buildBody(modelName: String, request: ProviderRequest, resolved: ResolvedReferences): Json = {
    val agentId = request.chain.lastOption
    val systemMsg = obj("role" -> str("system"), "content" -> str(buildSystemContent(request, resolved)))
    val messages = renderFrames(request.turnInput.conversationView.frames, agentId)

    val toolsArr = request.tools.map { t =>
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
    val gen = request.generationSettings
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

  /**
   * Compose the system message body from every contextually relevant
   * field on the request. Each section is omitted when its source is
   * empty so the wire payload stays compact.
   *
   *   - mode + description (always)
   *   - instructions (always)
   *   - critical memories (`TurnInput.criticalMemories`)
   *   - earlier-conversation summaries (`TurnInput.summaries`)
   *   - active memories (`TurnInput.memories`)
   *   - referenced information catalog (`TurnInput.information`)
   *   - active skills aggregated across chain participants (from
   *     `ConversationView.aggregatedSkills(chain)`)
   *   - recent / suggested tools per chain participant (from the
   *     view's per-participant projections)
   *   - app-supplied extra context, both conversation-wide
   *     (`TurnInput.extraContext`) and per-participant
   *     (`ParticipantProjection.extraContext`)
   *
   * Every Model-visible field on `TurnInput` / `ConversationView` MUST
   * appear here. The companion [[spec.LlamaCppRequestCoverageSpec]] is
   * the regression guard.
   */
  private def buildSystemContent(request: ProviderRequest, resolved: ResolvedReferences): String = {
    val turn = request.turnInput
    val view = turn.conversationView
    val chain = request.chain
    val sb = new StringBuilder

    sb.append(s"Current mode: ${request.currentMode} — ${request.currentMode.description}\n")
    sb.append(s"Current topic: \"${request.currentTopicLabel}\"\n")

    val instr = request.instructions.render
    if (instr.nonEmpty) sb.append("\n").append(instr).append("\n")

    if (resolved.criticalMemories.nonEmpty) {
      sb.append("\n== Critical directives ==\n")
      resolved.criticalMemories.foreach(m => sb.append(s"- ${m.fact}\n"))
    }

    if (resolved.summaries.nonEmpty) {
      sb.append("\n== Earlier in this conversation ==\n")
      resolved.summaries.foreach(s => sb.append(s.text).append("\n"))
    }

    if (resolved.memories.nonEmpty) {
      sb.append("\n== Memories ==\n")
      resolved.memories.foreach(m => sb.append(s"- ${m.fact}\n"))
    }

    if (turn.information.nonEmpty) {
      sb.append("\n== Referenced content (look up by id) ==\n")
      turn.information.foreach(i => sb.append(s"- ${i.id.value} [${i.informationType.name}]: ${i.summary}\n"))
    }

    val skills = view.aggregatedSkills(chain)
    if (skills.nonEmpty) {
      sb.append("\n== Active skills ==\n")
      skills.foreach { s =>
        sb.append(s"- ${s.name}\n")
        if (s.content.nonEmpty) sb.append(s.content).append("\n")
      }
    }

    val recentTools = chain.flatMap(id => view.projectionFor(id).recentTools).distinct
    if (recentTools.nonEmpty) {
      sb.append("\n== Recently used tools ==\n")
      recentTools.foreach(t => sb.append(s"- $t\n"))
    }

    val suggestedTools = chain.flatMap(id => view.projectionFor(id).suggestedTools).distinct
    if (suggestedTools.nonEmpty) {
      sb.append("\n== Suggested tools ==\n")
      suggestedTools.foreach(t => sb.append(s"- $t\n"))
    }

    if (turn.extraContext.nonEmpty) {
      sb.append("\n== Conversation context ==\n")
      turn.extraContext.foreach { case (k, v) => sb.append(s"- ${k.value}: $v\n") }
    }

    val perParticipantExtras = chain.flatMap(id => view.projectionFor(id).extraContext.map(id -> _))
    if (perParticipantExtras.nonEmpty) {
      sb.append("\n== Participant context ==\n")
      perParticipantExtras.foreach { case (pid, (k, v)) =>
        sb.append(s"- ${pid.value} ${k.value}: $v\n")
      }
    }

    sb.toString
  }

  /**
   * Render the conversation view's [[ContextFrame]]s into the
   * OpenAI/llama.cpp chat-completions message format.
   *
   * Mapping rules:
   *   - `ContextFrame.Text` from the agent itself → `{role: "assistant", content}`
   *   - `ContextFrame.Text` from anyone else      → `{role: "user", content}`
   *   - `ContextFrame.ToolCall` from the agent for any tool *other than*
   *     `respond` → `{role: "assistant", tool_calls: [...]}`. The `respond`
   *     tool's call is filtered at render time because the following
   *     `Text` frame IS the response — emitting both yields a tool_call
   *     without a matching tool_result, which models handle poorly.
   *   - `ContextFrame.ToolResult` → `{role: "tool", tool_call_id, content}`,
   *     paired by `callId` to a prior `ToolCall`.
   *   - `ContextFrame.System` → `{role: "tool", tool_call_id, content}` when
   *     paired with a pending tool call; otherwise `{role: "system", content}`.
   *
   * Only model-visible events become frames in the first place (see
   * [[sigil.conversation.FrameBuilder]]), so UI-only history never reaches
   * this renderer.
   */
  private[llamacpp] def renderFrames(frames: Vector[ContextFrame],
                                     agentId: Option[_root_.sigil.participant.ParticipantId]): Vector[Json] = {
    val out = Vector.newBuilder[Json]
    var pendingToolCall: Option[String] = None

    def flushAsToolResultOrSystem(content: String): Unit = pendingToolCall match {
      case Some(callId) =>
        out += obj(
          "role" -> str("tool"),
          "tool_call_id" -> str(callId),
          "content" -> str(content)
        )
        pendingToolCall = None
      case None =>
        out += obj(
          "role" -> str("system"),
          "content" -> str(content)
        )
    }

    frames.foreach {
      case ContextFrame.Text(content, participantId, _) =>
        val isAssistant = agentId.contains(participantId)
        if (isAssistant && pendingToolCall.isDefined) {
          flushAsToolResultOrSystem(content)
          out += obj("role" -> str("assistant"), "content" -> str(content))
        } else if (isAssistant) {
          out += obj("role" -> str("assistant"), "content" -> str(content))
        } else {
          out += obj("role" -> str("user"), "content" -> str(content))
        }

      case ContextFrame.ToolCall(toolName, _, _, participantId, _)
        if toolName == sigil.tool.core.RespondTool.schema.name && agentId.contains(participantId) =>
      // Skip — the following Text frame is the actual response.

      case ContextFrame.ToolCall(toolName, argsJson, callId, participantId, _) if agentId.contains(participantId) =>
        out += obj(
          "role" -> str("assistant"),
          "tool_calls" -> arr(obj(
            "id" -> str(callId.value),
            "type" -> str("function"),
            "function" -> obj(
              "name" -> str(toolName.value),
              "arguments" -> str(argsJson)
            )
          ))
        )
        pendingToolCall = Some(callId.value)

      case _: ContextFrame.ToolCall =>
      // ToolCall from someone else — skip (not rendered as a tool call for this agent).

      case ContextFrame.ToolResult(callId, content, _) =>
        out += obj(
          "role" -> str("tool"),
          "tool_call_id" -> str(callId.value),
          "content" -> str(content)
        )
        if (pendingToolCall.contains(callId.value)) pendingToolCall = None

      case ContextFrame.System(content, _) =>
        flushAsToolResultOrSystem(content)
    }

    // Dangling tool_call without a result — defensive fallback.
    pendingToolCall.foreach { callId =>
      out += obj(
        "role" -> str("tool"),
        "tool_call_id" -> str(callId),
        "content" -> str("(no result recorded)")
      )
    }

    out.result()
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
      val rendered = schema.examples.map { e =>
        // Render example inputs as JSON (stripped of the ToolInput poly
        // discriminator) so the model sees the SAME structural shape its
        // own tool-call arguments will take — a JSON object with typed
        // fields. Case-class `toString` would render `RespondInput(...)`
        // in constructor order, which the model can't distinguish from
        // a single opaque string value.
        val json = JsonFormatter.Compact(stripPolyDiscriminator(summon[RW[ToolInput]].read(e.input)))
        s"- ${e.description}: $json"
      }.mkString("\n")
      s"${schema.description}\n\nExamples:\n$rendered"
    }

  /**
   * Strip the `ToolInput` poly discriminator from a serialized example.
   * Frames already carry the clean form; examples rendered in tool
   * descriptions do the same so the model sees pure parameter-schema
   * JSON, matching what its own tool_call arguments must produce.
   */
  private def stripPolyDiscriminator(json: Json): Json = json match {
    case o: Obj => Obj(o.value - "type")
    case other => other
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
