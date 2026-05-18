package sigil.orchestrator

import lightdb.id.Id
import lightdb.time.Timestamp
import rapid.{Stream, Task}
import sigil.Sigil
import sigil.conversation.{ContextFrame, Conversation, Topic, TopicShiftResult}
import sigil.event.{Event, Message, MessageDisposition, MessageRole, MessageVisibility, Reasoning, TopicChange, TopicChangeKind, ToolInvoke}
import sigil.participant.ParticipantId
import sigil.provider.{CallId, ConversationRequest, Provider, ProviderEvent, StopReason, XmlToolCallSanitizer}
import sigil.signal.{MessageContentDelta, ContentKind, EventState, ImageDelta, MessageDelta, Signal, StateDelta, ThinkingChunk, ToolDelta, XmlToolCallLeak}
import sigil.tool.core.{CoreTools, FindCapabilityInput}
import sigil.tool.model.{MarkdownContentParser, RespondInput, ResponseContent}
import sigil.tool.ToolName
import sigil.TurnContext
import sigil.tool.{Tool, ToolInput, ToolPreconditionResult}

/**
 * Stateless, per-invocation bridge between the provider wire stream and the
 * sigil `Signal` vocabulary.
 *
 *   - Consumes `Stream[ProviderEvent]` from a [[Provider]]
 *   - Emits `Stream[Signal]` — `Event`s (ToolInvoke, Message, …) and `Delta`s
 *     (ToolDelta, MessageDelta) in the order a subscriber can apply to
 *     reconstruct the durable state
 *   - Runs `tool.execute(input, TurnContext)` for atomic tools (those for
 *     which no `ContentBlockDelta`s arrived during the call) and forwards the
 *     resulting Events as Signals
 *
 * Invariant: whenever a `ContentBlockDelta` was observed for a tool call, the
 * orchestrator treats it as streaming-producing and pre-creates a `Message`.
 * Otherwise the call is atomic and `execute` handles the result emission at
 * `ToolCallComplete`.
 *
 * Per-request ephemeral state lives in a mutable [[Orchestrator.State]] object
 * scoped to the stream closure — no server-side caching survives between
 * invocations.
 *
 * Scope of this initial implementation:
 *   - no RocksDB persistence (the Signal stream is the sole output; a wrapper
 *     layer will apply Signals to a Signal store once one exists)
 *   - no broadcast hook (caller consumes the stream directly)
 *   - no multi-turn follow-up (StopReason handling stops the stream)
 */
object Orchestrator {

  /** Names of the framework's terminal user-visible tools. Completion
    * of any one of these means the agent "spoke" — i.e. produced
    * something the user (or the orchestrator's silent-turn detector
    * in [[Sigil.runAgentLoop]]) recognizes as a closing signal for
    * the turn. Used for bug #46's silent-completion fallback. */
  val UserVisibleTerminalTools: Set[String] =
    Set("respond", "respond_options", "respond_field", "respond_failure", "no_response")

  /** Canonicalise a `find_capability` keywords string for repeated-
    * query detection (sigil bug #159). Lowercases, trims, and
    * collapses whitespace runs so trivial formatting differences
    * (`"foo  bar"` vs `" foo bar "`) don't slip past the
    * once-per-turn intercept. */
  def normalizeQuery(s: String): String = s.trim.toLowerCase.replaceAll("\\s+", " ")

  /** Read the caller agent's [[sigil.provider.SafetyPosture]] from
    * the turn context. The orchestrator's consent gate (sigil bug
    * #160) bypasses `requiresUserConsent` when this is
    * `Autonomous` — the user has pre-authorized the agent and the
    * gate would only force the agent to call `record_consent` on
    * itself.
    *
    * Walks the conversation's participants to find the caller's
    * instance and reads `instructions.posture`. Non-agent callers
    * (or chains where the caller isn't a registered participant)
    * default to `Confirming` — the framework refuses to bypass
    * gates for callers it can't attribute. */
  def isAutonomousPosture(context: TurnContext): Boolean =
    context.conversation.participants
      .find(_.id == context.caller)
      .collect { case agent: _root_.sigil.participant.AgentParticipant => agent }
      .exists(_.instructions.posture == _root_.sigil.provider.SafetyPosture.Autonomous)

  def process(sigil: Sigil,
              provider: Provider,
              request: ConversationRequest,
              conversation: Conversation): Stream[Signal] = {
    // The full [[Conversation]] is threaded in by the caller (in
    // production, `Sigil.runAgentTurn` from the live DB row that
    // `runAgentLoop` already reloads each iteration). Tools' atomic
    // dispatch path embeds this conversation in its [[TurnContext]],
    // so app-level fields like `space` / `participants` /
    // `clearedAt` / `currentMode` round-trip correctly into
    // `ctx.conversation`. Bug #46 — earlier the orchestrator
    // synthesized a stand-in from `ConversationRequest` and silently
    // dropped every field not on the request, breaking apps that
    // pattern-match on `SpaceId` inside a tool.
    //
    // Keyed by wire-level string so the provider's `activeToolName`
    // lookup stays a plain String comparison (the provider reports
    // tool names via the raw JSON wire).
    val toolsByName: Map[String, Tool] = request.tools.map(t => t.schema.name.value -> t).toMap
    val state = new State()
    val convId = request.conversationId

    // Capture the most recent throwable observed in this stream's
    // lifecycle so the corruption-resistance cleanup (below) can
    // surface a useful diagnostic on the orphan-settle messages it
    // emits. `onErrorFinalize` only fires when the error is on the
    // OUTER pull's stepTask — rapid's implementation doesn't
    // recursively wrap inner `Step.Concat` pulls, so errors
    // originating inside `translate`'s inner streams or inside
    // `provider(request)`'s appended sub-streams bypass it. The
    // `guarantee` block below runs on every termination path
    // (success, outer error, inner-Concat error) and consults this
    // ref + `state.activeCalls` to publish any unsettled orphans.
    val capturedError = new java.util.concurrent.atomic.AtomicReference[Option[Throwable]](None)

    /** Publish orphan-settle signals for any tool calls left
      * in-flight when the stream terminates. Runs at termination
      * regardless of how the stream ended; idempotent because
      * `settleOrphanToolInvoke` clears `state.activeCalls` after
      * walking it.
      *
      * `settleOrphanMessage` ONLY fires when an error was actually
      * observed — it stamps the in-flight streaming Message with a
      * Failure disposition + "Tool call failed before settling"
      * text, and we must not corrupt a perfectly-good streaming
      * Message on the success path just because
      * `state.activeMessageId` hasn't been cleared yet (a respond
      * turn whose plain-text stream the model emits without
      * wrapping in a tool call is a legitimate success shape).
      */
    def reconcileInflight: Task[Unit] = {
      val errOpt = capturedError.get()
      val errorMsg = errOpt.flatMap(t => Option(t.getMessage)).filter(_.nonEmpty)
      val callerForOrphan = request.chain.lastOption.getOrElse(
        throw new IllegalStateException("ProviderRequest.chain is empty; orchestrator needs at least one participant.")
      )
      val orphans = settleOrphanToolInvoke(
        state, convId,
        caller = callerForOrphan,
        topicId = request.currentTopic.id,
        error = errorMsg,
        reasonFor = a =>
          errorMsg.fold(s"Tool `${a.toolName}` did not produce a result")(
            err => s"Tool `${a.toolName}` did not complete: $err"
          ),
        recoverable = true
      ) ++ (if (errOpt.isDefined) settleOrphanMessage(state, convId, error = errorMsg) else Nil)
      orphans.foldLeft(Task.unit) { (acc, sig) =>
        acc.flatMap(_ => sigil.publish(sig).handleError(_ => Task.unit))
      }
    }

    provider(request)
      .flatMap(pe => translate(pe, sigil, request, conversation, toolsByName, state))
      .onErrorFinalize { t =>
        // Outer-level errors land here. Capture for the guarantee
        // block, which actually does the orphan-settle publish — we
        // need a single place that's guaranteed to run even when the
        // error originated in an inner `Step.Concat` pull (where
        // rapid's `onErrorFinalize` wrap doesn't reach).
        Task { capturedError.set(Some(t)); () }
      }
      .guarantee(Task.defer {
        // Sigil bug #190 — corruption-resistance invariant: every
        // ToolInvoke this orchestrator emitted must reach Complete +
        // have at least one paired result event by the time the
        // stream terminates. `guarantee` runs at termination on every
        // path: clean Stop, outer error, inner-Concat error, fiber
        // cancellation. Replaces the old `onErrorFinalize`-only
        // approach that silently missed inner-stream errors and left
        // ToolInvokes Active in the durable event log, which then
        // poisoned subsequent turns via the wire renderer's dangling-
        // tool-call fallback.
        reconcileInflight
      })
  }

  /** A tool call that's been started but not yet settled. Tracks the
    * provider's `CallId` (origin of routing for parallel tool calls)
    * alongside the framework-side invokeId and the wire-level tool
    * name. Stored in a `LinkedHashMap` keyed by `CallId` so iteration
    * order is insertion order (the orchestrator's streaming-text path
    * routes ContentBlock events to the most recently started tool —
    * which is `activeCalls.lastOption.map(_._2)`). */
  private final case class ActiveCall(toolName: String, invokeId: lightdb.id.Id[Event])

  private final class State {
    /** Tool calls in flight, keyed by the provider's `CallId`. OpenAI
      * (and Anthropic with `parallel_tool_use: true`) interleave
      * deltas for multiple calls inside one turn; the orchestrator
      * needs to route each `ToolCallComplete` back to the matching
      * `ToolCallStart`'s invokeId rather than tracking a single
      * "active" call. Pre-fix this map was an `Option[Id[Event]]`
      * which silently dropped invokeIds when a second `Start` arrived
      * before the first `Complete`. */
    val activeCalls: scala.collection.mutable.LinkedHashMap[CallId, ActiveCall] =
      scala.collection.mutable.LinkedHashMap.empty

    /** Bug #69 — track the most recently settled ToolInvoke's id so a
      * `ProviderEvent.Error` arriving after `ToolCallComplete` (e.g. a
      * stream-level error after the tool itself succeeded) can still
      * stamp `origin` on the error Message. Without this fallback the
      * post-completion error path would emit a Tool-role event with
      * no parent, violating the framework's invariant. */
    var lastSettledInvokeId: Option[lightdb.id.Id[Event]] = None

    /** Most-recent active tool name — used by streaming-text paths
      * (ContentBlockStart/Delta) that want to route to whichever tool
      * is "currently" producing content. With parallel tool calls
      * this is the most recently started; `respond`-style streaming
      * is rarely paralleled in practice so the heuristic holds. */
    def activeToolName: Option[String] = activeCalls.lastOption.map(_._2.toolName)
    def activeToolInvokeId: Option[lightdb.id.Id[Event]] = activeCalls.lastOption.map(_._2.invokeId)
    var activeMessageId: Option[lightdb.id.Id[Event]] = None
    /** Tracks whether the in-flight [[activeMessageId]] has actually
      * been emitted as a `Message` event yet. `ThinkingDelta` reserves
      * the id ahead of any user-visible content so [[ThinkingChunk]]
      * `target` matches the eventual settled Message, but the Message
      * itself is only "born" once `ContentBlockDelta` lands real
      * content. Downstream branches that distinguish streaming-Message
      * from atomic-tool dispatch (`toolCallCompleteInner`,
      * `settleOrphanMessage`, the `Usage` fallback) read THIS flag,
      * not `activeMessageId.isDefined`. */
    var activeMessageCreated: Boolean = false

    /** Bug #55 — id of the most recently emitted user-visible Message
      * for this turn (`role != MessageRole.Tool`). Used as the fallback
      * target when [[ProviderEvent.Usage]] arrives but no streaming
      * `activeMessageId` exists — for tool-call-only models (llama.cpp
      * grammar-constrained `respond` invocations) the agent's
      * user-visible Message is built inside the tool's `executeTyped`,
      * so the streaming-text path never fires. Without this fallback
      * the per-turn token usage would land nowhere and clients render
      * `usage = (0,0,0)` on the agent's bubble. */
    var lastUserVisibleMessageId: Option[lightdb.id.Id[Event]] = None
    var currentKind: Option[ContentKind] = None
    var currentArg: Option[String] = None
    /** Accumulated text for the current open content block. Flushed as a
      * `MessageContentDelta(complete = true, delta = full text)` when the block
      * closes (next ContentBlockStart or ToolCallComplete). */
    val currentBuffer: StringBuilder = new StringBuilder
    /** Accumulates every text fragment the agent produced across the
      * whole turn. Used by the per-turn memory extractor after `Done`. */
    val turnBuffer: StringBuilder = new StringBuilder
    /** Stable Message id per image-generation callId. The first
      * ImageGenerationPartial creates an Active Message; subsequent
      * partials emit `ImageDelta` updates targeting this id; the
      * `Complete` settles it via a final `ImageDelta` plus
      * `StateDelta(Complete)`. */
    var imageMessageIds: Map[String, lightdb.id.Id[Event]] = Map.empty

    /** Bug #75 — track whether the model emitted free-form text
      * (`ProviderEvent.TextDelta`, dispatched by providers when the
      * LLM sends `delta.content` outside of a tool call) AND whether
      * any tool call was started. Smaller / quantised models drift
      * to plain-text output despite `tool_choice: required` after
      * long contexts; pre-fix that text was silently dropped, the
      * turn settled silent, and bug-#46's placeholder fired with no
      * feedback to the model. Post-fix the orchestrator emits a
      * `MessageDisposition.Failure` diagnostic the agent's next
      * iteration reads and can self-correct from. */
    val plainTextBuffer: StringBuilder = new StringBuilder
    var sawAnyToolCall: Boolean = false

    /** Bug #87 — keys (toolName + canonical args JSON) of atomic tool
      * calls already dispatched this turn. When the model emits
      * multiple `function_call`s in one completion that share a key
      * (parallel hedging on a deterministic-failure tool, e.g. a
      * `requiresUserConsent` tool retried in parallel), the
      * duplicates are routed to a synthesized Tool-role result
      * pointing at the first dispatch instead of executing the same
      * (tool, args) N times. Wire shape stays well-formed —
      * `function_call` ↔ `function_call_output` pairing is satisfied
      * for every call_id; the underlying execution happens once. */
    val dispatchedKeys: scala.collection.mutable.Map[String, lightdb.id.Id[Event]] =
      scala.collection.mutable.Map.empty

    /** Tool-role result content keyed by the originating ToolInvoke id.
      * Populated as Tool-role Messages flow through `tracked.evalMap`
      * so a subsequent duplicate dispatch can inline the original
      * result into its own paired Tool-role Message rather than
      * pointing the agent at a `call_id` reference it can't
      * dereference from inside its prompt. */
    val dispatchedResultContent: scala.collection.mutable.Map[lightdb.id.Id[Event], Vector[ResponseContent]] =
      scala.collection.mutable.Map.empty

    /** Wire `callId`s whose `ToolCallComplete` has already been
      * processed in this provider stream. Some OpenAI-compat backends
      * occasionally emit two complete chunks for the same call (parser
      * quirk on chunked streams); the duplicate must not re-dispatch
      * the tool or synthesize a phantom `ToolInvoke`. */
    val completedCallIds: scala.collection.mutable.Set[CallId] =
      scala.collection.mutable.Set.empty
  }

  private def translate(event: ProviderEvent,
                        sigil: Sigil,
                        request: ConversationRequest,
                        conversation: Conversation,
                        toolsByName: Map[String, Tool],
                        state: State): Stream[Signal] = {
    val caller = request.chain.lastOption.getOrElse {
      throw new IllegalStateException("ProviderRequest.chain is empty; orchestrator needs at least one participant.")
    }
    val convId = request.conversationId
    val topicId = request.currentTopic.id
    // Resolve once per turn — cheap registry lookup, used at every
    // Message-creation site below so clients render a UI-friendly
    // label without maintaining their own model cache.
    val modelDisplayName: Option[String] =
      sigil.cache.findTolerant(request.modelId).flatMap(_.displayName)

    event match {
      case ProviderEvent.ToolCallStart(callId, toolName) =>
        // Sigil bug #204 — defer ToolInvoke emission to
        // ToolCallComplete so the event carries parsed `input` at
        // emission time. `ProviderEvent.ToolCallStart` only delivers
        // `callId` + `toolName`; the args stream as
        // `ContentBlockDelta` chunks and the parsed `ToolInput` is
        // only available when `ToolCallComplete` lands at the end of
        // that window. Consumers that read `ToolInvoke.input` from
        // the wire previously saw `None` for the entire streaming
        // window — for reasoning models that think for tens of
        // seconds before the tool_call lands, the chip stayed at
        // "input pending" for the full duration. Deferring lands the
        // populated event on first emission. We still mint the
        // invokeId here and stash it in `activeCalls` so the
        // `ToolCallComplete` handler can correlate, and we still
        // flip `sawAnyToolCall` for the Done handler's drift check.
        // Stream-abort paths (`settleOrphanToolInvoke`) synthesize a
        // ToolInvoke for active calls that never reached Complete,
        // preserving the corruption-resistance invariant.
        val invokeId = Event.id()
        state.activeCalls(callId) = ActiveCall(toolName, invokeId)
        // Preserve a thinking-reserved `activeMessageId` so the atomic
        // tool can adopt it via `TurnContext.currentMessageId`,
        // ensuring `ThinkingChunk.target` matches the eventual settled
        // Message id even on tool-call-only respond paths. A streaming
        // Message that was already born (`activeMessageCreated`) DOES
        // get cleared — the tool call boundary opens a new tool
        // dispatch and the prior streaming Message is settled elsewhere.
        if (state.activeMessageCreated) {
          state.activeMessageId = None
          state.activeMessageCreated = false
        }
        state.currentKind = None
        state.currentArg = None
        state.sawAnyToolCall = true
        Stream.empty

      case ProviderEvent.ContentBlockStart(_, blockType, arg) =>
        // Close the previous block if one was open, then start tracking the new kind.
        val closeSignals = closeCurrentBlock(state, convId)
        state.currentKind = Some(kindOf(blockType))
        state.currentArg = arg
        Stream.emits(closeSignals)

      case ProviderEvent.ContentBlockDelta(_, text) =>
        val kind = state.currentKind.getOrElse(ContentKind.Text)
        state.currentBuffer.append(text)
        state.turnBuffer.append(text)
        // Emit the Message on the first content delta — `activeMessageId`
        // may already be set by a prior `ThinkingDelta` (which reserves
        // the id so `ThinkingChunk.target` matches the settled Message
        // without "birthing" the Message), so the flag — not id presence
        // — gates Message creation.
        val (createMessageSignal, msgId) = if (state.activeMessageCreated) {
          (None, state.activeMessageId.getOrElse {
            val id = Event.id()
            state.activeMessageId = Some(id)
            id
          })
        } else {
          val id = state.activeMessageId.getOrElse(Event.id())
          state.activeMessageId = Some(id)
          state.activeMessageCreated = true
          state.lastUserVisibleMessageId = Some(id)
          val msg = Message(
            participantId = caller,
            conversationId = convId,
            topicId = topicId,
            content = Vector.empty,
            modelId = Some(request.modelId),
            modelDisplayName = modelDisplayName,
            _id = id,
            state = EventState.Active
          )
          (Some(msg), id)
        }
        val delta = MessageDelta(
          target = msgId,
          conversationId = convId,
          content = Some(MessageContentDelta(kind = kind, arg = state.currentArg, complete = false, delta = text))
        )
        Stream.emits(createMessageSignal.toList ::: List(delta))

      case ProviderEvent.ToolCallComplete(callId, input) if state.completedCallIds.contains(callId) =>
        // Safety net for unknown future provider variance. The known
        // split-finish case (OpenRouter+Chutes+Kimi per #228) is now
        // suppressed at the wire-decoder layer where the context is
        // rich enough to recognise the usage-followup shape, so a
        // duplicate reaching the orchestrator today indicates either
        // a real Sigil bug or a new provider quirk worth surfacing —
        // but at debug level, not warn, so an operator investigating
        // can opt in via `scribe.Level.Debug` without normal runs
        // logging noise on every OpenRouter+Kimi tool call.
        scribe.debug(
          s"Duplicate ToolCallComplete(callId=${callId.value}) reached the orchestrator. " +
            "Ignoring the second one; the first dispatched and settled normally."
        )
        Stream.empty

      case ProviderEvent.ToolCallComplete(callId, input) =>
        state.completedCallIds += callId
        // Sigil bug #176 — some OpenAI-compat backends (observed:
        // OpenRouter passing through Kimi-K2.5, also kindred to
        // bug #163's DeepInfra streaming variance) ship a tool-call
        // shape that doesn't trigger `ToolCallStart` upstream — either
        // the leading `id`+`name` chunk is missing, or its keys arrive
        // in a shape the accumulator's predicate doesn't recognize.
        // The previous IllegalStateException tore down the whole agent
        // loop on the first such request. Recover by synthesizing the
        // ActiveCall + ToolInvoke event in-line: the typed `input`
        // carries enough info — its runtime class maps deterministically
        // to one of `request.tools` (each tool's input type is unique
        // by construction), and we mint a fresh invokeId here.
        // Sigil bug #176 — some OpenAI-compat backends (observed:
        // OpenRouter passing through Kimi-K2.5, also kindred to
        // bug #163's DeepInfra streaming variance) ship a tool-call
        // shape that doesn't trigger `ToolCallStart` upstream — either
        // the leading `id`+`name` chunk is missing, or its keys arrive
        // in a shape the accumulator's predicate doesn't recognize.
        // The previous IllegalStateException tore down the whole agent
        // loop on the first such request. Recover by populating
        // activeCalls with a fresh invokeId here; the deferred
        // ToolInvoke emission below uses `active.toolName` directly,
        // so the orphan flow ends up emitting the same shape as the
        // normal path.
        if (!state.activeCalls.contains(callId)) {
          val toolName = request.tools.iterator.collectFirst {
            case t if t.inputRW.definition.className.contains(input.getClass.getName) => t.schema.name.value
          }.getOrElse("(unknown)")
          val invokeId = Event.id()
          state.activeCalls(callId) = ActiveCall(toolName, invokeId)
          state.sawAnyToolCall = true
          scribe.warn(
            s"Synthesizing ActiveCall entry for orphan ToolCallComplete(callId=${callId.value}, " +
              s"tool=$toolName) — provider didn't emit a recognizable ToolCallStart upstream. " +
              "See sigil bug #176."
          )
        }
        val active = state.activeCalls.remove(callId).getOrElse {
          // Synthesis above always populates activeCalls; this branch
          // only fires if the synthesized entry was somehow removed in
          // the same step. Keep the throw as a hard invariant for the
          // genuinely impossible case.
          throw new IllegalStateException(
            s"ToolCallComplete($callId) without a preceding ToolCallStart and synthesis failed. " +
              s"Active calls: [${state.activeCalls.keys.map(_.value).mkString(", ")}]."
          )
        }
        val invokeId = active.invokeId
        // Bug #69 — record this so a ProviderEvent.Error arriving
        // after settle has a parent to stamp on its error Message.
        state.lastSettledInvokeId = Some(invokeId)
        // Bug #56 — mark the settle delta `internal` for the respond
        // family so clients that filter chips by that flag stay
        // consistent across the call's lifecycle (Active invoke,
        // Complete settle). The flag isn't load-bearing for any
        // framework-internal logic — it's a hint for client UIs.
        val isInternal = Orchestrator.UserVisibleTerminalTools.contains(active.toolName)
        // Sigil bug #204 — emit the deferred ToolInvoke NOW with
        // parsed `input` populated, then the settle delta.
        val deferredInvoke: Signal = ToolInvoke(
          toolName       = ToolName(active.toolName),
          participantId  = caller,
          conversationId = convId,
          topicId        = topicId,
          _id            = invokeId,
          state          = EventState.Active,
          internal       = isInternal,
          input          = Some(input),
          callId         = Some(callId.value),
          modelId        = Some(request.modelId)
        )
        val toolDeltaPrefix: List[Signal] = List(deferredInvoke, ToolDelta(
          target = invokeId,
          conversationId = convId,
          input = Some(input),
          state = Some(EventState.Complete),
          internal = isInternal
        ))
        // Local def so `return` statements inside the streaming /
        // atomic branches (refusal challenge, repeated-query intercept)
        // return from THIS def rather than from `translate`. That
        // preserves the synthesis-prepend at the bottom of this case.
        def toolCallCompleteInner(): Stream[Signal] = (if (state.activeMessageCreated) state.activeMessageId else None) match {
          case Some(msgId) =>
            // Streaming path — respond's content streamed live as
            // ContentBlockDeltas. Close the open block, parse the
            // assembled content as markdown, replace Message.content
            // with the parsed blocks, then run topic resolution and
            // settle. Use `active.toolName` (the local just removed
            // from activeCalls) rather than state.activeToolName,
            // which now reads the most-recent-remaining call after
            // the remove above.
            val closeBlock = closeCurrentBlock(state, convId)
            (Some(active.toolName), input) match {
              case (Some("respond"), r: RespondInput) =>
                val sanitized = XmlToolCallSanitizer.sanitize(r.content)
                if (sanitized.leakedSpans.nonEmpty) {
                  sigil.publish(XmlToolCallLeak(
                    conversationId     = convId,
                    modelId            = Some(request.modelId),
                    leakedSpanCount    = sanitized.leakedSpans.size,
                    firstLeakedExcerpt = sanitized.leakedSpans.head.take(200)
                  )).handleError(_ => rapid.Task.unit).startUnit()
                }
                val parsed = MarkdownContentParser.parse(sanitized.content)
                val settle = MessageDelta(
                  target = msgId,
                  conversationId = convId,
                  contentReplacement = Some(parsed),
                  state = Some(EventState.Complete)
                )
                // Topic resolution + keyword update used to live inline
                // here. Lifted to `Sigil.resolveTopicShift` /
                // `Sigil.updateConversationKeywords` so the atomic-
                // respond path (via `RespondTool.executeTyped`) fires
                // the same logic. We still emit from the streaming
                // branch — the streaming Message is being settled by
                // `MessageDelta` rather than created by the tool's
                // stream, so the tool's body never runs.
                val userMessage = request.turnInput.frames.reverseIterator.collectFirst {
                  case t: ContextFrame.Text if t.participantId != caller => t.content
                }.getOrElse("")
                Stream.force(
                  for {
                    topicEvents <- sigil.resolveTopicShift(
                      proposedLabel   = r.topicLabel,
                      proposedSummary = r.topicSummary,
                      caller          = caller,
                      conversation    = conversation,
                      currentTopic    = request.currentTopic,
                      previousTopics  = request.previousTopics,
                      modelId         = request.modelId,
                      chain           = request.chain,
                      userMessage     = userMessage
                    )
                    _ <- sigil.updateConversationKeywords(convId, r.keywords)
                  } yield {
                    val prelude: List[Signal] = topicEvents.flatMap { tc =>
                      List[Signal](
                        tc,
                        StateDelta(target = tc._id, conversationId = tc.conversationId, state = EventState.Complete)
                      )
                    }
                    Stream.emits(prelude ::: closeBlock ::: toolDeltaPrefix ::: List[Signal](settle))
                  }
                )
              case _ =>
                val settle = MessageDelta(
                  target = msgId,
                  conversationId = convId,
                  state = Some(EventState.Complete)
                )
                Stream.emits(closeBlock ::: toolDeltaPrefix ::: List[Signal](settle))
            }
          case None =>
            // Atomic path — run execute and forward resulting Events.
            //
            // Bug #126 — refusal-challenge intercept for atomic
            // `respond` calls. If the content reads as a refusal AND
            // no `find_capability` was called since the last
            // user-authored Message AND we haven't already
            // challenged this turn, suppress `executeAtomic` (the
            // respond never publishes) and emit a synthetic
            // `_refusal_challenge` ToolInvoke + Tool-role `Failure`
            // paired to it. The Failure becomes a trigger for the
            // next agent iteration; the agent re-runs with explicit
            // instructions to consult the catalog before refusing.
            //
            // Loop safety: `refusalChallengeOutcome` returns `None`
            // once a prior `_refusal_challenge` is already on the
            // conversation tail since the last user msg, so the
            // framework challenges at most once per user turn — if
            // the agent re-emits a refusal after the reminder, the
            // refusal passes through (that IS the answer).
            def proceedWithAtomicDispatch(): Stream[Signal] = {
              val toolName = active.toolName
              // Hard cap on identical (toolName + canonical args)
              // dispatches across the recent-invocations window. When
              // the projection already holds N-1 entries matching this
              // call AND `Sigil.maxIdenticalToolCallsInWindow > 0`,
              // the orchestrator REFUSES dispatch and emits a Tool-
              // role Failure paired to the originating ToolInvoke. The
              // prompt-level warning is advisory; the cap is the
              // backstop when the agent ignores it.
              val identicalLimit = sigil.maxIdenticalToolCallsInWindow
              if (identicalLimit > 0) {
                val canonicalHash = _root_.sigil.tool.ToolInputCanonicalizer.argsHash(input)
                val priorIdentical = request.turnInput
                  .projectionFor(caller)
                  .recentToolInvocations
                  .count(inv => inv.toolName.value == toolName && inv.argsHash == canonicalHash)
                if (priorIdentical >= identicalLimit - 1) {
                  val attemptedCount = priorIdentical + 1
                  val preview = _root_.sigil.tool.ToolInputCanonicalizer.argsPreview(input)
                  val previewText = if (preview.nonEmpty) s" `$preview`" else ""
                  val body =
                    s"Refused to dispatch `$toolName` -- you have already called this tool with " +
                      s"these exact args $priorIdentical times in the recent window (this would " +
                      s"be call #$attemptedCount).$previewText The result will not change. Try a " +
                      "different approach: narrow the pattern, paginate via `next_page`, switch " +
                      "to a different tool, or ask the user for clarification."
                  val capMsg = Message(
                    participantId  = caller,
                    conversationId = convId,
                    topicId        = topicId,
                    role           = MessageRole.Tool,
                    content        = Vector(ResponseContent.Text(body)),
                    state          = EventState.Complete,
                    disposition    = MessageDisposition.Failure(recoverable = true),
                    visibility     = MessageVisibility.Agents,
                    origin         = Some(invokeId)
                  )
                  return Stream.emits(toolDeltaPrefix ::: List[Signal](
                    capMsg,
                    StateDelta(target = capMsg._id, conversationId = convId, state = EventState.Complete)
                  ))
                }
              }
              // Bug #87 — dedupe identical (toolName + canonical args
              // JSON) calls within the same completion. When the model
              // emits N parallel function_calls that share a key
              // (parallel hedging, or the same retry hitting both a
              // generic and specific path), execute the work once and
              // route the duplicates to a synthesized Tool-role
              // pointer Message paired to their own ToolInvoke. Wire
              // shape stays well-formed; the underlying tool runs once.
              val argsKey = canonicalArgsKey(toolName, input)
              state.dispatchedKeys.get(argsKey) match {
                case Some(firstInvokeId) =>
                  // Inline the original call's result content into the
                  // duplicate's paired Tool-role Message so the agent's
                  // frame trail carries the same result the original
                  // ToolInvoke produced — wire pairing is satisfied for
                  // both call_ids without introducing a separate
                  // framework-state directive.
                  //
                  // When the original tool emitted no result content
                  // (atomic side-effect tools like `change_mode` that
                  // emit only their typed event), the inlined content
                  // is empty. Empty paired content reads to the agent
                  // as "tool ran, no output" — the same neutral signal
                  // the original ToolInvoke produced. NEVER fall back
                  // to a prose directive like "this is a duplicate":
                  // such text was framework state masquerading as a
                  // tool result and poisoned the agent's next-turn
                  // reasoning (sigil bug #189).
                  val inlinedContent: Vector[ResponseContent] =
                    state.dispatchedResultContent.getOrElse(firstInvokeId, Vector.empty)
                  val dupeMsg = Message(
                    participantId  = caller,
                    conversationId = convId,
                    topicId        = topicId,
                    role           = MessageRole.Tool,
                    content        = inlinedContent,
                    state          = EventState.Complete,
                    visibility     = MessageVisibility.Agents,
                    origin         = Some(invokeId)
                  )
                  return Stream.emits(toolDeltaPrefix ::: List[Signal](
                    dupeMsg,
                    StateDelta(target = dupeMsg._id, conversationId = convId, state = EventState.Complete)
                  ))
                case None =>
                  state.dispatchedKeys(argsKey) = invokeId
              }
              //
              // Bug #49 — wrap stream construction in `Task(...).handleError`
              // so a tool that throws during `tool.execute` (synchronously,
              // at stream construction OR on first pull) surfaces as a
              // failure Message instead of tearing down the surrounding
              // stream. Without the wrap, the throw propagated through
              // `++ executed` and dropped BOTH the toolDelta AND any
              // ToolResults — agent saw the user's tool chip stuck at
              // "input pending" with no follow-up. `Stream.force` defers
              // stream materialization to Task evaluation, where
              // `handleError` can catch and substitute.
              val tool = toolsByName.get(active.toolName)
              val executed: Stream[Signal] = tool match {
                case Some(t) =>
                  Stream.force(
                    Task(executeAtomic(t, input, TurnContext(
                      sigil = sigil,
                      chain = request.chain,
                      conversation = conversation,
                      turnInput = request.turnInput,
                      // Bug #7 — stamp the dispatching tool's invoke id +
                      // name so `TurnContext.reportProgress` can publish
                      // ToolProgress pulses without the tool author
                      // having to thread the correlation id manually.
                      currentToolInvokeId = Some(invokeId),
                      currentToolName     = Some(t.name),
                      // Bug #55 — atomic content tools (respond,
                      // respond_options, …) stamp `Message.modelId` from
                      // here so per-message metadata strips show which
                      // model produced the response. Without this, agent
                      // Messages from tool-call-only models (llama.cpp's
                      // grammar-constrained respond invocations) carried
                      // `modelId = None` because no streaming
                      // ContentBlockDelta path fired to attach it.
                      modelId             = Some(request.modelId),
                      // Thread the thinking-reserved Message id (if a
                      // prior `ThinkingDelta` allocated one) so atomic
                      // content tools can adopt it as `Message._id`,
                      // making `ThinkingChunk.target` match the eventual
                      // settled Message even on tool-call-only respond
                      // paths.
                      currentMessageId    = if (state.activeMessageCreated) None else state.activeMessageId
                    ), invokeId)).handleError { err =>
                      scribe.error(s"Atomic tool '$toolName' threw during execution", err)
                      Task.pure(Stream.emit[Signal](Message(
                        participantId  = caller,
                        conversationId = convId,
                        topicId        = topicId,
                        role           = MessageRole.Tool,
                        content        = Vector(ResponseContent.Text(
                          s"Tool '$toolName' execution failed: ${err.getClass.getSimpleName}: ${err.getMessage}"
                        )),
                        state          = EventState.Complete,
                        visibility     = MessageVisibility.Agents,
                        // Bug #69 — Tool-role events MUST carry origin.
                        origin         = Some(invokeId)
                      )))
                    }
                  )
                case None =>
                  // Bug #167 — model invoked a tool name not in this turn's
                  // roster (hallucination, or tool renamed/removed mid-flow).
                  // Without a Tool-role event paired to this invoke, the frame
                  // builder produces a ContextFrame.ToolCall with no matching
                  // ContextFrame.ToolResult, and OpenAI's Responses API 400s
                  // on the next request ("No tool output found for function
                  // call <id>"). Surface to the agent as a recoverable Tool-
                  // role Failure so the call_id stays paired and the agent
                  // self-corrects on its next iteration.
                  Stream.emit[Signal](Message(
                    participantId  = caller,
                    conversationId = convId,
                    topicId        = topicId,
                    role           = MessageRole.Tool,
                    content        = Vector(ResponseContent.Text(
                      s"Unknown tool: '${active.toolName}'. The framework didn't dispatch this " +
                        "call because the name isn't in this turn's available tool roster. " +
                        "Call find_capability to discover the catalog, or pick a tool from your visible roster."
                    )),
                    state          = EventState.Complete,
                    disposition    = MessageDisposition.Failure(recoverable = true),
                    visibility     = MessageVisibility.Agents,
                    origin         = Some(invokeId)
                  ))
              }
              // Bug #55 — record user-visible Message ids the atomic
              // tool emitted so the orchestrator's Usage handler has a
              // target when no streaming activeMessageId exists.
              val tracked = executed.evalMap { sig =>
                sig match {
                  case m: Message if m.role != MessageRole.Tool =>
                    Task { state.lastUserVisibleMessageId = Some(m._id); sig }
                  case m: Message if m.role == MessageRole.Tool && m.origin.contains(invokeId) =>
                    Task {
                      state.dispatchedResultContent(invokeId) = m.content
                      sig
                    }
                  case tr: _root_.sigil.event.ToolResults if tr.origin.contains(invokeId) =>
                    Task {
                      val rendered = tr.summary.orElse(tr.typed.map(j => fabric.io.JsonFormatter.Default(j))).getOrElse("")
                      if (rendered.nonEmpty)
                        state.dispatchedResultContent(invokeId) = Vector(ResponseContent.Text(rendered))
                      sig
                    }
                  case _ =>
                    Task.pure(sig)
                }
              }
              // Sigil bug #174 (durable fix) — guarantee every atomic-
              // content tool call (`respond`, `respond_options`, …) is
              // followed by a Tool-role completion event in the durable
              // event log. Without this, the conversation's frame trail
              // can end on an assistant Text frame (the respond's
              // user-visible Message), and chat templates that interpret
              // trailing-assistant as response prefill (Qwen3.6 with
              // `enable_thinking: true`) HTTP 400 the next call. Prior
              // to this fix, the wire layer synthesized an empty
              // function_call_output inline (Provider.renderInput) to
              // satisfy OpenAI's pairing requirement — that synthesis is
              // wire-side only and doesn't appear in subsequent contexts
              // as a trailing Tool frame.
              //
              // The synthetic Message:
              //   - `MessageRole.Tool` → produces ContextFrame.ToolResult
              //   - empty content → renders as empty function_call_output
              //   - `origin = invokeId` → satisfies #69 origin-stamp invariant
              //   - `Agents` visibility → never surfaces in user UIs
              //
              // Bug #167 — guarantee at least one result-shaped event
              // (`role == MessageRole.Tool`) is emitted for non-atomic-
              // content tools. Every other tool is expected to produce
              // a `Tool`-role Message or `ToolResults` event; if the
              // tool's executeTyped path returned a stream that
              // completed without emitting any result-shaped event
              // (silent-failure path that swallowed an error into
              // `Task.unit`, or a transform that filtered results out),
              // inject a synthetic Tool-role Failure so the wire's
              // function_call ↔ function_call_output pairing stays valid.
              val isAtomic = CoreTools.atomicContentToolNames.contains(ToolName(active.toolName))
              // Drain the tool's stream into a list, recovering from
              // mid-stream errors (Bug #49 / #190). The construction-time
              // `Task(executeAtomic(…)).handleError` above catches
              // synchronous throws WHILE building the stream value;
              // errors raised DURING stream evaluation (e.g.
              // `Stream.force(Task.error(…))` in the tool body, fiber
              // cancellation, a stream-internal HTTP read failure)
              // propagate past that wrap and would otherwise leave the
              // ToolInvoke without a paired result event. Capturing the
              // throwable here means the synth paths below always run
              // against a known-good `collected` list and can pair the
              // invoke even if the tool's stream blew up mid-pull.
              val drained: Task[(List[Signal], Option[Throwable])] =
                tracked.toList.map(events => (events, Option.empty[Throwable]))
                  .handleError { err =>
                    scribe.error(
                      s"orchestrator: tool '${active.toolName}' (invokeId=${invokeId.value}) " +
                        s"stream errored mid-dispatch (${err.getClass.getSimpleName}: ${err.getMessage}). " +
                        "Pairing the invoke with a typed Failure result so the agent's frame trail stays " +
                        "well-formed.",
                      err
                    )
                    Task.pure((List.empty[Signal], Some(err)))
                  }
              val guarded: Stream[Signal] =
                if (isAtomic) Stream.force(drained.map { case (collected, errOpt) =>
                  // If the tool already emitted a Tool-role event paired to
                  // this invoke (e.g. an app-specific atomic content tool
                  // that handles its own pairing), don't duplicate.
                  val hasResult = collected.exists {
                    case e: Event if e.role == MessageRole.Tool && e.origin.contains(invokeId) => true
                    case _ => false
                  }
                  if (hasResult) Stream.emits(collected)
                  else {
                    val (content, disposition) = errOpt match {
                      case None      => (Vector.empty[ResponseContent], MessageDisposition.Success)
                      case Some(err) =>
                        (Vector[ResponseContent](ResponseContent.Text(
                          s"Tool `${active.toolName}` failed during execution: " +
                            s"${err.getClass.getSimpleName}: ${Option(err.getMessage).getOrElse("(no message)")}"
                        )), MessageDisposition.Failure(recoverable = true))
                    }
                    val synth = Message(
                      participantId  = caller,
                      conversationId = convId,
                      topicId        = topicId,
                      role           = MessageRole.Tool,
                      content        = content,
                      state          = EventState.Complete,
                      disposition    = disposition,
                      visibility     = MessageVisibility.Agents,
                      origin         = Some(invokeId)
                    )
                    // Capture so a subsequent duplicate-call dispatch
                    // can inline this content rather than fall back to
                    // a generic prose directive. See sigil bug #189.
                    state.dispatchedResultContent(invokeId) = synth.content
                    Stream.emits(collected :+ synth)
                  }
                })
                else Stream.force(drained.map { case (collected, errOpt) =>
                  val hasResult = collected.exists {
                    case e: Event if e.role == MessageRole.Tool => true
                    case _                                       => false
                  }
                  if (hasResult) Stream.emits(collected)
                  else {
                    val argsText = canonicalArgsKey(active.toolName, input)
                      .stripPrefix(s"${active.toolName}:")
                      .take(200)
                    val contentText = errOpt match {
                      case None =>
                        scribe.warn(
                          s"orchestrator: tool '${active.toolName}' (invokeId=${invokeId.value}) completed " +
                            "without emitting a MessageRole.Tool event — likely a sync throw escaping its " +
                            "executeTyped handleError. Emitting a typed Failure result to keep the wire paired."
                        )
                        s"Tool `${active.toolName}` failed internally. Args: $argsText. " +
                          "Pick a different tool or refine the approach."
                      case Some(err) =>
                        s"Tool `${active.toolName}` failed during execution: " +
                          s"${err.getClass.getSimpleName}: ${Option(err.getMessage).getOrElse("(no message)")}. " +
                          s"Args: $argsText. Pick a different tool or refine the approach."
                    }
                    Stream.emits(collected :+ Message(
                      participantId  = caller,
                      conversationId = convId,
                      topicId        = topicId,
                      role           = MessageRole.Tool,
                      content        = Vector(ResponseContent.Text(contentText)),
                      state          = EventState.Complete,
                      disposition    = MessageDisposition.Failure(recoverable = errOpt.isDefined),
                      visibility     = MessageVisibility.Agents,
                      origin         = Some(invokeId)
                    ))
                  }
                })
              Stream.emits(toolDeltaPrefix) ++ guarded
            }

            if (active.toolName == "respond") {
              input match {
                case r: RespondInput =>
                  // Failure-disposition responses are explicit
                  // structured signals; they bypass the refusal
                  // challenge entirely. Success-disposition replies
                  // run through the detector against their markdown
                  // content.
                  r.disposition match {
                    case _root_.sigil.tool.model.ResponseDisposition.Failure =>
                      // Explicit failure — agent has decided, no
                      // challenge.
                    case _root_.sigil.tool.model.ResponseDisposition.Success =>
                      if (r.content.nonEmpty) {
                        return Stream.force(refusalChallengeOutcome(sigil, r.content, convId, caller, topicId).map {
                          case Some(challengeSignals) =>
                            Stream.emits(toolDeltaPrefix ::: challengeSignals)
                          case None =>
                            proceedWithAtomicDispatch()
                        })
                      }
                  }
                case _ =>
              }
            }
            if (active.toolName == "find_capability") {
              input match {
                case fc: FindCapabilityInput =>
                  // Bug #159 — repeated-query intercept. When the
                  // agent has already invoked `find_capability` with
                  // identical (normalized) keywords earlier in the
                  // same user turn, the ranker is deterministic so a
                  // second call will return the same hits. Replace
                  // the duplicate execution with a guidance Failure
                  // directing the agent to refine the query or pick
                  // a different result from the prior hits, before
                  // it burns the turn looping until
                  // `maxAgentIterations`.
                  return Stream.force(repeatedQueryOutcome(sigil, fc.keywords, convId, caller, topicId).map {
                    case Some(interceptSignals) =>
                      Stream.emits(toolDeltaPrefix ::: interceptSignals)
                    case None =>
                      proceedWithAtomicDispatch()
                  })
                case _ =>
              }
            }
            proceedWithAtomicDispatch()
        }
        toolCallCompleteInner()

      case ProviderEvent.Usage(usage) =>
        // Bug #55 — fall back to the last user-visible Message id when
        // no streaming activeMessageId exists. Tool-call-only models
        // (llama.cpp's grammar-constrained respond invocations) build
        // the agent's user-visible Message inside the tool's
        // `executeTyped`, so the streaming-text path never fires; this
        // fallback lets per-turn token usage land on that Message.
        // Use `activeMessageId` only when a Message has actually been
        // emitted (`activeMessageCreated`); otherwise the id may be
        // a thinking-reserved placeholder for which no Message exists
        // yet, and the usage would land nowhere.
        val streamingTarget = if (state.activeMessageCreated) state.activeMessageId else None
        streamingTarget.orElse(state.lastUserVisibleMessageId) match {
          case Some(msgId) =>
            Stream.emits(List(MessageDelta(target = msgId, conversationId = convId, usage = Some(usage))))
          case None if state.sawAnyToolCall =>
            // The turn ran tool calls but none of them produced a
            // user-visible Message (e.g. change_mode, find_capability,
            // a side-effect-only tool). Fold the usage onto the most
            // recently settled ToolInvoke via ToolDelta so cost
            // projection has a durable record carrying both `modelId`
            // (stamped at invoke creation) and `usage`. MessageDelta
            // can't do this — its `apply` is a no-op on non-Message
            // events, so the usage data was silently dropped.
            state.lastSettledInvokeId match {
              case Some(invokeId) =>
                Stream.emits(List(ToolDelta(target = invokeId, conversationId = convId, usage = Some(usage))))
              case None => Stream.empty
            }
          case None =>
            // Truly silent turn — no Message and no tool call. The
            // agent loop's silent-turn recovery (forced respond-family
            // iteration) re-enters the loop; the usage from THIS
            // turn is dropped since no Message exists to carry it.
            // The forced iteration will emit a Message that carries
            // its own usage.
            Stream.empty
        }

      case ProviderEvent.TextDelta(text) =>
        // Bug #75 — accumulate plain-text fragments so the Done
        // handler can produce a diagnostic if the model never
        // wrapped them in a tool call. Don't emit anything here —
        // the diagnostic is post-stream.
        state.plainTextBuffer.append(text)
        Stream.empty
      case ProviderEvent.ThinkingDelta(text) =>
        // Forward reasoning text to consumers as a transient
        // `ThinkingChunk` Notice so live UIs can render a tail of the
        // agent's pre-content thinking. Lazily allocates the
        // placeholder `activeMessageId` on first chunk and reuses it
        // across subsequent chunks (and across the eventual user-
        // visible Message creation in `ContentBlockDelta`); the
        // ThinkingChunk's `target` therefore matches the id the
        // settled Message will carry, letting consumers fuse the
        // thinking-tail UI with the final message bubble.
        // Crucially, no `Message.create` fires here — the Message
        // isn't "born" until the first user-visible content delta
        // lands; reasoning alone never materializes a Message.
        val msgId = state.activeMessageId match {
          case Some(id) => id
          case None =>
            val id = Event.id()
            state.activeMessageId = Some(id)
            id
        }
        Stream.emit(ThinkingChunk(target = msgId, conversationId = convId, delta = text))
      case ProviderEvent.ServerToolStart(_, _, _)         => Stream.empty
      case ProviderEvent.ServerToolComplete(_, _)         => Stream.empty

      case ProviderEvent.ResponseStateCaptured(maybeId, messageCount) =>
        // Persist the provider's server-side state handle on the
        // agent's projection. The next turn reads it back as
        // `ProviderCall.previousResponseId` + `priorMessageCount`,
        // chains via `previous_response_id`, and ships only the delta.
        // `None` here means the provider invalidated the cache —
        // typically `previous_response_not_found` on an expired id.
        val persist: Task[Unit] = maybeId match {
          case Some(id) => sigil.setProviderResponseState(convId, caller, id, messageCount)
          case None     => sigil.clearProviderResponseState(convId, caller)
        }
        Stream.force(persist.handleError(_ => Task.unit).map(_ => Stream.empty[Signal]))

      case ProviderEvent.ReasoningItem(providerItemId, summary, encryptedContent) =>
        // Bug #61 — persist the provider's reasoning state so subsequent
        // turns can replay it. Visibility scoped to the originating
        // agent (`Participants(Set(caller))`) — wire delivery to the
        // human user UI drops it via `Sigil.canSee`; the agent's own
        // `buildContext` keeps it for prompt-rebuild. The Reasoning
        // event flows through the standard publish pipeline; the
        // OpenAIProvider's `renderInput` later picks the corresponding
        // `ContextFrame.Reasoning` out of the rendered messages and
        // emits it back onto the wire in its original position.
        Stream.emits(List[Signal](Reasoning(
          providerItemId   = providerItemId,
          summary          = summary,
          encryptedContent = encryptedContent,
          participantId    = caller,
          conversationId   = convId,
          topicId          = topicId,
          visibility       = MessageVisibility.Participants(Set(caller))
        )))

      case ProviderEvent.ImageGenerationPartial(callId, imageUrl) =>
        // First partial creates an Active Message keyed by callId; subsequent
        // partials emit ImageDelta updates so the same Message progressively
        // shows better previews until ImageGenerationComplete settles it.
        parseImageUrl(imageUrl) match {
          case None => Stream.empty
          case Some(url) =>
            state.imageMessageIds.get(callId.value) match {
              case Some(messageId) =>
                Stream.emits(List(ImageDelta(
                  target = messageId,
                  conversationId = convId,
                  url = url
                )))
              case None =>
                val message = Message(
                  participantId = caller,
                  conversationId = convId,
                  topicId = topicId,
                  content = Vector(ResponseContent.Image(url = url)),
                  modelId = Some(request.modelId),
                  modelDisplayName = modelDisplayName,
                  state = EventState.Active
                )
                state.imageMessageIds = state.imageMessageIds + (callId.value -> message._id)
                Stream.emits(List[Signal](message))
            }
        }

      case ProviderEvent.ImageGenerationComplete(callId, imageUrl) =>
        // Settle the streaming Message (created on the first partial). When
        // there were no partials — built-in tool path or non-streaming —
        // synthesize a fresh Complete Message carrying the image.
        parseImageUrl(imageUrl) match {
          case None => Stream.empty
          case Some(url) =>
            state.imageMessageIds.get(callId.value) match {
              case Some(messageId) =>
                state.imageMessageIds = state.imageMessageIds - callId.value
                Stream.emits(List[Signal](
                  ImageDelta(target = messageId, conversationId = convId, url = url),
                  StateDelta(target = messageId, conversationId = convId, state = EventState.Complete)
                ))
              case None =>
                Stream.emits(List(
                  Message(
                    participantId = caller,
                    conversationId = convId,
                    topicId = topicId,
                    content = Vector(ResponseContent.Image(url = url)),
                    modelId = Some(request.modelId),
                    modelDisplayName = modelDisplayName,
                    state = EventState.Complete
                  )
                ))
            }
        }

      case ProviderEvent.Done(stopReason)                 =>
        // Settle any in-flight tool call before terminating. If the
        // provider stream ends between `ToolCallStart` and
        // `ToolCallComplete` (token-budget cutoff, network drop, mid-args
        // 5xx), the unsettled `ToolInvoke` would otherwise stay
        // `state=Active` in the events store forever, and clients reading
        // that state believe the agent is still working.
        //
        // Snapshot the orphaned calls BEFORE settleOrphanToolInvoke
        // clears them — sigil bug #123's MaxTokens-during-tool-args
        // diagnostic needs to know which tool was being streamed so
        // the paired Failure message can name it. Without this snapshot
        // the diagnostic loses the tool name (settleOrphan clears
        // activeCalls) and the frame renderer surfaces the misleading
        // "tool's executeTyped — please report it" framework error
        // instead.
        val orphanedCalls = state.activeCalls.values.toList
        val reasonFor: ActiveCall => String = stopReason match {
          case StopReason.MaxTokens =>
            active =>
              s"Your `${active.toolName}` call was truncated at max_tokens — the arguments " +
                "never fully arrived, so the tool didn't run. Reduce argument size (e.g. don't " +
                "inline whole files into a `text:` field — read the file separately), split the " +
                "work across multiple smaller calls, or request a larger max_tokens for this turn."
          case _ =>
            active => s"Tool `${active.toolName}` did not produce a result before the stream ended."
        }
        val orphanRecoverable = stopReason == StopReason.MaxTokens
        val closeOrphan = settleOrphanToolInvoke(state, convId, caller, topicId, reasonFor = reasonFor, recoverable = orphanRecoverable)
        val truncationDiagnostic: List[Signal] = Nil
        // Detect token-level repetition loops — the model hit
        // max_tokens AND the accumulated text is dominated by a
        // single repeated sentence. Surface as a Failure-block
        // Tool-role Message so the next agent iteration sees the
        // diagnostic and self-corrects rather than reading the
        // 200k-char tail back into the prompt.
        val degenerateDiagnostic: List[Signal] = stopReason match {
          case StopReason.MaxTokens =>
            val text = state.turnBuffer.toString
            _root_.sigil.provider.DegenerateContentDetector.Default.detect(text) match {
              case Some(hit) =>
                scribe.warn(s"orchestrator: degenerate generation detected (${hit.occurrences}/${hit.totalSentences} sentences " +
                  s"= ${math.round(hit.share * 100)}% repetition) in conversation $convId — emitting Failure diagnostic")
                val syntheticInvokeId = Event.id()
                val syntheticInvoke = ToolInvoke(
                  toolName       = ToolName("_degenerate_generation"),
                  participantId  = caller,
                  conversationId = convId,
                  topicId        = topicId,
                  _id            = syntheticInvokeId,
                  state          = EventState.Complete,
                  internal       = true
                )
                val diagnostic = Message(
                  participantId  = caller,
                  conversationId = convId,
                  topicId        = topicId,
                  role           = MessageRole.Tool,
                  content        = Vector(ResponseContent.Text(hit.renderDiagnostic(text.length))),
                  disposition    = MessageDisposition.Failure(recoverable = true),
                  state          = EventState.Complete,
                  visibility     = MessageVisibility.Agents,
                  origin         = Some(syntheticInvokeId)
                )
                List[Signal](syntheticInvoke, diagnostic)
              case None => Nil
            }
          case _ => Nil
        }
        // Bug #149 — memory extraction used to fire per-iteration
        // here. Lifted to `Sigil.runAgentLoop`'s `terminate()`
        // boundary so it fires exactly once per user turn instead
        // of once per agent-loop iteration. The orchestrator's
        // turn-scoped buffer is unreliable for this anyway: each
        // Orchestrator.process call has its own State, so a
        // multi-iteration turn produced N extractions over
        // overlapping content.
        // Bug #75 — if the model emitted plain text without any tool
        // call this turn, the framework was silently dropping the
        // text and bug-#46's placeholder fired post-loop with no
        // feedback to the model. Now the orchestrator surfaces the
        // drop as a tool-call-shaped diagnostic the agent reads on
        // its next iteration — structurally identical to a script
        // compile error / arg-parse error / validator failure: a
        // Tool-role Message carrying
        // `MessageDisposition.Failure(reason, recoverable = true)`,
        // paired with a synthetic ToolInvoke representing the
        // "attempted reply via plain text" that the framework
        // rejected.
        //
        // The Tool-role tag is what makes the diagnostic re-trigger
        // the agent's loop within the current claim
        // (`TriggerFilter.isTriggerFor` always re-fires on Tool
        // role, even from self). The synthetic ToolInvoke gives the
        // Message a parent for bug #69's origin invariant. Both are
        // marked `internal = true` so client UIs can filter them
        // out — this is framework-internal model-correction noise,
        // not user-facing content. Visibility = Agents keeps the
        // diagnostic out of the user-facing wire stream regardless.
        val plainTextDiagnostic: List[Signal] =
          if (state.plainTextBuffer.nonEmpty && !state.sawAnyToolCall) {
            val droppedText = state.plainTextBuffer.toString
            val snippet = if (droppedText.length <= 200) droppedText else droppedText.take(200) + "…"
            val reason =
              "Your previous reply was plain text and was dropped — every reply must be a " +
                "tool call. Wrap your answer in one of the respond-family tools " +
                "(`respond`, `respond_options`, `respond_field`, `respond_failure`, " +
                "`no_response`) appropriate to your situation. When a tool result IS the " +
                s"user-facing answer, call `respond` with that content. Dropped text was: $snippet"
            val syntheticInvokeId = Event.id()
            val syntheticInvoke = ToolInvoke(
              toolName       = ToolName("_plain_text_reply"),
              participantId  = caller,
              conversationId = convId,
              topicId        = topicId,
              _id            = syntheticInvokeId,
              state          = EventState.Complete,
              internal       = true
            )
            val diagnosticMessage = Message(
              participantId  = caller,
              conversationId = convId,
              topicId        = topicId,
              role           = MessageRole.Tool,
              content        = Vector(ResponseContent.Text(reason)),
              disposition    = MessageDisposition.Failure(recoverable = true),
              state          = EventState.Complete,
              visibility     = MessageVisibility.Agents,
              origin         = Some(syntheticInvokeId)
            )
            List[Signal](syntheticInvoke, diagnosticMessage)
          } else Nil
        Stream.emits(closeOrphan ++ truncationDiagnostic ++ plainTextDiagnostic ++ degenerateDiagnostic)
      case ProviderEvent.Error(msg)                       =>
        // Bug #50 — surface the provider/validator failure as a
        // Tool-role Message so the agent's next iteration sees a
        // concrete error and can retry with corrected args. Without
        // this the orphan settle dropped the error string entirely
        // and the agent had no signal anything went wrong (turn
        // ended silent → bug-#46 placeholder fired → user saw a
        // dead-end chat). MessageVisibility.Agents because
        // validator/provider diagnostics are developer-y and
        // shouldn't leak to non-agent viewers; the trigger filter
        // still routes Tool-role messages to the agent.
        //
        // Bug #51 — pass the error through to the orphan-settle
        // ToolDelta so the user-visible chip can render
        // "(invalid args: …)" instead of "(input pending)".
        val orphanSettle = settleOrphanToolInvoke(state, convId, caller, topicId, error = Some(msg))
        val orphanMessageSettle = settleOrphanMessage(state, convId, error = Some(msg))
        // Bug #69 — Tool-role Message MUST have origin. Pair to the
        // active ToolInvoke if one is open (the typical case — the
        // provider's error came mid-tool-call); otherwise fall back
        // to None and accept that this branch hits the framework's
        // missing-origin throw downstream.
        //
        // Bug #67 — the "in practice unreachable" fallback IS
        // reachable: pre-flight `/apply-template` / `/tokenize` /
        // capacity-gate failures fire `ProviderEvent.Error` BEFORE
        // any tool call has been issued (and thus before
        // `activeToolInvokeId` / `lastSettledInvokeId` exist).
        // Bug #64's write-side validation correctly refused the
        // origin-less Tool-role Message, killing the iteration.
        // Synthesize a stub ToolInvoke so the agent sees the
        // error in its next iteration's context — same pattern
        // the plain-text-reply diagnostic above uses.
        val errorOrigin = state.activeToolInvokeId.orElse(state.lastSettledInvokeId)
        val (preludeSignals, originId) = errorOrigin match {
          case Some(parent) => (Nil, parent)
          case None =>
            val syntheticInvokeId = Event.id()
            val syntheticInvoke = ToolInvoke(
              toolName       = ToolName("_provider_error"),
              participantId  = caller,
              conversationId = convId,
              topicId        = topicId,
              _id            = syntheticInvokeId,
              state          = EventState.Complete,
              internal       = true
            )
            (List[Signal](syntheticInvoke), syntheticInvokeId)
        }
        val errorMessage = Message(
          participantId  = caller,
          conversationId = convId,
          topicId        = topicId,
          role           = MessageRole.Tool,
          content        = Vector(ResponseContent.Text(s"Provider error: $msg")),
          state          = EventState.Complete,
          visibility     = MessageVisibility.Agents,
          origin         = Some(originId)
        )
        Stream.emits(orphanSettle ++ orphanMessageSettle ++ preludeSignals :+ (errorMessage: Signal))
    }
  }

  /**
   * Emit a synthetic terminal `ToolDelta` for any in-flight
   * `ToolInvoke` so it lands at `state=Complete` instead of getting
   * stuck at `Active`. Idempotent — if no tool call is open the
   * returned list is empty. Clears `state.activeToolInvokeId` /
   * `state.activeToolName` either way.
   */
  /** Settle every in-flight `ToolInvoke` and pair each with a durable
    * Tool-role failure Message. The pairing keeps the conversation's
    * frame trail well-formed; without it, subsequent turns'
    * `renderInput` finds dangling ToolInvokes and falls into its
    * defensive synthesis path. `reasonFor` lets the caller customize
    * the failure text per orphan (e.g. the MaxTokens-truncation path
    * supplies a more actionable diagnosis); the default is a brief
    * generic phrasing. */
  private def settleOrphanToolInvoke(state: State,
                                     convId: lightdb.id.Id[Conversation],
                                     caller: ParticipantId,
                                     topicId: lightdb.id.Id[Topic],
                                     error: Option[String] = None,
                                     reasonFor: ActiveCall => String =
                                       a => s"Tool `${a.toolName}` did not produce a result",
                                     recoverable: Boolean = false): List[Signal] = {
    val signals = state.activeCalls.values.toList.flatMap { active =>
      val isInternal = Orchestrator.UserVisibleTerminalTools.contains(active.toolName)
      // Sigil bug #204 — ToolInvoke emission is deferred to
      // `ToolCallComplete` so the normal path can stamp `input`. For
      // active calls that never reached Complete (stream abort,
      // validator rejection mid-call), synthesize the ToolInvoke
      // here with `input = None` so the closeDelta and pairedFailure
      // have a real target to refer to — otherwise the corruption-
      // resistance invariant (#190) breaks: the closeDelta's target
      // would silently no-op against a non-existent event, and the
      // pairedFailure's `origin` would dangle.
      val synthInvoke: Signal = ToolInvoke(
        toolName       = ToolName(active.toolName),
        participantId  = caller,
        conversationId = convId,
        topicId        = topicId,
        _id            = active.invokeId,
        state          = EventState.Active,
        internal       = isInternal
      )
      val closeDelta: Signal = ToolDelta(
        target = active.invokeId,
        conversationId = convId,
        input = None,
        state = Some(EventState.Complete),
        // Bug #51 — when the orphan-settle is the result of a provider
        // failure (validator rejection, mid-call error), surface the
        // diagnostic on the delta so client chips can render
        // "(invalid args: …)" instead of the "(input pending)"
        // placeholder reserved for genuinely-mid-flight calls.
        error = error
      )
      val pairedFailure: Signal = Message(
        participantId  = caller,
        conversationId = convId,
        topicId        = topicId,
        role           = MessageRole.Tool,
        content        = Vector(ResponseContent.Text(reasonFor(active))),
        state          = EventState.Complete,
        disposition    = MessageDisposition.Failure(recoverable = recoverable),
        visibility     = MessageVisibility.Agents,
        origin         = Some(active.invokeId)
      )
      List(synthInvoke, closeDelta, pairedFailure)
    }
    state.activeCalls.clear()
    signals
  }

  /**
   * Sigil bug #171 — settle the in-flight streaming Message that was
   * started during respond-family `ContentBlockDelta` flow when the
   * tool call ultimately failed (parse error, mid-stream throw). Emits
   * a terminal `MessageDelta(state=Complete, disposition=Failure)` so
   * the chat bubble stops rendering as "agent is still typing" and
   * shows the failure inline. Idempotent — returns empty when no
   * Message was streamed. Always clears `state.activeMessageId`.
   */
  private def settleOrphanMessage(state: State,
                                  convId: lightdb.id.Id[Conversation],
                                  error: Option[String] = None): List[Signal] =
    // Only settle when a Message was actually emitted — a thinking-
    // reserved id without `activeMessageCreated` points at no event.
    state.activeMessageId.filter(_ => state.activeMessageCreated) match {
      case None =>
        state.activeMessageId = None
        state.activeMessageCreated = false
        Nil
      case Some(msgId) =>
        state.activeMessageId = None
        state.activeMessageCreated = false
        val reason = error.getOrElse("Tool call failed before settling")
        val delta: Signal = MessageDelta(
          target             = msgId,
          conversationId     = convId,
          contentReplacement = Some(Vector(ResponseContent.Text(
            s"Model output failed to produce a valid reply: $reason"
          ))),
          state              = Some(EventState.Complete),
          disposition        = Some(sigil.event.MessageDisposition.Failure(recoverable = true))
        )
        List(delta)
    }

  /** Bug #126 — decide whether an atomic `respond` should be
    * suppressed and replaced with a refusal-challenge diagnostic.
    *
    * Returns:
    *   - `Some(signals)` when the content reads as a refusal AND
    *     the agent didn't consult `find_capability` since the last
    *     user-authored Message AND we haven't already challenged
    *     this user turn. The signals are a synthetic
    *     `_refusal_challenge` ToolInvoke + a paired Tool-role
    *     `Failure` Message the agent reads on its next iteration.
    *   - `None` when the content isn't a refusal, when the agent
    *     DID call `find_capability` (an informed refusal is valid),
    *     or when a prior `_refusal_challenge` is already on the
    *     tail (loop-safety — challenge once, then step aside).
    *
    * Apps tune the refusal-detection itself via
    * [[sigil.Sigil.refusalDetector]] — e.g. apps where refusal is
    * a legitimate outcome plug in [[RefusalDetector.Never]] to
    * disable the intercept entirely.
    */
  private def refusalChallengeOutcome(sigil: Sigil,
                                      content: String,
                                      convId: lightdb.id.Id[Conversation],
                                      caller: ParticipantId,
                                      topicId: lightdb.id.Id[Topic]): Task[Option[List[Signal]]] = {
    if (!sigil.refusalDetector.isRefusal(content)) Task.pure(None)
    else sigil.withDB(_.events.transaction(_.list)).map { allEvents =>
      val convEvents = allEvents
        .filter(_.conversationId == convId)
        .sortBy(_.timestamp.value)
      // "Last user message" = most recent non-agent participantId on
      // a Message event. Agent-only conversations (delegated workers
      // with no human in the chain) skip the challenge — no user
      // intent to defend against.
      val lastUserIdx = convEvents.lastIndexWhere {
        case m: Message => !m.participantId.isInstanceOf[_root_.sigil.participant.AgentParticipantId]
        case _          => false
      }
      if (lastUserIdx < 0) None
      else {
        val tail = convEvents.drop(lastUserIdx + 1)
        val discoveryAttempted = tail.exists {
          case ti: ToolInvoke if ti.toolName.value == "find_capability" => true
          case _                                                        => false
        }
        val alreadyChallenged = tail.exists {
          case ti: ToolInvoke if ti.toolName.value == "_refusal_challenge" => true
          case _                                                           => false
        }
        if (discoveryAttempted || alreadyChallenged) None
        else Some(buildRefusalChallengeSignals(caller, convId, topicId))
      }
    }
  }

  /** Construct the (synthetic-invoke, Failure-message) pair the
    * orchestrator emits when [[refusalChallengeOutcome]] fires. The
    * invoke's `_refusal_challenge` name doubles as the marker
    * `refusalChallengeOutcome` walks for on subsequent iterations
    * to enforce the once-per-user-turn limit. */
  private def buildRefusalChallengeSignals(caller: ParticipantId,
                                           convId: lightdb.id.Id[Conversation],
                                           topicId: lightdb.id.Id[Topic]): List[Signal] = {
    val syntheticInvokeId = Event.id()
    val syntheticInvoke = ToolInvoke(
      toolName       = ToolName("_refusal_challenge"),
      participantId  = caller,
      conversationId = convId,
      topicId        = topicId,
      _id            = syntheticInvokeId,
      state          = EventState.Complete,
      internal       = true
    )
    val reason =
      "Your previous `respond` refused the user without first calling `find_capability` (see the " +
        "system prompt — a refusal not preceded by `find_capability` is a bug). The tool catalog " +
        "likely contains capabilities you haven't discovered. Call `find_capability` with keywords " +
        "describing what the user asked, review the matches, then decide whether to refuse based on " +
        "what discovery actually returns. If no relevant capability surfaces, refuse with the " +
        "specifics of what you searched and what wasn't there."
    val diagnostic = Message(
      participantId  = caller,
      conversationId = convId,
      topicId        = topicId,
      role           = MessageRole.Tool,
      content        = Vector(ResponseContent.Text(reason)),
      disposition    = MessageDisposition.Failure(recoverable = true),
      state          = EventState.Complete,
      visibility     = MessageVisibility.Agents,
      origin         = Some(syntheticInvokeId)
    )
    List[Signal](syntheticInvoke, diagnostic)
  }

  /** Bug #159 — decide whether a `find_capability` dispatch should be
    * suppressed because the agent already issued the same query
    * earlier in this user turn.
    *
    * Returns:
    *   - `Some(signals)` when a prior `find_capability` invoke since
    *     the last user-authored Message carries identical normalized
    *     keywords AND no `_repeated_query_intercept` marker is
    *     already on the tail. The signals are a synthetic
    *     `_repeated_query_intercept` ToolInvoke + a paired Tool-role
    *     `Failure` that tells the agent to refine the query or pick
    *     a different result from the previous hits.
    *   - `None` when no duplicate is on the tail, when the prior
    *     query had different keywords, when no prior `find_capability`
    *     exists this turn, or when a prior intercept is already
    *     present (once-per-turn limit — same shape as the refusal
    *     challenge's loop safety). */
  private def repeatedQueryOutcome(sigil: Sigil,
                                   keywords: String,
                                   convId: lightdb.id.Id[Conversation],
                                   caller: ParticipantId,
                                   topicId: lightdb.id.Id[Topic]): Task[Option[List[Signal]]] = {
    val normalized = Orchestrator.normalizeQuery(keywords)
    sigil.withDB(_.events.transaction(_.list)).map { allEvents =>
      val convEvents = allEvents
        .filter(_.conversationId == convId)
        .sortBy(_.timestamp.value)
      val lastUserIdx = convEvents.lastIndexWhere {
        case m: Message => !m.participantId.isInstanceOf[_root_.sigil.participant.AgentParticipantId]
        case _          => false
      }
      if (lastUserIdx < 0) None
      else {
        val tail = convEvents.drop(lastUserIdx + 1)
        val alreadyIntercepted = tail.exists {
          case ti: ToolInvoke if ti.toolName.value == "_repeated_query_intercept" => true
          case _                                                                  => false
        }
        if (alreadyIntercepted) None
        else {
          val priorMatch = tail.exists {
            case ti: ToolInvoke if ti.toolName.value == "find_capability" =>
              ti.input match {
                case Some(fc: FindCapabilityInput) =>
                  Orchestrator.normalizeQuery(fc.keywords) == normalized
                case _ => false
              }
            case _ => false
          }
          if (!priorMatch) None
          else Some(buildRepeatedQuerySignals(caller, convId, topicId, normalized))
        }
      }
    }
  }

  /** Construct the (synthetic-invoke, Failure-message) pair the
    * orchestrator emits when [[repeatedQueryOutcome]] fires. The
    * `_repeated_query_intercept` invoke name doubles as the marker
    * the detector walks for to enforce once-per-user-turn loop
    * safety. */
  private def buildRepeatedQuerySignals(caller: ParticipantId,
                                        convId: lightdb.id.Id[Conversation],
                                        topicId: lightdb.id.Id[Topic],
                                        normalizedKeywords: String): List[Signal] = {
    val syntheticInvokeId = Event.id()
    val syntheticInvoke = ToolInvoke(
      toolName       = ToolName("_repeated_query_intercept"),
      participantId  = caller,
      conversationId = convId,
      topicId        = topicId,
      _id            = syntheticInvokeId,
      state          = EventState.Complete,
      internal       = true
    )
    val reason =
      s"You already called `find_capability` with keywords `$normalizedKeywords` earlier this " +
        "turn. The ranker is deterministic — the same keywords will return the same hits. " +
        "Either pick a different tool from the prior results (review the previous " +
        "`find_capability` result Message in your context), or call `find_capability` again " +
        "with DIFFERENT keywords that describe the action shape more specifically. Repeating " +
        "the same search will not produce a different answer."
    val diagnostic = Message(
      participantId  = caller,
      conversationId = convId,
      topicId        = topicId,
      role           = MessageRole.Tool,
      content        = Vector(ResponseContent.Text(reason)),
      disposition    = MessageDisposition.Failure(recoverable = true),
      state          = EventState.Complete,
      visibility     = MessageVisibility.Agents,
      origin         = Some(syntheticInvokeId)
    )
    List[Signal](syntheticInvoke, diagnostic)
  }


  /** Public alias for [[executeAtomic]] — exposes the consent +
    * precondition gates the agent loop runs before dispatching a
    * tool's `execute`, so apps and specs can drive the same path
    * without going through a full provider round-trip. */
  def dispatchAtomic(tool: Tool,
                     input: ToolInput,
                     context: TurnContext,
                     originatingInvokeId: Id[Event]): Stream[Signal] =
    executeAtomic(tool, input, context, originatingInvokeId)

  /** Dispatches an atomic tool's `execute` and forwards its events as
    * signals. Each event the tool emits is followed by a
    * `StateDelta(Complete)` so the uniform Active → Complete lifecycle
    * holds for atomic tools too: subscribers see a reactive pulse on the
    * event, then a settle via the delta. Tools that explicitly emit
    * `state = Complete` still get a closing `StateDelta`, which is an
    * idempotent no-op (the event is already Complete).
    */
  private def executeAtomic(tool: Tool,
                            input: ToolInput,
                            context: TurnContext,
                            originatingInvokeId: Id[Event]): Stream[Signal] = {
    // Bug #69 — stamp every event the tool emits with the originating
    // ToolInvoke's id (unless the tool set an explicit origin
    // itself — apps with multi-source emissions can override the
    // default). The framework's invariant is "every MessageRole.Tool
    // event carries `origin` pointing to its parent ToolInvoke" so
    // FrameBuilder pairs by parent rather than by scan, and multiple
    // Tool events from one executeTyped all pair to the same call.
    //
    // Fast path: tools without preconditions / consent declared can
    // construct their stream synchronously — preserves bug #49's
    // behaviour (sync throws in `tool.execute` are caught by the
    // outer `Task(...).handleError` at the dispatch site so the
    // surrounding stream survives). Wrapping the fast path in
    // `Stream.force(Task...)` would convert sync throws into async
    // stream errors and slide past that handler. Slow path
    // (preconditions or consent gate) accepts that small shift in
    // error semantics — the tradeoff for declarable gates.
    if (tool.preconditions.isEmpty && !tool.requiresUserConsent)
      runExecute(tool, input, context, originatingInvokeId)
    else Stream.force(consentOutcome(tool, context, originatingInvokeId).flatMap {
      case Left(blockedSignals) => Task.pure(Stream.emits(blockedSignals))
      case Right(()) =>
        if (tool.preconditions.isEmpty) Task.pure(runExecute(tool, input, context, originatingInvokeId))
        else preflightOutcome(tool, context, originatingInvokeId).map {
          case Right(()) => runExecute(tool, input, context, originatingInvokeId)
          case Left(blockedSignals) => Stream.emits(blockedSignals)
        }
    })
  }

  /** Bug #83 — verify a [[sigil.event.ToolApproval]] exists before
    * dispatching a tool whose `requiresUserConsent` flag is set.
    * Returns `Right(())` to proceed; `Left(signals)` to short-
    * circuit dispatch with a Tool-role refusal Message that the
    * agent reads on its next iteration.
    *
    * Three outcomes:
    *   - tool doesn't require consent → `Right(())`, fast path
    *   - approved record exists → `Right(())`, proceed
    *   - declined record exists → `Left(refusal-with-decline-reason)`
    *   - no record exists → `Left(refusal-prompting-record_consent)` */
  private def consentOutcome(tool: Tool,
                             context: TurnContext,
                             originatingInvokeId: Id[Event]): Task[Either[List[Signal], Unit]] =
    if (!tool.requiresUserConsent) Task.pure(Right(()))
    else if (Orchestrator.isAutonomousPosture(context)) Task.pure(Right(()))
    else context.sigil.latestToolApproval(tool.name, context.conversation._id).map {
      case Some(approval) if approval.approved => Right(())
      case Some(declined) =>
        val reason = declined.reason.map(r => s" — $r").getOrElse("")
        val body =
          s"""Tool `${tool.name.value}` cannot run — user previously declined this action$reason.
             |
             |If the user's intent has changed, ask them again (e.g. via `respond_options`) and
             |record the new decision with `record_consent("${tool.name.value}", approved=true,
             |reason="...")` before retrying.""".stripMargin
        Left(refusalSignals(body, context, originatingInvokeId))
      case None =>
        val body =
          s"""Tool `${tool.name.value}` requires user consent before running.
             |
             |Ask the user (typically via `respond_options` listing this action), wait for the
             |reply, then call `record_consent("${tool.name.value}", approved=true, reason="...")`
             |and retry the tool. The framework refuses to dispatch consent-gated tools without
             |a `ToolApproval` record in this conversation.""".stripMargin
        Left(refusalSignals(body, context, originatingInvokeId))
    }

  private def refusalSignals(body: String,
                              context: TurnContext,
                              originatingInvokeId: Id[Event]): List[Signal] = {
    val msg = Message(
      participantId  = context.caller,
      conversationId = context.conversation.id,
      topicId        = context.conversation.currentTopicId,
      content        = Vector(ResponseContent.Text(body)),
      disposition    = MessageDisposition.Failure(recoverable = true),
      role           = MessageRole.Tool,
      state          = EventState.Complete,
      origin         = Some(originatingInvokeId)
    )
    List[Signal](
      msg,
      StateDelta(target = msg._id, conversationId = msg.conversationId, state = EventState.Complete)
    )
  }

  /** Tool execution path — emit each event with origin-stamping +
    * paired StateDelta. Extracted so both fast (no-precondition) and
    * slow (precondition-gated) executeAtomic paths share one
    * implementation. */
  private def runExecute(tool: Tool,
                         input: ToolInput,
                         context: TurnContext,
                         originatingInvokeId: Id[Event]): Stream[Signal] =
    tool.execute(input, context).flatMap { ev =>
      val stamped = if (ev.origin.isDefined) ev else ev.withOrigin(Some(originatingInvokeId))
      Stream.emits(List[Signal](
        stamped,
        StateDelta(target = stamped._id, conversationId = stamped.conversationId, state = EventState.Complete)
      ))
    }

  /** Run every [[Tool.preconditions]] check. If any returns
    * [[ToolPreconditionResult.Unsatisfied]], yield a Role.Tool
    * Message describing the blocked state instead of letting the
    * tool's `execute` run. The Message is paired to the originating
    * ToolInvoke so FrameBuilder threads it under that call. */
  private def preflightOutcome(tool: Tool,
                               context: TurnContext,
                               originatingInvokeId: Id[Event]): Task[Either[List[Signal], Unit]] =
    if (tool.preconditions.isEmpty) Task.pure(Right(()))
    else Task.sequence(tool.preconditions.map(p => p.check(context).map(p.name -> _))).map { results =>
      val unsatisfied = results.collect {
        case (n, ToolPreconditionResult.Unsatisfied(reason, fix)) => (n, reason, fix)
      }
      if (unsatisfied.isEmpty) Right(())
      else {
        val lines = unsatisfied.map { case (n, reason, fix) =>
          val fixHint = fix.map(f => s" — try `$f`").getOrElse("")
          s"- **$n**: $reason$fixHint"
        }.mkString("\n")
        val body =
          s"""Tool `${tool.name.value}` cannot run yet — preconditions not met:
             |
             |$lines
             |
             |Resolve the blocked items, then retry.""".stripMargin
        val msg = Message(
          participantId = context.caller,
          conversationId = context.conversation.id,
          topicId = context.conversation.currentTopicId,
          content = Vector(ResponseContent.Text(body)),
          disposition = MessageDisposition.Failure(recoverable = true),
          role = MessageRole.Tool,
          state = EventState.Complete,
          origin = Some(originatingInvokeId)
        )
        Left(List[Signal](
          msg,
          StateDelta(target = msg._id, conversationId = msg.conversationId, state = EventState.Complete)
        ))
      }
    }


  private def kindOf(name: String): ContentKind =
    scala.util.Try(ContentKind.valueOf(name)).getOrElse(ContentKind.Text)

  /** Bug #87 — canonical key for (toolName, args) so the
    * orchestrator can detect duplicate parallel calls in a single
    * completion. Falls back to `toString` for robustness — if
    * fabric's RW path throws on a particular ToolInput shape, the
    * dedupe just doesn't fire for that call rather than crashing. */
  private def canonicalArgsKey(toolName: String, input: sigil.tool.ToolInput): String = {
    val argsJson =
      try fabric.io.JsonFormatter.Compact(summon[fabric.rw.RW[sigil.tool.ToolInput]].read(input))
      catch { case _: Throwable => input.toString }
    s"$toolName:$argsJson"
  }

  /**
   * Emit a `MessageDelta` with `MessageContentDelta(complete = true, delta = full
   * block text)` if a content block is currently open and the buffer has
   * content. Resets the block buffer + kind. The full text closes the block
   * for the DB applier (which appends a fully-formed `ResponseContent`);
   * subscribers that already saw the streaming chunks can treat this as the
   * canonical final form.
   */
  private def closeCurrentBlock(state: State, convId: lightdb.id.Id[Conversation]): List[Signal] = {
    val emit = for {
      msgId <- state.activeMessageId
      kind  <- state.currentKind
      if state.currentBuffer.nonEmpty
    } yield {
      val text = state.currentBuffer.toString
      MessageDelta(
        target = msgId,
        conversationId = convId,
        content = Some(MessageContentDelta(kind = kind, arg = state.currentArg, complete = true, delta = text))
      )
    }
    state.currentBuffer.clear()
    state.currentKind = None
    state.currentArg = None
    emit.toList
  }

  /** Convert a provider's image-ref string (HTTP URL or `data:` URI) into
    * a `spice.net.URL`. Bare base64 (no `data:` prefix) is wrapped as
    * PNG by convention. Returns `None` when the string is empty or
    * unparseable. */
  private def parseImageUrl(ref: String): Option[spice.net.URL] = {
    if (ref.isEmpty) None
    else spice.net.URL.get(ref)
      .orElse(spice.net.URL.get(s"data:image/png;base64,$ref"))
      .toOption
  }
}
