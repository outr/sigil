package sigil.provider

import rapid.{Stream, Task}
import sigil.Sigil
import sigil.conversation.{ContextFrame, TurnInput}
import sigil.db.Model
import sigil.participant.ParticipantId
import sigil.tool.core.RespondTool
import sigil.tool.model.ResponseContent
import spice.http.HttpRequest

/**
 * Pluggable LLM backend. Each provider serializes a uniform
 * [[ProviderCall]] to its own wire format (OpenAI chat-completions,
 * Anthropic messages, llama.cpp's OpenAI-compatible surface, etc.) and
 * parses the streaming response into [[ProviderEvent]]s.
 *
 * The framework's `apply` is `final` and handles all variant dispatch
 * + translation. Implementations only see `ProviderCall` and only
 * implement `call` (and `httpRequestFor` for the inspect-without-send
 * path used by tests).
 *
 * Shared translation logic — DB resolution of memory/summary ids,
 * system-prompt assembly, frame-to-message rendering — lives in this
 * trait, not in each provider. Adding a new provider means writing one
 * `call` implementation, not duplicating ~500 lines of conversation-
 * aware machinery.
 */
trait Provider {
  def `type`: ProviderType

  /** This provider's namespace key — matches the prefix on
    * `Model.canonicalSlug` and `Model._id`. Default derives from the
    * `type` enum value's lowercased name (`OpenAI` → `"openai"`).
    * Override only when a provider's models live under a different
    * namespace. */
  def providerKey: String = `type`.toString.toLowerCase

  /** DB / configuration access for the shared translation pass. Wired
    * by each provider implementation (typically as a constructor arg). */
  protected def sigil: Sigil

  /**
   * Models available in this provider's namespace, read synchronously
   * from [[sigil.cache.ModelRegistry]]. The registry is populated by
   * [[sigil.controller.OpenRouter.refreshModels]] (run automatically
   * on the background refresh interval, or manually by the app) —
   * long-running apps see fresh metadata as it lands without
   * reconstructing the provider, and the read is a single
   * `AtomicReference` deref so this is safe to call on every request.
   *
   * Local providers like [[sigil.provider.llamacpp.LlamaCppProvider]]
   * override with their own list (loaded from the running server,
   * not openrouter).
   */
  def models: List[Model] = sigil.cache.find(provider = Some(providerKey))

  // ---- public entry points (final) ----

  /**
   * Send a request and receive a stream of provider events. Final —
   * implementations must not override. Internally translates the request
   * into a uniform [[ProviderCall]] and dispatches to [[call]].
   *
   * The stream terminates with a `Done` event (or `Error`).
   */
  final def apply(request: ProviderRequest): Stream[ProviderEvent] =
    Stream.force(translate(request).map(call))

  /**
   * Build the underlying [[spice.http.HttpRequest]] for a sigil request without
   * performing any network I/O. `apply` invokes the same translation
   * pass before calling `call`; tests can call this directly to inspect
   * the wire payload (typically by reading `httpRequest.content` and
   * asserting on the JSON body).
   *
   * Final — providers implement [[httpRequestFor]] instead.
   */
  final def requestConverter(request: ProviderRequest): Task[HttpRequest] =
    translate(request)
      .flatMap(httpRequestFor)
      // Invoke the wire interceptor here too so inspect-only paths
      // (tests, debug dumps) still produce wire logs — same coverage
      // as the live streaming path in `call`.
      .flatMap(sigil.wireInterceptor.before)

  // ---- protected: providers implement these ----

  /**
   * The provider's wire-level streaming call. Receives a fully-resolved,
   * format-neutral [[ProviderCall]]. Implementation: serialize to the
   * provider's request format, POST, parse the streaming response into
   * [[ProviderEvent]]s.
   */
  protected def call(input: ProviderCall): Stream[ProviderEvent]

  /**
   * Build the wire-level [[spice.http.HttpRequest]] from a [[ProviderCall]] without
   * sending it. Used by the final [[requestConverter]] for inspect-only
   * test paths.
   */
  protected def httpRequestFor(input: ProviderCall): Task[HttpRequest]

  // ---- shared translation, private to the framework ----

  private def translate(req: ProviderRequest): Task[ProviderCall] = req match {
    case c: ConversationRequest => translateConversation(c)
    case s: OneShotRequest      => Task.pure(translateOneShot(s))
  }

  private def translateConversation(c: ConversationRequest): Task[ProviderCall] =
    resolveReferences(c.turnInput).map { resolved =>
      val agentId = c.chain.lastOption
      val toolChoice =
        if (c.tools.isEmpty) ToolChoice.None else ToolChoice.Required
      ProviderCall(
        modelId = c.modelId,
        system = renderSystem(c, resolved),
        messages = renderFrames(c.turnInput.conversationView.frames, agentId),
        tools = c.tools,
        builtInTools = c.builtInTools,
        toolChoice = toolChoice,
        generationSettings = c.generationSettings,
        currentMode = c.currentMode
      )
    }

  private def translateOneShot(s: OneShotRequest): ProviderCall = {
    val toolChoice =
      if (s.tools.isEmpty) ToolChoice.None else ToolChoice.Required
    val userMessage =
      if (s.userContent.nonEmpty) ProviderMessage.User(toMessageContent(s.userContent))
      else ProviderMessage.User(s.userPrompt)
    ProviderCall(
      modelId = s.modelId,
      system = s.systemPrompt,
      messages = Vector(userMessage),
      tools = s.tools,
      builtInTools = s.builtInTools,
      toolChoice = toolChoice,
      generationSettings = s.generationSettings
    )
  }

  /** Project the public [[ResponseContent]] vocabulary onto the
    * narrower wire-level [[MessageContent]] used in
    * [[ProviderMessage.User]]. `Text` and `Image` map directly;
    * structured variants (Code, Diff, Table, Heading, …) render to
    * a `Text` block via `toString` so the model still sees the
    * content even on text-only providers. Image blocks survive into
    * the wire layer; per-provider serialization there decides
    * whether to send or drop based on the target API's multimodal
    * support. */
  private def toMessageContent(content: Vector[ResponseContent]): Vector[MessageContent] =
    content.map {
      case ResponseContent.Text(t)             => MessageContent.Text(t)
      case ResponseContent.Image(url, alt)     => MessageContent.Image(url, alt)
      case ResponseContent.Markdown(t)         => MessageContent.Text(t)
      case ResponseContent.Code(c, lang)       => MessageContent.Text(s"```${lang.getOrElse("")}\n$c\n```")
      case other                                => MessageContent.Text(other.toString)
    }

  /** Resolve the ids on `TurnInput.criticalMemories` / `.memories` /
    * `.summaries` to full records via the DB. Ids that don't resolve are
    * dropped silently. */
  private def resolveReferences(turn: TurnInput): Task[ResolvedReferences] = {
    for {
      crit <- Task.sequence(turn.criticalMemories.toList.map(id =>
                sigil.withDB(_.memories.transaction(_.get(id)))))
      regular <- Task.sequence(turn.memories.toList.map(id =>
                   sigil.withDB(_.memories.transaction(_.get(id)))))
      summaries <- Task.sequence(turn.summaries.toList.map(id =>
                     sigil.withDB(_.summaries.transaction(_.get(id)))))
    } yield ResolvedReferences(
      criticalMemories = crit.flatten.toVector,
      memories = regular.flatten.toVector,
      summaries = summaries.flatten.toVector
    )
  }

  /** Compose the system prompt body from every contextually relevant
    * field on a [[ConversationRequest]]. Each section is omitted
    * when its source is empty. Every Model-visible field on `TurnInput`
    * / `ConversationView` MUST appear here. The companion
    * [[spec.LlamaCppRequestCoverageSpec]] is the regression guard. */
  private def renderSystem(c: ConversationRequest,
                           resolved: ResolvedReferences): String = {
    val turn = c.turnInput
    val view = turn.conversationView
    val chain = c.chain
    val sb = new StringBuilder

    if (c.tools.nonEmpty) {
      sb.append(
        "You communicate exclusively through tool calls. Plain text output is never delivered to the user — " +
          "always pick a tool.\n\n"
      )
    }

    sb.append(s"Current mode: ${c.currentMode} — ${c.currentMode.description}\n")
    // Tools that need runtime context (e.g. `change_mode` enumerating
    // the available modes) override `Tool.descriptionFor` to fold
    // that context into their own description. The framework
    // prompt-builder stays free of per-tool special cases.
    sb.append(s"Current topic: \"${c.currentTopic.label}\" — ${c.currentTopic.summary}\n")
    if (c.previousTopics.nonEmpty) {
      sb.append("Previous topics in this conversation:\n")
      c.previousTopics.foreach(t => sb.append(s"  - \"${t.label}\" — ${t.summary}\n"))
    }

    // Instructions' TOOLS discovery block tells the model to call
    // `find_capability` first for actions outside its tool roster. If
    // that tool isn't actually available (e.g. the active mode uses
    // `ToolPolicy.None` or `Exclusive`), pointing the model at it
    // creates a dead loop — strip the block in that case. When
    // `find_capability` IS available but `respond` ISN'T (PureDiscovery
    // active), swap to the pure-discovery variant so the prompt
    // doesn't describe `respond` as immediately callable.
    val findCapabilityAvailable =
      c.tools.exists(_.schema.name.value == "find_capability")
    val respondAvailable =
      c.tools.exists(_.schema.name.value == "respond")
    val instr =
      if (!findCapabilityAvailable) c.instructions.renderWithoutTools
      else if (!respondAvailable) c.instructions.forPureDiscovery.render
      else c.instructions.render
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
      turn.information.foreach(i =>
        sb.append(s"- ${i.id.value} [${i.informationType.name}]: ${i.summary}\n"))
    }

    // Roles render the agent's identity into the system prompt. A single
    // role is shown linearly (one description block); multiple roles get a
    // "You serve the following roles:" preamble + per-role enumeration so
    // the model handles multi-role identity explicitly even when each
    // role's description was written self-contained.
    c.roles match {
      case Nil           => ()
      case List(single)  =>
        if (single.description.nonEmpty)
          sb.append("\n").append(single.description).append("\n")
      case multi         =>
        sb.append("\nYou serve the following roles:\n")
        multi.foreach { r =>
          sb.append(s"- ${r.name}")
          if (r.description.nonEmpty) sb.append(s" — ${r.description}")
          sb.append("\n")
        }
    }

    val skills = view.aggregatedSkills(chain)
    val roleSkills = c.roles.flatMap(_.skill.toList)
    val allSkills = (skills ++ roleSkills).distinctBy(_.name)
    if (allSkills.nonEmpty) {
      sb.append("\n== Active skills ==\n")
      allSkills.foreach { s =>
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

    val perParticipantExtras =
      chain.flatMap(id => view.projectionFor(id).extraContext.map(id -> _))
    if (perParticipantExtras.nonEmpty) {
      sb.append("\n== Participant context ==\n")
      perParticipantExtras.foreach { case (pid, (k, v)) =>
        sb.append(s"- ${pid.value} ${k.value}: $v\n")
      }
    }

    sb.toString
  }

  /** Render a conversation's [[ContextFrame]]s into format-neutral
    * [[ProviderMessage]]s. Mapping rules:
    *
    *   - `Text` from the agent itself        → `Assistant`
    *   - `Text` from anyone else             → `User`
    *   - `ToolCall` from the agent for any
    *     tool *other than* `respond`         → `Assistant` with `toolCalls`
    *     The `respond` tool's call is filtered because the following
    *     `Text` frame IS the response — emitting both would yield a
    *     tool_call without a matching tool_result.
    *   - `ToolCall` from someone else        → skipped
    *   - `ToolResult`                        → `ToolResult` paired by callId
    *   - `System`                            → `ToolResult` if a tool call
    *     is open; otherwise `System`
    *
    * Only model-visible events become frames in the first place (see
    * [[sigil.conversation.FrameBuilder]]), so UI-only history never
    * reaches this renderer.
    */
  private def renderFrames(frames: Vector[ContextFrame],
                           agentId: Option[ParticipantId]): Vector[ProviderMessage] = {
    val out = Vector.newBuilder[ProviderMessage]
    var pendingToolCallId: Option[String] = None

    // Bug #69 — merge consecutive `ContextFrame.ToolResult` entries
    // sharing the same `callId` into a single frame whose content is
    // the concatenation in emission order. The wire stays 1:1
    // (`function_call` ↔ `function_call_output`) which is what every
    // provider expects; tool authors who emit multiple Tool-role
    // events for one call get them folded into one wire-level result.
    val merged = mergeAdjacentToolResults(frames)

    merged.foreach {
      case ContextFrame.Text(content, participantId, _, _) =>
        if (agentId.contains(participantId)) out += ProviderMessage.Assistant(content)
        else out += ProviderMessage.User(content)

      case ContextFrame.ToolCall(toolName, _, _, participantId, _, _)
        if toolName == RespondTool.schema.name && agentId.contains(participantId) =>
      // Skip — the following Text frame IS the response.

      case ContextFrame.ToolCall(toolName, argsJson, callId, participantId, _, _) if agentId.contains(participantId) =>
        out += ProviderMessage.Assistant(
          content = "",
          toolCalls = List(ToolCallMessage(
            id = callId.value,
            name = toolName.value,
            argsJson = argsJson
          ))
        )
        pendingToolCallId = Some(callId.value)

      case _: ContextFrame.ToolCall =>
      // ToolCall from someone else — skip (not rendered as a tool call for this agent).

      case ContextFrame.ToolResult(callId, content, _, _) =>
        out += ProviderMessage.ToolResult(toolCallId = callId.value, content = content)
        if (pendingToolCallId.contains(callId.value)) pendingToolCallId = None

      case ContextFrame.System(content, _, _) =>
        out += ProviderMessage.System(content)

      case ContextFrame.Reasoning(providerItemId, summary, encryptedContent, _, _, _) =>
        // Provider-internal reasoning state from a prior turn (bug #61).
        // Surfaced uniformly as a `ProviderMessage.Reasoning` entry; the
        // originating provider serializes it back onto the wire and other
        // providers drop it in their `renderInput`.
        out += ProviderMessage.Reasoning(providerItemId, summary, encryptedContent)
    }

    // Dangling tool_call without a result — defensive fallback. Should
    // never fire under the normal flow (every non-terminal tool emits
    // a `MessageRole.Tool` event that produces a paired `ToolResult` frame),
    // but providers reject bare `tool_calls` in the request, so we
    // ensure every dangling pending id has SOMETHING.
    //
    // Bug #67 — the placeholder text is intentionally diagnostic
    // rather than blandly true ("no result recorded" tells the agent
    // nothing about why). When this fallback fires it means the
    // tool's `executeTyped` returned a stream that didn't include any
    // `MessageRole.Tool`-shaped event for this call_id — typically a
    // sync throw escaping the tool's `handleError` (the bug shape
    // #67 fixes for the script tools). The text frames it as a
    // framework-level miss so the agent doesn't blame its own input.
    pendingToolCallId.foreach { callId =>
      out += ProviderMessage.ToolResult(
        toolCallId = callId,
        content =
          "(framework error: tool emitted no MessageRole.Tool event for this call_id; " +
            "this is a bug in the tool's executeTyped — usually a sync throw escaping " +
            "its handleError. Please report it.)"
      )
    }

    out.result()
  }

  /** Walk the frame vector and merge runs of [[ContextFrame.ToolResult]]
    * frames sharing the same `callId` into a single frame whose
    * content is the run's contents joined with `\n\n`. Bug #69 — tool
    * authors who emit multiple Tool-role events for one call (the
    * old [[sigil.event.ToolResults]] suggestion-cascade pattern, the
    * primary-result-plus-followup shape, etc.) get a single wire
    * `function_call_output` instead of one paired result + N orphan
    * frames.
    *
    * Only **adjacent** ToolResult frames merge — interleaved frames
    * (a Text frame between two ToolResults sharing a callId) are kept
    * separate since the textual ordering is meaningful. In practice
    * orchestrator-stamped events from a single `executeTyped` arrive
    * contiguously, so adjacency tracks the actual "all from one tool
    * call" boundary. */
  private def mergeAdjacentToolResults(frames: Vector[ContextFrame]): Vector[ContextFrame] = {
    val out = Vector.newBuilder[ContextFrame]
    var pending: Option[ContextFrame.ToolResult] = None
    val joiner = "\n\n"

    def flush(): Unit = {
      pending.foreach(out += _)
      pending = None
    }

    frames.foreach {
      case curr @ ContextFrame.ToolResult(callId, content, _, _) =>
        pending match {
          case Some(prev) if prev.callId == callId =>
            // Same call_id — merge into the pending accumulator.
            // Keep the earliest sourceEventId / visibility (caller can
            // override via dedicated joiner if needed; default is
            // newline-separated concat).
            pending = Some(prev.copy(content = prev.content + joiner + content))
          case _ =>
            flush()
            pending = Some(curr)
        }
      case other =>
        flush()
        out += other
    }
    flush()
    out.result()
  }
}
