package sigil.provider.openai

import fabric.*
import fabric.io.JsonFormatter
import rapid.{Stream, Task}
import sigil.Sigil
import sigil.db.Model
import sigil.provider.*
import sigil.provider.sse.{SSELine, SSELineParser}
import sigil.tokenize.{JtokkitTokenizer, Tokenizer}
import sigil.tool.{DefinitionToSchema, Tool, ToolInput, ToolSchema}
import sigil.tool.ToolInput.given
import sigil.provider.debug.StreamWireInterceptor
import spice.http.{HttpMethod, HttpRequest, HttpResponse, HttpStatus}
import spice.http.client.HttpClient
import spice.http.content.StringContent
import spice.net.*

import scala.concurrent.duration.*
import scala.util.Success

/**
 * OpenAI provider targeting the Responses API (`/v1/responses`) — the
 * only OpenAI surface that supports GPT-5.x natively and all built-in
 * tools (web_search, image_generation, file_search, code_interpreter,
 * computer_use).
 *
 * Request translation happens in [[call]] / [[httpRequestFor]] on the
 * [[Provider]] base trait's [[ProviderCall]]. Responses-specific
 * concerns:
 *
 *   - System prompts are top-level `instructions` rather than a
 *     system-role message in the input array.
 *   - Input items are heterogeneous: text blocks, image blocks, tool
 *     results, all addressed uniformly.
 *   - Output items stream via `response.*.delta` / `response.*.done`
 *     event names, with content in different items per type.
 *   - Built-in tools appear as top-level `{type: <built_in_tool>}`
 *     entries in the `tools` array alongside custom functions.
 *
 * Tool-call streaming reuses the framework's
 * [[sigil.provider.ToolCallAccumulator]]; output_text and image
 * events feed through directly as [[ProviderEvent.ContentBlockStart]]
 * / [[ProviderEvent.ImageGeneration*]].
 */
case class OpenAIProvider(apiKey: String,
                          sigilRef: Sigil,
                          baseUrl: URL = url"https://api.openai.com",
                          streamTimeout: FiniteDuration = 120.seconds) extends Provider {
  override def `type`: ProviderType = ProviderType.OpenAI
  override val providerKey: String = OpenAI.Provider
  override protected def sigil: Sigil = sigilRef

  /** OpenAI Chat Completions / Responses use the cl100k_base /
    * o200k_base BPE tokenizer. cl100k is the safe default — accurate
    * for GPT-3.5 / GPT-4 / GPT-4o-mini, slightly conservative (~5%
    * over) for o-series. The pre-flight gate's job is to err
    * conservative anyway. */
  override def tokenizer: Tokenizer = JtokkitTokenizer.OpenAIChatGpt

  override def call(input: ProviderCall): Stream[ProviderEvent] = {
    val state = new StreamState(new ToolCallAccumulator(input.tools))
    Stream.force(
      for {
        raw         <- httpRequestFor(input)
        intercepted <- sigilRef.wireInterceptor.before(raw)
        lines       <- HttpClient.modify(_ => intercepted).noFailOnHttpStatus.timeout(streamTimeout).streamLines()
      } yield {
        // Bug #77 — enforce an overall stream-lifetime deadline at our
        // level. Spice's `.timeout(streamTimeout)` configures Netty's
        // per-poll timer, which keepalives from upstream (OpenAI's SSE
        // `: keepalive` lines, periodic empty newlines) keep resetting —
        // a stream that produces only keepalives never reaches netty's
        // idle timeout and hangs the agent loop indefinitely. rapid's
        // `Stream.timeout` checks a wall-clock deadline before every
        // pull, so accumulated time across keepalives DOES trip it.
        StreamWireInterceptor.attach(lines.timeout(streamTimeout), sigilRef.wireInterceptor, intercepted) { line =>
          Stream.emits(parseLine(line, state))
        }
      }
    )
  }

  override def httpRequestFor(input: ProviderCall): Task[HttpRequest] = Task {
    val (path, body) =
      if (isImageOnlyModel(input.modelId)) ("/v1/images/generations", buildImagesBody(input))
      else ("/v1/responses", buildBody(input))
    val bodyStr = JsonFormatter.Compact(body)
    HttpRequest(
      method = HttpMethod.Post,
      url = baseUrl.withPath(path),
      content = Some(StringContent(bodyStr, ContentType.`application/json`))
    ).withHeader("Authorization", s"Bearer $apiKey")
  }

  /** True when the configured Model record's outputModalities indicate an
    * image-only model (e.g. `gpt-image-2`) — these go to
    * `/v1/images/generations` with a different request shape and a
    * different streaming event grammar. */
  private def isImageOnlyModel(modelId: lightdb.id.Id[Model]): Boolean =
    models.find(_._id == modelId).exists { m =>
      val out = m.architecture.outputModalities.toSet
      out.contains("image") && !out.contains("text")
    }

  /** Body for `/v1/images/generations`. Streams partial previews via
    * `stream: true, partial_images: 2`; OpenAI emits
    * `image_generation.partial_image` events progressively followed by
    * `image_generation.completed`. */
  private def buildImagesBody(input: ProviderCall): Json = {
    val modelName = OpenAI.stripProviderPrefix(input.modelId.value)
    val prompt = extractImagePrompt(input)
    obj(
      "model" -> str(modelName),
      "prompt" -> str(prompt),
      "n" -> num(1),
      "stream" -> bool(true),
      "partial_images" -> num(2)
    )
  }

  /** Pull the prompt for a direct image-generation call. Concatenates
    * the system instructions (if any) with the most-recent user
    * message's text — the model takes a single string. Multi-turn
    * conversation history is not meaningful for image-only models. */
  private def extractImagePrompt(input: ProviderCall): String = {
    val systemPart = if (input.system.isEmpty) "" else input.system + "\n\n"
    val userText = input.messages.reverseIterator.collectFirst {
      case ProviderMessage.User(content) =>
        content.collect { case MessageContent.Text(t) => t }.mkString(" ")
    }.getOrElse("")
    (systemPart + userText).trim
  }

  // ---- request body construction ----

  private def buildBody(input: ProviderCall): Json = {
    val modelName = OpenAI.stripProviderPrefix(input.modelId.value)
    val inputItems = renderInput(input.messages)
    val toolsArr = renderTools(input)

    val baseFields = Vector[(String, Json)](
      "model" -> str(modelName),
      "input" -> arr(inputItems*),
      "stream" -> bool(true)
    )
    val instructionsField: Vector[(String, Json)] =
      if (input.system.isEmpty) Vector.empty
      else Vector("instructions" -> str(input.system))
    val toolFields: Vector[(String, Json)] = input.toolChoice match {
      case ToolChoice.None if toolsArr.isEmpty => Vector.empty
      case ToolChoice.None =>
        Vector("tools" -> arr(toolsArr*), "tool_choice" -> str("none"))
      case ToolChoice.Auto =>
        Vector("tools" -> arr(toolsArr*), "tool_choice" -> str("auto"))
      case ToolChoice.Required =>
        Vector("tools" -> arr(toolsArr*), "tool_choice" -> str("required"))
    }
    val gen = input.generationSettings
    val isReasoningModel = OpenAI.reasoningModelPrefixes.exists(modelName.startsWith)
    // Bug #62 — for reasoning-family models (gpt-5.x, o1, o3, o4),
    // ask OpenAI to populate the reasoning items it returns:
    //   - `reasoning.summary = "auto"` so the response carries a
    //     human-readable summary (which the framework persists +
    //     replays via [[ProviderMessage.Reasoning]] on subsequent
    //     turns).
    //   - `include = ["reasoning.encrypted_content"]` (added below)
    //     so o1/o3-style opaque CoT blobs come back populated and
    //     can be round-tripped verbatim.
    // Without these, the API returns `{id, type: "reasoning", summary: []}`
    // with no `encrypted_content`. Replaying a hollow item gives the
    // model no state to resume from and the next response is empty.
    val reasoningField: Vector[(String, Json)] = (gen.effort, isReasoningModel) match {
      case (Some(e), true) =>
        Vector("reasoning" -> obj(
          "effort"  -> str(Effort.openAIEffortLevel(e)),
          "summary" -> str("auto")
        ))
      case (Some(e), false) =>
        Vector("reasoning" -> obj("effort" -> str(Effort.openAIEffortLevel(e))))
      case (None, true) =>
        Vector("reasoning" -> obj("summary" -> str("auto")))
      case (None, false) =>
        Vector.empty
    }
    val includeField: Vector[(String, Json)] =
      if (isReasoningModel) Vector("include" -> arr(str("reasoning.encrypted_content")))
      else Vector.empty
    // The GPT-5 family + reasoning-only models (o1, o3, …) hard-reject
    // `temperature` / `top_p` (sampling locked to 1.0) and return
    // HTTP 400 when those fields are sent. Drive the filter from the
    // model's `supportedParameters` first — that's the upstream-
    // catalog-of-truth — and fall back to a known-prefix list when
    // the registry is cold (first boot before
    // `OpenRouter.refreshModels`, an offline session, etc.). Apps
    // can override [[sigil.Sigil.supportsParameter]] for stricter
    // policies.
    def supports(param: String): Boolean =
      sigilRef.supportsParameter(input.modelId, param) && !OpenAI.fixedSamplingPrefixes.exists(modelName.startsWith)
    val genFields: Vector[(String, Json)] =
      (if (supports("temperature")) gen.temperature.toVector.map("temperature" -> num(_)) else Vector.empty) ++
        gen.maxOutputTokens.toVector.map("max_output_tokens" -> num(_)) ++
        (if (supports("top_p")) gen.topP.toVector.map("top_p" -> num(_)) else Vector.empty) ++
        (if (gen.stopSequences.nonEmpty) Vector("stop" -> arr(gen.stopSequences.map(str)*)) else Vector.empty)

    obj((baseFields ++ instructionsField ++ toolFields ++ reasoningField ++ includeField ++ genFields)*)
  }

  /** Render framework-neutral [[ProviderMessage]]s into Responses API
    * input items. Each item carries a `role` + a `content` array of
    * typed blocks. */
  private def renderInput(messages: Vector[ProviderMessage]): Vector[Json] =
    messages.flatMap {
      case ProviderMessage.System(content) =>
        // System frames mid-conversation: Responses API takes a
        // top-level `instructions`, but that's a single string. For
        // mid-conversation system frames we fall back to a user-role
        // input item marked as system guidance.
        Vector(obj(
          "role" -> str("user"),
          "content" -> arr(obj(
            "type" -> str("input_text"),
            "text" -> str(s"[system] $content")
          ))
        ))

      case ProviderMessage.User(blocks) =>
        val contentItems = blocks.map {
          case MessageContent.Text(t) =>
            obj("type" -> str("input_text"), "text" -> str(t))
          case MessageContent.Image(u, _) =>
            obj("type" -> str("input_image"), "image_url" -> str(u.toString))
        }
        Vector(obj("role" -> str("user"), "content" -> arr(contentItems*)))

      case ProviderMessage.Assistant(content, toolCalls) =>
        val textItem =
          if (content.isEmpty) Vector.empty
          else Vector(obj(
            "role" -> str("assistant"),
            "content" -> arr(obj("type" -> str("output_text"), "text" -> str(content)))
          ))
        val functionCallItems = toolCalls.map { tc =>
          obj(
            "type" -> str("function_call"),
            "call_id" -> str(tc.id),
            "name" -> str(tc.name),
            "arguments" -> str(tc.argsJson)
          )
        }
        (textItem ++ functionCallItems).toVector

      case ProviderMessage.ToolResult(toolCallId, content) =>
        Vector(obj(
          "type" -> str("function_call_output"),
          "call_id" -> str(toolCallId),
          "output" -> str(content)
        ))

      case ProviderMessage.Reasoning(providerItemId, summary, encryptedContent) =>
        // Bug #61 — replay reasoning items captured from prior turns
        // back into the Responses API's `input` array. The API expects
        // each item in its original position relative to the
        // function_call(s) it generated; the framework's frame
        // ordering (chronological by event timestamp) preserves that.
        // For gpt-5 / 5.x, `summary` carries the textual summary; for
        // o1 / o3, `encryptedContent` is the opaque CoT blob the API
        // requires verbatim.
        val summaryArr = arr(summary.map(t => obj(
          "type" -> str("summary_text"),
          "text" -> str(t)
        ))*)
        val baseFields = Vector[(String, Json)](
          "type"    -> str("reasoning"),
          "id"      -> str(providerItemId),
          "summary" -> summaryArr
        )
        val encryptedField = encryptedContent.toVector.map("encrypted_content" -> str(_))
        Vector(obj((baseFields ++ encryptedField)*))
    }

  /** Render custom functions + built-in tools into the Responses
    * `tools` array. Each item is a top-level object with a `type`
    * discriminator (`function` for custom, built-in names otherwise). */
  private def renderTools(input: ProviderCall): Vector[Json] = {
    val functionTools = input.tools.map { t =>
      val s = t.schema
      // Bug #64 — opt out of strict mode per-tool when the input
      // schema contains a `DefType.Json` anywhere in its tree. Strict
      // mode demands every "object"-typed branch declare its own
      // closed `properties` + `additionalProperties: false`, which is
      // mutually exclusive with "any JSON value" (`type` union
      // including `"object"` per #63). The non-strict path renders
      // the schema unchanged — `Json` fields ship as the typed
      // permissive union from #63, accepted by the validator.
      // Strict-mode-incompatible tools lose grammar-constrained
      // decoding; `ToolInputValidator` still re-checks args
      // post-decode for safety.
      val canBeStrict = !DefinitionToSchema.containsJson(s.input)
      val baseSchema = DefinitionToSchema(s.input)
      val parameters =
        if (canBeStrict) StrictSchema.forOpenAIStrict(baseSchema)
        else StrictSchema.stripUnsupportedKeys(baseSchema)
      obj(
        "type"        -> str("function"),
        "name"        -> str(s.name.value),
        "description" -> str(renderDescription(t, input.currentMode)),
        // Strict mode enables grammar-constrained decoding — the model
        // can't emit malformed args. Requires a schema dialect with
        // every property `required` (optionals widened to nullable),
        // `additionalProperties: false` everywhere, and no `pattern` /
        // `format` / numeric-bound keywords. `StrictSchema` rewrites
        // sigil's standard schema into that shape.
        "strict"      -> bool(canBeStrict),
        "parameters"  -> parameters
      )
    }
    val builtIn = input.builtInTools.iterator.flatMap(renderBuiltIn).toVector
    functionTools ++ builtIn
  }

  /** Map a [[BuiltInTool]] to the Responses tool-array entry shape.
    * Tools the API hasn't surfaced yet are dropped silently. */
  private def renderBuiltIn(tool: BuiltInTool): Option[Json] = tool match {
    case BuiltInTool.WebSearch       => Some(obj("type" -> str("web_search")))
    case BuiltInTool.ImageGeneration => Some(obj("type" -> str("image_generation")))
    case BuiltInTool.FileSearch      => Some(obj("type" -> str("file_search")))
    case BuiltInTool.CodeInterpreter => Some(obj("type" -> str("code_interpreter")))
    case BuiltInTool.ComputerUse     => Some(obj("type" -> str("computer_use_preview")))
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

  // ---- streaming response parsing ----

  private[openai] def parseLine(line: String, state: StreamState): Vector[ProviderEvent] =
    SSELineParser.parse(line) match {
      case SSELine.Data(json)                  => parseEvent(json, state)
      case SSELine.Done                        => state.flushDone()
      case SSELine.MalformedData(_, reason)    => Vector(ProviderEvent.Error(s"Failed to parse chunk: $reason"))
      case SSELine.Blank | SSELine.Comment | _: SSELine.Other => Vector.empty
    }

  /** Route a Responses SSE event by `type` discriminator. Unknown
    * types are ignored — OpenAI adds new ones over time; we surface
    * the ones we understand and drop the rest. */
  private def parseEvent(json: Json, state: StreamState): Vector[ProviderEvent] = {
    val eventType = json.get("type").map(_.asString).getOrElse("")
    eventType match {
      case "response.output_item.added" =>
        parseOutputItemAdded(json, state)

      case "response.output_text.delta" =>
        val callId = state.activeItemCallId.getOrElse(CallId("responses-text"))
        val rawDelta = json.get("delta").map(_.asString).getOrElse("")
        // Bug #50 — strip OpenAI Responses' inline citation markers
        // (`【cite_turn0view0】` etc., U+3010 / U+3011 lenticular
        // brackets). The actual URL/title pairs ride on
        // `response.output_text.annotation.added` events; the
        // markers themselves are placeholders the client is
        // expected to swap. Without stripping, downstream UIs
        // render literal `【cite_…】` strings (boxes in most fonts).
        val delta = OpenAIProvider.CitationMarker.replaceAllIn(rawDelta, "")
        if (delta.isEmpty) Vector.empty
        else Vector(ProviderEvent.ContentBlockDelta(callId, delta))

      case "response.output_text.annotation.added" =>
        // Bug #50 — buffer URL citations so we can emit them as a
        // trailing markdown footer at `response.completed`. The
        // annotation is the structured counterpart to the
        // `【cite_…】` marker we just stripped from the text.
        val ann = json.get("annotation").getOrElse(json)
        val annType = ann.get("type").map(_.asString).getOrElse("")
        if (annType == "url_citation") {
          val url = ann.get("url").map(_.asString).getOrElse("")
          val title = ann.get("title").map(_.asString).filter(_.nonEmpty).getOrElse(url)
          if (url.nonEmpty) state.pendingCitations.append(title -> url)
        }
        Vector.empty

      case "response.function_call_arguments.delta" =>
        val idx = state.itemIndex
        val fragment = json.get("delta").map(_.asString).getOrElse("")
        state.acc.appendArgs(idx, fragment)

      case "response.reasoning_summary_text.delta" | "response.reasoning.delta" =>
        val delta = json.get("delta").map(_.asString).getOrElse("")
        // Bug #61 — accumulate the summary fragments per active
        // reasoning item so the final ReasoningItem we emit at
        // `output_item.done` carries the full text. Also surface the
        // delta as a `ThinkingDelta` for UIs that visualize CoT
        // streams in real time.
        if (delta.nonEmpty) state.activeItemCallId.foreach { cid =>
          val buf = state.reasoningSummaryBuffers.getOrElseUpdate(cid.value, new StringBuilder)
          buf.append(delta)
        }
        if (delta.isEmpty) Vector.empty
        else Vector(ProviderEvent.ThinkingDelta(delta))

      case "response.image_generation_call.partial_image" =>
        val callId = state.activeItemCallId.getOrElse(CallId("responses-image"))
        val b64 = json.get("partial_image_b64").map(_.asString)
        val url = json.get("partial_image_url").map(_.asString)
        val imageRef = url.orElse(b64.map(b => s"data:image/png;base64,$b")).getOrElse("")
        if (imageRef.isEmpty) Vector.empty
        else Vector(ProviderEvent.ImageGenerationPartial(callId, imageRef))

      case "response.image_generation_call.completed" =>
        val callId = state.activeItemCallId.getOrElse(CallId("responses-image"))
        val imageRef = json.get("image_url").map(_.asString)
          .orElse(json.get("image_b64").map(b => s"data:image/png;base64,${b.asString}"))
          .getOrElse("")
        val complete = Vector[ProviderEvent](ProviderEvent.ImageGenerationComplete(callId, imageRef))
        val serverDone = Vector[ProviderEvent](ProviderEvent.ServerToolComplete(callId, BuiltInTool.ImageGeneration))
        complete ++ serverDone

      case "response.output_item.done" =>
        // Item completion — if it's a built-in tool call (image_generation_call,
        // web_search_call, etc.) we emit the completion event here, since the
        // API sometimes delivers the final payload on output_item.done rather
        // than on a dedicated `.completed` event.
        val item = json.get("item").getOrElse(Obj.empty)
        val itemType = item.get("type").map(_.asString).getOrElse("")
        val callIdRaw = item.get("call_id").map(_.asString).orElse(item.get("id").map(_.asString))
        val callId = callIdRaw.map(CallId(_)).getOrElse(state.activeItemCallId.getOrElse(CallId("responses-done")))
        itemType match {
          case "image_generation_call" =>
            val imageRef = item.get("result").map(_.asString)
              .orElse(item.get("image_url").map(_.asString))
              .map(b => if (b.startsWith("data:") || b.startsWith("http")) b else s"data:image/png;base64,$b")
              .getOrElse("")
            if (imageRef.isEmpty) Vector.empty
            else Vector(
              ProviderEvent.ImageGenerationComplete(callId, imageRef),
              ProviderEvent.ServerToolComplete(callId, BuiltInTool.ImageGeneration)
            )
          case "web_search_call" =>
            Vector(ProviderEvent.ServerToolComplete(callId, BuiltInTool.WebSearch))
          case "reasoning" =>
            // Bug #61 — emit the settled ReasoningItem with the
            // accumulated summary text + encrypted_content (if any).
            // Final summary entries on `item.summary` win over the
            // delta-accumulated buffer when both exist (the wire's
            // settled view is authoritative).
            val pid = item.get("id").map(_.asString)
              .orElse(state.reasoningProviderIds.get(callId.value))
              .getOrElse(callId.value)
            val finalSummary = item.get("summary").toList.flatMap { s =>
              s.asArr.value.toList.flatMap(_.get("text").map(_.asString))
            }
            val accumulated = state.reasoningSummaryBuffers.get(callId.value).map(_.toString).filter(_.nonEmpty).toList
            val summary = if (finalSummary.nonEmpty) finalSummary else accumulated
            val encrypted = item.get("encrypted_content").map(_.asString)
              .orElse(state.reasoningEncryptedContents.get(callId.value))
            state.reasoningProviderIds.remove(callId.value)
            state.reasoningSummaryBuffers.remove(callId.value)
            state.reasoningEncryptedContents.remove(callId.value)
            Vector(ProviderEvent.ReasoningItem(pid, summary, encrypted))
          case _ => Vector.empty
        }

      case "response.web_search_call.in_progress" | "response.web_search_call.searching" =>
        val callId = state.activeItemCallId.getOrElse(CallId("responses-websearch"))
        val query = json.get("query").map(_.asString).orElse(None)
        Vector(ProviderEvent.ServerToolStart(callId, BuiltInTool.WebSearch, query))

      case "response.web_search_call.completed" =>
        val callId = state.activeItemCallId.getOrElse(CallId("responses-websearch"))
        Vector(ProviderEvent.ServerToolComplete(callId, BuiltInTool.WebSearch))

      case "response.completed" =>
        // Responses API terminates the stream with `response.completed`
        // (there is no `[DONE]` sentinel like chat-completions). Emit
        // tool-call completions, usage, and Done here.
        val usage = json.get("response").flatMap(_.get("usage")).map(parseUsage)
        val completes = state.acc.complete()
        val usageEv = usage.toVector.map(ProviderEvent.Usage(_))
        // Bug #50 — flush buffered web-search citations as a markdown
        // footer appended to the active text block. Renders as a real
        // "Sources" list in any markdown-capable consumer; passes
        // through as readable plaintext otherwise. Deduplicated by
        // URL so the same source cited multiple times appears once.
        val citationFooter: Vector[ProviderEvent] =
          if (state.pendingCitations.isEmpty) Vector.empty
          else {
            val callId = state.activeItemCallId.getOrElse(CallId("responses-text"))
            val seen = scala.collection.mutable.LinkedHashMap.empty[String, String]
            state.pendingCitations.foreach { case (title, url) =>
              if (!seen.contains(url)) seen += (url -> title)
            }
            val lines = seen.iterator.map { case (url, title) => s"- [$title]($url)" }.mkString("\n")
            state.pendingCitations.clear()
            Vector(ProviderEvent.ContentBlockDelta(callId, s"\n\n**Sources:**\n$lines\n"))
          }
        val apiStatus = json.get("response").flatMap(_.get("status")).map(_.asString)
        val stopReason =
          if (state.sawFunctionCall) StopReason.ToolCall
          else apiStatus match {
            case Some("incomplete") => StopReason.MaxTokens
            case _                  => StopReason.Complete
          }
        completes ++ citationFooter ++ usageEv :+ ProviderEvent.Done(stopReason)

      case "response.error" | "error" =>
        val msg = json.get("error").flatMap(_.get("message")).map(_.asString)
          .orElse(json.get("message").map(_.asString))
          .getOrElse("unknown error")
        Vector(ProviderEvent.Error(msg))

      // Direct `/v1/images/generations` streaming events (gpt-image-* models).
      // Same ProviderEvent vocabulary as the built-in tool path so apps render
      // the same way regardless of dispatch path.
      case "image_generation.partial_image" =>
        val callId = CallId("openai-image")
        val b64 = json.get("b64_json").map(_.asString)
        val url = json.get("url").map(_.asString)
        val imageRef = url.orElse(b64.map(b => s"data:image/png;base64,$b")).getOrElse("")
        if (imageRef.isEmpty) Vector.empty
        else Vector(ProviderEvent.ImageGenerationPartial(callId, imageRef))

      case "image_generation.completed" =>
        val callId = CallId("openai-image")
        val imageRef = json.get("url").map(_.asString)
          .orElse(json.get("b64_json").map(b => s"data:image/png;base64,${b.asString}"))
          .getOrElse("")
        val complete: Vector[ProviderEvent] =
          if (imageRef.isEmpty) Vector.empty
          else Vector(ProviderEvent.ImageGenerationComplete(callId, imageRef))
        complete :+ ProviderEvent.Done(StopReason.Complete)

      case _ => Vector.empty
    }
  }

  /** When an output item is added, OpenAI tells us its kind and call_id.
    * We track the active item's call_id so subsequent deltas can pair. */
  private def parseOutputItemAdded(json: Json, state: StreamState): Vector[ProviderEvent] = {
    val item = json.get("item").getOrElse(Obj.empty)
    val itemType = item.get("type").map(_.asString).getOrElse("")
    val callIdRaw = item.get("call_id").map(_.asString)
      .orElse(item.get("id").map(_.asString))
    val callId = callIdRaw.map(CallId(_)).getOrElse(CallId(s"resp-${state.nextIndex}"))
    state.activeItemCallId = Some(callId)
    val index = json.get("output_index").map(_.asInt).getOrElse(state.nextIndex)
    state.itemIndex = index
    state.nextIndex = math.max(state.nextIndex, index + 1)

    itemType match {
      case "function_call" =>
        state.sawFunctionCall = true
        val name = item.get("name").map(_.asString).getOrElse("")
        state.acc.start(index, callId, name)

      case "message" =>
        // Text-output item — opens a Text content block for subsequent deltas.
        Vector(ProviderEvent.ContentBlockStart(callId, "Text", None))

      case "web_search_call" =>
        Vector(ProviderEvent.ServerToolStart(callId, BuiltInTool.WebSearch, None))

      case "image_generation_call" =>
        val prompt = item.get("prompt").map(_.asString)
        Vector(ProviderEvent.ServerToolStart(callId, BuiltInTool.ImageGeneration, prompt))

      case "file_search_call" =>
        Vector(ProviderEvent.ServerToolStart(callId, BuiltInTool.FileSearch, None))

      case "code_interpreter_call" =>
        Vector(ProviderEvent.ServerToolStart(callId, BuiltInTool.CodeInterpreter, None))

      case "reasoning" =>
        // Bug #61 — capture the wire-level reasoning item id so
        // `response.reasoning_summary_text.delta` can accumulate
        // against it and `output_item.done` can emit the settled
        // ReasoningItem. The id (`rs_…`) and any inline summary or
        // encrypted_content are stashed on the StreamState; we don't
        // emit a ProviderEvent here — the settled record arrives at
        // `output_item.done`.
        val pid = item.get("id").map(_.asString).getOrElse(callId.value)
        val initialSummary = item.get("summary").toList.flatMap { s =>
          s.asArr.value.toList.flatMap(_.get("text").map(_.asString))
        }
        val encrypted = item.get("encrypted_content").map(_.asString)
        state.reasoningProviderIds(callId.value) = pid
        if (initialSummary.nonEmpty) {
          val buf = state.reasoningSummaryBuffers.getOrElseUpdate(callId.value, new StringBuilder)
          initialSummary.foreach(buf.append)
        }
        encrypted.foreach(state.reasoningEncryptedContents(callId.value) = _)
        Vector.empty

      case _ =>
        Vector.empty
    }
  }

  private def parseUsage(json: Json): TokenUsage =
    TokenUsage(
      promptTokens = json.get("input_tokens").map(_.asInt).getOrElse(0),
      completionTokens = json.get("output_tokens").map(_.asInt).getOrElse(0),
      totalTokens = json.get("total_tokens").map(_.asInt).getOrElse(0)
    )

  /** Per-response state: tracks the active output item (so deltas
    * pair with the right call_id), plus a shared tool-call
    * accumulator for function-call args. Mutable — confined to a
    * single stream. */
  final private[openai] class StreamState(val acc: ToolCallAccumulator) {
    var activeItemCallId: Option[CallId] = None
    var itemIndex: Int = 0
    var nextIndex: Int = 0
    var pendingDone: Option[StopReason] = None
    var sawFunctionCall: Boolean = false
    /** Annotations gathered from `response.output_text.annotation.added`
      * events (web-search citations). Emitted at `response.completed`
      * as a trailing markdown footer so the URLs reach the user
      * instead of being dropped along with the inline `【cite_…】`
      * markers. Bug #50. */
    val pendingCitations: scala.collection.mutable.ArrayBuffer[(String, String)] =
      scala.collection.mutable.ArrayBuffer.empty

    /** Bug #61 — per-active-reasoning-item state. Keys are the
      * stream-local callId.value (since `output_item.added` always
      * carries one); values are the wire-level provider id (`rs_…`),
      * accumulated summary text fragments, and the optional encrypted
      * CoT blob respectively. Cleared on `output_item.done`. */
    val reasoningProviderIds: scala.collection.mutable.Map[String, String] =
      scala.collection.mutable.Map.empty
    val reasoningSummaryBuffers: scala.collection.mutable.Map[String, StringBuilder] =
      scala.collection.mutable.Map.empty
    val reasoningEncryptedContents: scala.collection.mutable.Map[String, String] =
      scala.collection.mutable.Map.empty

    def flushDone(): Vector[ProviderEvent] = pendingDone match {
      case Some(sr) => pendingDone = None; Vector(ProviderEvent.Done(sr))
      case None     => Vector.empty
    }
  }
}

object OpenAIProvider {
  /** OpenAI Responses API web-search citation markers. Two shapes
    * observed on the wire:
    *   - Bracketed: `【cite_turn1view0】` (U+3010 + `cite` + separator
    *     + `turn<N>view<M>` group(s) + U+3011). The separator has
    *     varied — underscore, PUA characters, RTL marks — so we
    *     match liberally on "any non-alphanumeric chars between
    *     `cite` and `turn`."
    *   - Bare: `citeturn1view0turn1view2` — no brackets at all,
    *     concatenated directly to surrounding prose. Same `turn<N>(view|search|navlist|news)<M>`
    *     repeating block, prefixed with `cite`. We drop both
    *     forms; the structured URL/title pair rides on
    *     `response.output_text.annotation.added` and is rendered
    *     as a Sources footer at `response.completed`. Bug #50 / #51. */
  private val CitationMarker: scala.util.matching.Regex =
    """(?:【\s*cite[^a-zA-Z0-9]*(?:turn\d+(?:view|search|navlist|news)\d+[^a-zA-Z0-9]*)+】|cite(?:turn\d+(?:view|search|navlist|news)\d+)+)""".r

  /** Construct an OpenAIProvider. Models are read from
    * [[sigil.cache.ModelRegistry]] at access time, so the DB just needs
    * to be populated (typically via
    * [[sigil.controller.OpenRouter.refreshModels]]). */
  def create(sigil: Sigil, apiKey: String, baseUrl: URL = url"https://api.openai.com"): Task[OpenAIProvider] =
    Task.pure(OpenAIProvider(apiKey, sigil, baseUrl))
}
