package sigil.orchestrator

import lightdb.id.Id
import lightdb.time.Timestamp
import rapid.{Stream, Task}
import sigil.Sigil
import sigil.conversation.{ContextFrame, Conversation, Topic, TopicShiftResult}
import sigil.event.{Event, Message, MessageDisposition, MessageRole, MessageVisibility, Reasoning, TopicChange, TopicChangeKind, ToolInvoke, ToolOutcome}
import sigil.participant.ParticipantId
import sigil.provider.{CallId, ConversationRequest, Provider, ProviderEvent, ProviderImage, StopReason, XmlToolCallSanitizer}
import sigil.storage.StoredFileCategory
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
 *   - Runs `tool.execute(input, TurnContext, invokeId, currentMessageId)` for
 *     atomic tools (those for which no `ContentBlockDelta`s arrived during the
 *     call) and forwards the resulting Events as Signals
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
    Set("respond", "respond_options", "no_response")

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
    // Sigil #274 — derive the dispatch roster from
    // [[ConversationRequest.effectiveTools]], the SAME source the wire
    // path uses ([[Provider.translateConversationCore]]). Without this,
    // forced-synthesis recovery's stripped wire roster + the unfiltered
    // orchestrator roster drift: the model could call an out-of-roster
    // tool, the accumulator's parser would emit `JsonInput` (no schema
    // to validate against), the orchestrator's `toolsByName` would find
    // the real typed tool, and dispatch would `asInstanceOf` the
    // JsonInput to the tool's typed `Input` → ClassCastException.
    val toolsByName: Map[String, Tool] = request.effectiveTools.map(t => t.schema.name.value -> t).toMap
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
      // Sigil #410 — scrub vendor identity (support URLs / backend name) from a
      // raw provider/turn error before it reaches user-visible or agent-visible
      // content; the raw error stays in the server log only. This is the
      // user-visible path (settleOrphanMessage with routedToAgent = false renders
      // the reason into the end user's own reply bubble).
      val rawErrorMsg = errOpt.flatMap(t => Option(t.getMessage)).filter(_.nonEmpty)
      rawErrorMsg.foreach(m => scribe.warn(s"Turn error (raw, ${provider.providerKey}): $m"))
      val errorMsg = rawErrorMsg.map(m => sigil.sanitizeProviderError(provider.providerKey, m))
      val callerForOrphan = request.chain.lastOption.getOrElse(
        throw new IllegalStateException("ProviderRequest.chain is empty; orchestrator needs at least one participant.")
      )
      // The streaming Message gets the richer settleOrphanMessage
      // handling (Failure disposition + context); drop it from the
      // open-Message registry so the generic sweep skips it. Tool
      // invokes aren't in the registry (it tracks Messages only) —
      // settleOrphanToolInvoke owns them.
      val richlyHandled: List[lightdb.id.Id[Event]] = state.activeMessageId.toList
      val orphanToolInvokes = settleOrphanToolInvoke(
        state, convId,
        caller = callerForOrphan,
        topicId = request.currentTopic.id,
        error = errorMsg,
        reasonFor = a =>
          errorMsg.fold(s"Tool `${a.toolName}` did not produce a result")(
            err => s"Tool `${a.toolName}` did not complete: $err"
          ),
        recoverable = true
      )
      val orphanMessage = if (errOpt.isDefined) settleOrphanMessage(state, convId, error = errorMsg) else Nil
      richlyHandled.foreach(state.openEvents.remove)
      // Generic backstop — settle every remaining Active event the
      // turn opened, whatever its kind; Failed on a turn-error exit.
      val genericSettles = settleOpenEvents(state, convId, failed = errOpt.isDefined)
      val orphans = orphanToolInvokes ++ orphanMessage ++ genericSettles
      orphans.foldLeft(Task.unit) { (acc, sig) =>
        acc.flatMap(_ => sigil.publish(sig).handleError(_ => Task.unit))
      }
    }

    provider(request)
      .flatMap(pe => translate(pe, sigil, request, conversation, toolsByName, state))
      .map { signal => trackOpenEvent(state, signal); signal }
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
      * user-visible Message is built inside the tool's `executeResult`,
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

    /** Open-Message registry — every [[Message]] this turn emitted
      * with `state = Active`. Maintained automatically by
      * [[Orchestrator.trackOpenEvent]] as signals stream past, and
      * swept by `reconcileInflight` at turn end so no Active Message
      * outlives its turn — regardless of which kind of Message, and
      * with no per-kind wiring to forget. Scoped to Messages: tool
      * invokes have their own settle path (`settleOrphanToolInvoke`)
      * and some framework events (e.g. the refusal-challenge invoke)
      * have deliberate cross-turn lifecycles. */
    val openEvents: scala.collection.mutable.Set[lightdb.id.Id[Event]] =
      scala.collection.mutable.Set.empty

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

    /** #369 — count of non-essential (action) tool calls dispatched in THIS
      * response. Once it reaches `Sigil.maxToolCallsPerResponse`, further
      * action calls in the same completion are refused with a corrective note
      * — a model that fired a whole discovered family when it needed the
      * rank-1 tool. The respond family / no_response / stop never count. */
    var dispatchedActionCount: Int = 0

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
    // Sigil #397 — whether discovery is in this turn's roster. Under
    // ToolPolicy.ActiveOnly/None/Exclusive it isn't, so corrective copy must
    // not name a `find_capability` the model can't call.
    val findCapabilityAvailable: Boolean = toolsByName.contains("find_capability")
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
        val deferredInvoke: ToolInvoke = ToolInvoke(
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
            // The streaming path settles the in-flight Message via
            // `MessageDelta` and never runs the tool body — so it must
            // emit the call's settling delta itself. Sigil #265 — the
            // settling [[ToolDelta]] folds output / outcome / state =
            // Complete onto the originating ToolInvoke in one update;
            // the user-visible content lives in the settled Message,
            // this delta is the invoke-settle marker. With this, EVERY
            // tool call — atomic OR streaming — settles its invoke by
            // construction, so no wire-side orphan-heal is needed.
            val streamingSettleDelta: Signal = ToolDelta(
              target         = invokeId,
              conversationId = convId,
              state          = Some(EventState.Complete),
              output         = Some(_root_.sigil.tool.TextToolOutput("")),
              outcome        = Some(ToolOutcome.Success),
              internal       = isInternal
            )
            (Some(active.toolName), input) match {
              case (Some("respond"), r: RespondInput) =>
                // Suppress an in-turn repeated respond. `respond(endsTurn =
                // false)` keeps the turn open; a small model that re-emits
                // the SAME content on the next iteration — instead of
                // progressing with a real tool call — would otherwise stream
                // the identical user-facing message again, because the
                // streaming-settle path bypasses the atomic duplicate-call
                // cap. When this respond's content matches the caller's
                // immediately-preceding message (no intervening user input),
                // settle the birthed Message empty and force the turn to end
                // so the user never sees the duplicate and the loop can't
                // spin.
                val priorCallerText = request.turnInput.frames.reverseIterator.collectFirst {
                  case t: ContextFrame.Text => t
                }
                val isInTurnRepeat = priorCallerText.exists(t =>
                  t.participantId == caller &&
                    Orchestrator.normalizeForDedup(t.content) == Orchestrator.normalizeForDedup(r.content))
                if (isInTurnRepeat) {
                  val endTurnSettle = ToolDelta(
                    target         = invokeId,
                    conversationId = convId,
                    input          = Some(r.copy(endsTurn = true)),
                    state          = Some(EventState.Complete),
                    internal       = isInternal
                  )
                  val suppress = MessageDelta(
                    target             = msgId,
                    conversationId     = convId,
                    contentReplacement = Some(Vector.empty),
                    state              = Some(EventState.Complete)
                  )
                  return Stream.emits(closeBlock ::: List[Signal](deferredInvoke, endTurnSettle, suppress, streamingSettleDelta))
                }
                val sanitized = XmlToolCallSanitizer.sanitize(r.content)
                // Sigil #304 — when sanitization fires, publish the
                // existing diagnostic Notice AND synthesise an
                // agent-side intervention pair so the next iteration
                // sees what went wrong and can retry with proper JSON.
                // The intervention's signals ride the same Stream the
                // settled respond uses so ordering is preserved
                // (sanitized Message lands first, the agent-side
                // diagnostic right after).
                val interventionSignals: List[Signal] =
                  if (sanitized.leakedSpans.nonEmpty) {
                    val firstExcerpt = sanitized.leakedSpans.head.take(200)
                    sigil.publish(XmlToolCallLeak(
                      conversationId     = convId,
                      modelId            = Some(request.modelId),
                      leakedSpanCount    = sanitized.leakedSpans.size,
                      firstLeakedExcerpt = firstExcerpt
                    )).handleError(_ => rapid.Task.unit).startUnit()
                    SyntheticDiagnostic(
                      name    = XmlToolCallSanitizer.SyntheticInvokeName,
                      caller  = caller,
                      convId  = convId,
                      topicId = conversation.currentTopicId,
                      reason  = XmlToolCallSanitizer.interventionDirective(firstExcerpt)
                    )
                  } else Nil
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
                // respond path (via `RespondTool.executeResult`) fires
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
                    Stream.emits(prelude ::: closeBlock ::: toolDeltaPrefix ::: List[Signal](settle, streamingSettleDelta) ::: interventionSignals)
                  }
                )
              case _ =>
                val settle = MessageDelta(
                  target = msgId,
                  conversationId = convId,
                  state = Some(EventState.Complete)
                )
                Stream.emits(closeBlock ::: toolDeltaPrefix ::: List[Signal](settle, streamingSettleDelta))
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
              // #369 — per-response cap on TOTAL action-tool calls. A model
              // that fires a whole discovered family in one completion (10
              // `bsp_*` when it needed the rank-1) has the excess refused with
              // a corrective note. The respond family / no_response / stop are
              // the turn's delivery and never count. Distinct from the
              // identical-call cap below (which targets REPEATED calls).
              val perResponseCap = sigil.maxToolCallsPerResponse
              val isEssentialTool =
                Orchestrator.UserVisibleTerminalTools.contains(toolName) ||
                  toolName == "no_response" || toolName == "stop"
              if (perResponseCap > 0 && !isEssentialTool) {
                if (state.dispatchedActionCount >= perResponseCap) {
                  val body =
                    if (findCapabilityAvailable)
                      s"Refused `$toolName` -- you invoked more than $perResponseCap tools in this single " +
                        "response. `find_capability` results are RANKED; call the rank-1 tool for your goal, " +
                        "not the whole family. Re-issue just the one you need."
                    else
                      s"Refused `$toolName` -- you invoked more than $perResponseCap tools in this single " +
                        "response. Call only the one tool you need for your goal, not several at once. " +
                        "Re-issue just that one."
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
                state.dispatchedActionCount += 1
              }
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
                // Sigil #304 — scope the duplicate-call cap to invocations
                // made during THIS turn. The projection's rolling window
                // now persists across turns (to feed
                // `narrowRosterByRecentUse`), so unfiltered counts would
                // refuse legitimate cross-turn retries after the user
                // fixed an external precondition. `turnStartedAt` is set
                // by the agent-loop path; the fallback (`Long.MinValue`)
                // counts every recent invocation for one-shot / synthetic
                // requests that have no owning turn.
                val turnStartMs = request.turnStartedAt.map(_.value).getOrElse(Long.MinValue)
                // Sigil #407 — a large/slow tool (e.g. grep overflowing to
                // `.sigil/output/`) whose result keeps racing past the frame
                // settles Pending, so its re-issues are cap-EXCLUDED below
                // (#354). A TRANSIENT race self-heals in a turn or two; a
                // PERSISTENT racer would otherwise re-issue unboundedly and
                // never see the result. After `maxRacedReissues` raced identical
                // re-issues THIS turn, stop inviting re-issue: refuse with a
                // NON-escalating Failure that redirects the agent to the
                // externalized result, so it pivots to reading the overflow file
                // instead of losing the race again.
                val racedReissueLimit = sigil.maxRacedReissues
                if (racedReissueLimit > 0) {
                  val racedPrior = request.turnInput
                    .projectionFor(caller)
                    .recentToolInvocations
                    .count(inv => inv.toolName.value == toolName
                               && inv.argsHash == canonicalHash
                               && inv.invokedAt.value >= turnStartMs
                               && !inv.resulted)
                  if (racedPrior >= racedReissueLimit) {
                    val overflowDir = s".sigil/output/${convId.value}/"
                    val body =
                      s"`$toolName` has been issued $racedPrior times this turn and its result keeps " +
                        s"racing past the turn — a large/slow result that overflowed to a file. Re-issuing " +
                        s"loses the race again and makes no progress. The completed result was written under " +
                        s"`$overflowDir` (as `$toolName-<callId>.txt`); read it with `read_file` or search it " +
                        s"with `grep` there instead of calling `$toolName` again."
                    val racedMsg = Message(
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
                      racedMsg,
                      StateDelta(target = racedMsg._id, conversationId = convId, state = EventState.Complete)
                    ))
                  }
                }
                val matchingPrior = request.turnInput
                  .projectionFor(caller)
                  .recentToolInvocations
                  .filter(inv => inv.toolName.value == toolName
                             && inv.argsHash == canonicalHash
                             && inv.invokedAt.value >= turnStartMs
                             // Sigil #354 — only count invocations that actually
                             // produced a result. A prior identical call whose
                             // result raced past the frame (settled Pending, the
                             // agent saw only a placeholder) is not the agent
                             // spinning; retrying to obtain the missing result is
                             // rational and must not trip the cap → escalation →
                             // roster-restriction spiral.
                             && inv.resulted)
                val priorIdentical = matchingPrior.size
                if (priorIdentical >= identicalLimit - 1) {
                  val attemptedCount = priorIdentical + 1
                  val preview = _root_.sigil.tool.ToolInputCanonicalizer.argsPreview(input)
                  val previewText = if (preview.nonEmpty) s" `$preview`" else ""
                  // Sigil #371 — a duplicate-call loop whose prior identical
                  // calls ALL failed is a tooling-seam loop, not a capability
                  // gap: a stronger model issues the same call and hits the same
                  // failure. Refuse the duplicate (the Failure guides a different
                  // approach) but don't burn a frontier tier escalating on it.
                  val seamLoop = matchingPrior.nonEmpty && matchingPrior.forall(_.failed)
                  // Sigil bug #287 — detection alone doesn't help when the
                  // same small/weak model that produced the duplicate
                  // keeps producing it. Bump the per-turn complexity
                  // tier so the next iteration routes to a more capable
                  // model that can actually reason its way out. The
                  // escalation is one-shot per call site: keeps
                  // climbing each time the cap fires, clamps at the
                  // top tier. Synchronous so the Failure body can
                  // mention the new tier in its didactic text. Apps
                  // that don't want the auto-bump set
                  // `escalateOnDuplicateCallCap = false`.
                  val (newTier, escalated) =
                    if (sigil.escalateOnDuplicateCallCap && !seamLoop)
                      sigil.requestEscalation(convId, reason = s"duplicate-call cap on `$toolName`").sync()
                    else (_root_.sigil.provider.Complexity.Medium, false)
                  val escalationText =
                    if (escalated)
                      s" Next iteration will run on the ${newTier.toString} tier — re-read this " +
                        "Failure and pick a different next move."
                    else if (seamLoop)
                      s" `$toolName` keeps failing on these exact args — a different model won't change " +
                        "that. Switch tool or arguments, or ask the user for direction."
                    else if (sigil.escalateOnDuplicateCallCap)
                      " Already on the top tier — if you can't make progress, call `respond_options` " +
                        "asking the user for direction."
                    else ""
                  val body =
                    s"Refused to dispatch `$toolName` -- you have already called this tool with " +
                      s"these exact args $priorIdentical times in the recent window (this would " +
                      s"be call #$attemptedCount).$previewText The result will not change. Try a " +
                      "different approach: narrow the pattern, switch to a different tool, or ask " +
                      "the user for clarification." + escalationText
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
              // `tool.execute` is total: the framework runs the tool's
              // resolution (mapping any crash to a Failure) and emits
              // exactly one settling [[ToolDelta]] that folds the typed
              // output / outcome / state onto the originating
              // [[ToolInvoke]]. The call/result pairing invariant holds
              // by construction; no synth / drain-and-guarantee
              // apparatus is needed.
              // Sigil #271 — `toolsByName` falls back to the framework's
              // [[sigil.tool.core.UnknownTool]] sentinel for names the model
              // hallucinated. UnknownTool's executeResult emits the paired
              // Tool-role Failure Message (visibility = Agents, origin =
              // invokeId) and returns ToolResult.Failure, so the agent loop
              // re-triggers and self-corrects instead of aborting with
              // AgentRunawayException. Same shape as any other tool failure
              // — the dispatch path has one branch, not two.
              val tool: Tool = toolsByName.getOrElse(active.toolName, _root_.sigil.tool.core.UnknownTool)
              val context = TurnContext(
                sigil = sigil,
                chain = request.chain,
                conversation = conversation,
                turnInput = request.turnInput,
                // Bug #55 — atomic content tools stamp `Message.modelId`
                // from here so per-message metadata shows which model
                // produced the response.
                model = request.model,
                // Sigil #281 follow-up — share the agent loop's
                // discovery cache so FindCapabilityTool's
                // `recordDiscovery` lands where the next iteration's
                // `runAgentTurn` can read it.
                discoveredCapabilitiesRef = request.discoveredCapabilitiesRef,
                // Surface the offered roster to tools that build refusal
                // payloads — UnknownTool's closest-name suggestion reads
                // from this; record_consent's unknown-toolName branch
                // surfaces the closest match from the same view.
                offeredTools = request.tools
              )
              // Thinking-reserved Message id (if a prior `ThinkingDelta`
              // allocated one) — passed to `tool.execute` so atomic content
              // tools can adopt it as `Message._id`.
              val currentMessageIdForTool: Option[Id[Event]] =
                if (state.activeMessageCreated) None else state.activeMessageId
              // The `Task(...).handleError` wrap is cheap insurance
              // against a throw while *constructing* the dispatch stream
              // (a precondition / consent check in `executeAtomic`) —
              // `tool.execute` itself is total.
              val executed: Stream[Signal] = Stream.force(
                Task(executeAtomic(tool, input, context, invokeId, currentMessageIdForTool, ToolName(active.toolName))).handleError { err =>
                  scribe.error(s"Atomic tool '$toolName' threw while building its dispatch", err)
                  Task.pure(Stream.emit[Signal](Message(
                    participantId  = caller,
                    conversationId = convId,
                    topicId        = topicId,
                    role           = MessageRole.Tool,
                    content        = Vector(ResponseContent.Text(
                      s"Tool '$toolName' execution failed: ${err.getClass.getSimpleName}: ${err.getMessage}"
                    )),
                    state          = EventState.Complete,
                    disposition    = MessageDisposition.Failure(recoverable = true),
                    visibility     = MessageVisibility.Agents,
                    origin         = Some(invokeId)
                  )))
                }
              )
              // Drain the result stream so we can (1) capture the result
              // content for #87's duplicate-call inlining and (2) recover
              // the user-visible Message id (a `respond`'s emitted reply,
              // for instance) for the `Usage` handler — the framework
              // drains `ctx.emit`-buffered events into the same stream
              // ahead of the settling delta, so they're all in `collected`.
              // The `handleError` keeps the corruption-resistance invariant
              // if `executeAtomic`'s consent / precondition stream
              // errors mid-pull.
              // #317 — broadcast the Active ToolInvoke to live subscribers
              // the moment dispatch begins, then drain the (possibly
              // long-running) tool and emit its events. The broadcast is
              // sequenced BEFORE the drain via `flatMap` so a slow tool
              // doesn't delay the Active chip — and so ordering does NOT
              // depend on `++` forcing the right side eagerly. Stream is
              // lazy: `++` defers the right operand until the left drains,
              // so the old `eagerPrefix ++ finalStream` shape (which relied
              // on `++` running `executed.toList` at subscription) is wrong.
              // `toolDeltaPrefix` (the invoke + its Active delta) is emitted
              // for durable persist; `publish` sees the eager-broadcast
              // marker and suppresses the hub re-emit, so the invoke
              // broadcasts once and persists once.
              val finalStream: Stream[Signal] = Stream.force(
                sigil.broadcastEager(deferredInvoke).flatMap { _ =>
                  executed.toList.handleError { err =>
                  scribe.error(
                    s"orchestrator: tool '${active.toolName}' (invokeId=${invokeId.value}) stream " +
                      s"errored mid-dispatch (${err.getClass.getSimpleName}: ${err.getMessage}). " +
                      "Pairing the invoke with a Failure result.",
                    err
                  )
                  Task.pure(List[Signal](Message(
                    participantId  = caller,
                    conversationId = convId,
                    topicId        = topicId,
                    role           = MessageRole.Tool,
                    content        = Vector(ResponseContent.Text(
                      s"Tool `${active.toolName}` failed during execution: " +
                        s"${err.getClass.getSimpleName}: ${Option(err.getMessage).getOrElse("(no message)")}."
                    )),
                    state          = EventState.Complete,
                    disposition    = MessageDisposition.Failure(recoverable = true),
                    visibility     = MessageVisibility.Agents,
                    origin         = Some(invokeId)
                  )))
                }.map { collected =>
                  collected.foreach {
                    // Sigil #265 — the settling [[ToolDelta]] carries the
                    // typed output + failure summary on the invoke itself.
                    // Render it for the duplicate-call inlining cache so
                    // repeated calls inline the prior result without
                    // re-running the tool.
                    case td: ToolDelta if td.target == invokeId =>
                      val rendered: String = td.outcome match {
                        case Some(_root_.sigil.event.ToolOutcome.Failure(reason, _)) =>
                          td.summary.getOrElse(reason)
                        case _ =>
                          td.summary.orElse {
                            td.output.flatMap { o =>
                              try Some(fabric.io.JsonFormatter.Default(summon[fabric.rw.RW[_root_.sigil.tool.ToolOutput]].read(o)))
                              catch { case _: Throwable => None }
                            }
                          }.getOrElse("")
                      }
                      if (rendered.nonEmpty)
                        state.dispatchedResultContent(invokeId) = Vector(ResponseContent.Text(rendered))
                    case m: Message if m.role == MessageRole.Tool && m.origin.contains(invokeId) =>
                      state.dispatchedResultContent(invokeId) = m.content
                    case _ =>
                  }
                  // `respond` publishes its user-visible Message via
                  // `ctx.emit`; the framework drains those events into
                  // `executed`, so we recover the id from `collected`.
                  collected.reverseIterator.collectFirst {
                    case m: Message if m.role != MessageRole.Tool => m._id
                  }.foreach(id => state.lastUserVisibleMessageId = Some(id))
                  // Sigil #354 — guarantee the invoke settles with an OUTCOME.
                  // `toolDeltaPrefix` already flipped it to `Complete` (the input
                  // settle), so if the tool's execution produced no settling
                  // event — an empty / interrupted result stream — the invoke
                  // dangles Complete-but-Pending and the agent reads the
                  // ambiguous "raced past, retry" placeholder (the materialized
                  // grep frame-race). Synthesize a recoverable Failure so it
                  // settles definitively with an actionable message instead.
                  val outcomeSettled = collected.exists {
                    case td: ToolDelta if td.target == invokeId            => td.outcome.isDefined
                    case m: Message if m.role == MessageRole.Tool && m.origin.contains(invokeId) => true
                    case _                                                  => false
                  }
                  val settleGuard: List[Signal] =
                    if (outcomeSettled) Nil
                    else List[Signal](ToolDelta(
                      target         = invokeId,
                      conversationId = convId,
                      output         = Some(_root_.sigil.tool.TextToolOutput("")),
                      outcome        = Some(_root_.sigil.event.ToolOutcome.Failure(
                        s"Tool `${active.toolName}` returned no result (its execution produced no output — likely interrupted mid-run). Re-issue it if you still need the result.",
                        recoverable = true)),
                      state          = Some(EventState.Complete),
                      internal       = isInternal
                    ))
                  Stream.emits(toolDeltaPrefix ::: collected ::: settleGuard)
                  }
                }
              )
              finalStream
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
                        return Stream.force(refusalChallengeOutcome(sigil, findCapabilityAvailable, r.content, convId, caller, topicId).map {
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
                      // Settle the find_capability invoke itself with a
                      // recoverable Failure so its frame renders the
                      // suppression — NOT the #354 "result raced past the
                      // prompt" placeholder. Emitting only `toolDeltaPrefix`
                      // (input delta, no outcome) left the invoke Complete-but-
                      // Pending, whose frame told the agent the result hadn't
                      // arrived and retrying was reasonable — driving the exact
                      // re-query loop this intercept exists to stop.
                      val note =
                        "Suppressed -- this repeats a `find_capability` query you already ran this turn. " +
                          "The ranker is deterministic; see the guidance that follows."
                      val settle = ToolDelta(
                        target         = invokeId,
                        conversationId = convId,
                        output         = Some(_root_.sigil.tool.TextToolOutput(note)),
                        outcome        = Some(ToolOutcome.Failure(note, recoverable = true)),
                        state          = Some(EventState.Complete),
                        internal       = isInternal
                      )
                      Stream.emits(toolDeltaPrefix ::: List[Signal](settle) ::: interceptSignals)
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
        // `executeResult`, so the streaming-text path never fires; this
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
            // agent loop's no-tool-call recovery re-enters the loop
            // (a full-roster retry, then a forced respond-family
            // iteration); the usage from THIS turn is dropped since no
            // Message exists to carry it — the recovery iteration emits
            // a Message that carries its own usage.
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

      case ProviderEvent.ImageGenerationPartial(callId, image) =>
        // First partial creates an Active Message keyed by callId; subsequent
        // partials emit ImageDelta updates so the same Message progressively
        // shows better previews until ImageGenerationComplete settles it.
        // Inline previews are stored with a short TTL so superseded ones
        // are reclaimed by StoredFileExpirationSweep.
        val previewExpiry = Some(lightdb.time.Timestamp(System.currentTimeMillis() + 3600000L))
        Stream.force(
          resolveProviderImage(sigil, image, StoredFileCategory.ExternalizedContent, previewExpiry).map { url =>
            state.imageMessageIds.get(callId.value) match {
              case Some(messageId) =>
                Stream.emits(List[Signal](ImageDelta(
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
        )

      case ProviderEvent.ImageGenerationComplete(callId, image) =>
        // Settle the streaming Message (created on the first partial). When
        // there were no partials — built-in tool path or non-streaming —
        // synthesize a fresh Complete Message carrying the image. The final
        // image is stored persistently.
        Stream.force(
          resolveProviderImage(sigil, image, StoredFileCategory.UserAttachment, None).map { url =>
            state.imageMessageIds.get(callId.value) match {
              case Some(messageId) =>
                state.imageMessageIds = state.imageMessageIds - callId.value
                Stream.emits(List[Signal](
                  ImageDelta(target = messageId, conversationId = convId, url = url),
                  StateDelta(target = messageId, conversationId = convId, state = EventState.Complete)
                ))
              case None =>
                Stream.emits(List[Signal](
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
        )

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
        // "tool's executeResult — please report it" framework error
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
                SyntheticDiagnostic("_degenerate_generation", caller, convId, topicId,
                  reason      = hit.renderDiagnostic(text.length),
                  disposition = MessageDisposition.Failure(recoverable = true))
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
        // Sigil #398 — plain text means different things depending on whether
        // the turn ran on forced `tool_choice`. A model NOT in the
        // forced-tool_choice-rejecter memo (#395) ran on `Required`, so plain
        // text is genuine drift (bug #75 — gemma ignoring the forcing) and is
        // dropped + diagnosed here. A model IN the memo (Fable / Mythos, whose
        // forced choice was downgraded to `auto`) chose prose as its answer; a
        // `Complete` plain-text turn from it is committed by `nakedTextTerminal`
        // below, not diagnosed. (Grammar-constrained providers can't drift
        // under forced tool_choice, so this only ever bites the chat-completions
        // wire, which streams prose as TextDelta into `plainTextBuffer`.)
        val autoAnsweredPlainText =
          stopReason == StopReason.Complete && Provider.rejectsForcedToolChoice(request.modelId)
        val plainTextDiagnostic: List[Signal] =
          if (state.plainTextBuffer.nonEmpty && !state.sawAnyToolCall && !autoAnsweredPlainText) {
            val droppedText = state.plainTextBuffer.toString
            val snippet = if (droppedText.length <= 200) droppedText else droppedText.take(200) + "…"
            val reason =
              "Your previous reply was plain text and was dropped — every reply must be a " +
                "tool call. Wrap your answer in one of the respond-family tools " +
                "(`respond`, `respond_options`, `no_response`) appropriate to your situation. " +
                "When a tool result IS the " +
                s"user-facing answer, call `respond` with that content. Dropped text was: $snippet"
            SyntheticDiagnostic("_plain_text_reply", caller, convId, topicId,
              reason      = reason,
              disposition = MessageDisposition.Failure(recoverable = true))
          } else Nil
        // Sigil #392/#398 — commit a naked-text terminal answer instead of
        // dropping it. When the turn ended naturally (`end_turn` →
        // StopReason.Complete) with user-visible prose and NO tool call, the
        // model gave a complete reply. Two wire shapes carry that prose:
        //   - `ContentBlockDelta` (Anthropic / OpenAI Responses / Google) — a
        //     Message was born (`activeMessageCreated`); settle it in place
        //     (#392). These providers grammar-constrain tool calls under forced
        //     tool_choice, so plain-text-only here is always a genuine `auto`
        //     answer — no rejecter check needed.
        //   - `TextDelta` (OpenAI-compatible chat-completions, e.g. Fable via
        //     OpenRouter) — buffered in `plainTextBuffer` with NO Message born;
        //     mint one and settle it terminally (#398), BUT only for a model in
        //     the forced-tool_choice-rejecter memo (#395) — i.e. one that
        //     actually ran on `auto`. A non-rejecter's plain text on this wire
        //     is drift (bug #75) and went to the `_plain_text_reply` diagnostic.
        // The settle flags `terminalReply` so the agent loop treats it as a
        // user-visible reply (no re-request).
        val nakedTextTerminal: List[Signal] =
          if (stopReason != StopReason.Complete || state.sawAnyToolCall) Nil
          else if (state.activeMessageCreated) {
            // Snapshot the streamed text into the settle — the per-delta
            // `ContentBlockDelta`s were `complete = false` (snapshot-only
            // persistence ignores them), so without this the committed Message
            // would settle empty.
            val text = state.currentBuffer.toString
            state.activeMessageId.toList.map(id =>
              MessageDelta(target = id, conversationId = convId,
                contentReplacement = Some(Vector(ResponseContent.Text(text))),
                state = Some(EventState.Complete), terminalReply = true))
          } else if (state.plainTextBuffer.nonEmpty && Provider.rejectsForcedToolChoice(request.modelId)) {
            val text = state.plainTextBuffer.toString
            val id = state.activeMessageId.getOrElse(Event.id())
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
            List(msg, MessageDelta(target = id, conversationId = convId,
              contentReplacement = Some(Vector(ResponseContent.Text(text))),
              state = Some(EventState.Complete), terminalReply = true))
          } else Nil
        Stream.emits(closeOrphan ++ plainTextDiagnostic ++ degenerateDiagnostic ++ nakedTextTerminal)
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
        // Sigil #410 — scrub vendor identity (support URLs / backend name) from
        // the raw provider error before it becomes agent-visible (the Tool-role
        // Message + ToolDelta below, which the model can echo) or user-visible;
        // the raw error stays in the server log only. Provider key = the model
        // id's namespace (`openai/…` → `openai`), the same key the registry
        // dispatches on.
        val providerKey = request.modelId.value.split("/", 2).headOption.getOrElse(request.modelId.value)
        scribe.warn(s"Provider error (raw, model=${request.modelId.value}): $msg")
        val safeMsg = sigil.sanitizeProviderError(providerKey, msg)
        val orphanSettle = settleOrphanToolInvoke(state, convId, caller, topicId, error = Some(safeMsg))
        // The failure is routed to the agent below (recoverable
        // Tool failure + retry); don't ALSO dead-end the user with the
        // streamed placeholder's "failed to produce a valid reply".
        val orphanMessageSettle = settleOrphanMessage(state, convId, error = Some(safeMsg), routedToAgent = true)
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
            val syntheticInvoke = SyntheticDiagnostic.invoke("_provider_error", caller, convId, topicId)
            (List[Signal](syntheticInvoke), syntheticInvoke._id)
        }
        // Sigil #265 — emit the provider-error pairing as a settling
        // [[ToolDelta]] folding `outcome = Failure(reason, recoverable
        // = true)` onto the originating invoke. The structured outcome
        // travels through the wire — the agent reads "the call failed
        // because $msg; retry with corrected args" and self-corrects.
        // `recoverable = true` because pre-flight / mid-stream provider
        // errors are typically retryable (transient backend hiccup,
        // args malformed, etc.); a fatal provider failure has its own
        // escalation path through `ProviderStrategy`.
        val reason = s"Provider error: $safeMsg"
        val errorResult: Signal = ToolDelta(
          target         = originId,
          conversationId = convId,
          state          = Some(EventState.Complete),
          summary        = Some(reason),
          outcome        = Some(ToolOutcome.Failure(reason, recoverable = true)),
          error          = Some(reason)
        )
        // Sigil #273 — pair the failure with a Tool-role Message so
        // `TriggerFilter` (`role == MessageRole.Tool` → re-fire) wakes the
        // agent loop on the next iteration. Without this, only the settling
        // ToolDelta lands (and ToolDelta isn't an Event subtype, so the
        // trigger filter ignores it) — the loop treats the turn as "no tool
        // call" and burns its recovery budget on a forced-synthesis attempt
        // that knows nothing about the failure. With the Tool-role Message
        // the model reads the diagnostic on its next turn, corrects its args
        // (or chooses a different tool / `respond`s), and the runaway only
        // fires for its real signal: genuinely zero `tool_use` emitted.
        val errorMessage: Signal = Message(
          participantId  = caller,
          conversationId = convId,
          topicId        = topicId,
          role           = MessageRole.Tool,
          content        = Vector(ResponseContent.Text(reason)),
          state          = EventState.Complete,
          disposition    = MessageDisposition.Failure(recoverable = true),
          visibility     = MessageVisibility.Agents,
          origin         = Some(originId)
        )
        Stream.emits(orphanSettle ++ orphanMessageSettle ++ preludeSignals ++ List(errorResult, errorMessage))
    }
  }

  /** Maintain `state.openEvents` as signals stream past — the
    * automatic registration that lets the turn-end settle guarantee
    * cover every kind of Active Message without per-kind wiring. An
    * Active Message registers; a settling StateDelta / MessageDelta,
    * or a Message emitted already Complete, deregisters. */
  private def trackOpenEvent(state: State, signal: Signal): Unit = signal match {
    case m: Message =>
      if (m.state == EventState.Active) state.openEvents.add(m._id)
      else state.openEvents.remove(m._id)
    case sd: StateDelta if sd.state == EventState.Complete =>
      state.openEvents.remove(sd.target)
    case md: MessageDelta if md.state.contains(EventState.Complete) =>
      state.openEvents.remove(md.target)
    case _ => ()
  }

  /** Generic turn-end settle for every Message still registered in
    * `state.openEvents` — the universal backstop guaranteeing no
    * Active Message outlives its turn, whatever kind it is (image
    * messages, and any future kind, are covered with no new code).
    * Runs from the termination-guarantee block, which fires on every
    * exit path — clean Done, error, mid-stream abort, cancellation.
    * Settles with a Failure disposition when the turn ended in error,
    * else to Complete. The streaming Message is removed from the
    * registry by `reconcileInflight` before this runs — it gets the
    * richer `settleOrphanMessage` handling. Clears the registry. */
  private def settleOpenEvents(state: State, convId: Id[Conversation], failed: Boolean): List[Signal] = {
    val settles: List[Signal] = state.openEvents.toList.map { id =>
      if (failed)
        MessageDelta(
          target = id,
          conversationId = convId,
          state = Some(EventState.Complete),
          disposition = Some(MessageDisposition.Failure(recoverable = false))
        )
      else
        StateDelta(target = id, conversationId = convId, state = EventState.Complete)
    }
    state.openEvents.clear()
    settles
  }

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
      // Sigil #265 — the orphan settle is one [[ToolDelta]] folding
      // input + state = Complete + outcome = Failure(reason, recoverable)
      // onto the invoke in a single update. The
      // agent reads the failure on its next iteration via the invoke's
      // own settled `outcome`/`summary`. Bug #51 — `error = Some(...)`
      // surfaces the provider-side diagnostic so client chips render
      // "(invalid args: …)" instead of the "(input pending)" placeholder
      // reserved for genuinely-mid-flight calls.
      val reason = reasonFor(active)
      val settleDelta: Signal = ToolDelta(
        target         = active.invokeId,
        conversationId = convId,
        input          = None,
        state          = Some(EventState.Complete),
        summary        = Some(reason),
        outcome        = Some(ToolOutcome.Failure(reason, recoverable = recoverable)),
        error          = error
      )
      List(synthInvoke, settleDelta)
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
   *
   * `routedToAgent` — when the same failure is ALSO being routed to the
   * agent as a recoverable Tool failure (the `ProviderEvent.Error` path:
   * the agent retries and produces the real reply), the streamed
   * placeholder must NOT surface a user-facing "failed to produce a
   * valid reply" dead-end — that's the failure leaking to the user as a
   * respond message instead of being handled by the agent. The Message
   * still settles to `Complete` (so the "typing" indicator stops and
   * live clients don't hang) but with empty content + a recoverable
   * Failure disposition, which clients collapse — leaving only the
   * agent's retried respond visible. Genuine unrecovered turn failures
   * (`reconcileInflight`) keep `routedToAgent = false` and show the
   * diagnostic inline.
   */
  private def settleOrphanMessage(state: State,
                                  convId: lightdb.id.Id[Conversation],
                                  error: Option[String] = None,
                                  routedToAgent: Boolean = false): List[Signal] =
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
        val replacement =
          if (routedToAgent) Vector.empty[ResponseContent]
          else Vector(ResponseContent.Text(s"Model output failed to produce a valid reply: $reason"))
        val delta: Signal = MessageDelta(
          target             = msgId,
          conversationId     = convId,
          contentReplacement = Some(replacement),
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
                                      findCapabilityAvailable: Boolean,
                                      content: String,
                                      convId: lightdb.id.Id[Conversation],
                                      caller: ParticipantId,
                                      topicId: lightdb.id.Id[Topic]): Task[Option[List[Signal]]] = {
    // Sigil #397 — the challenge's whole corrective is "call `find_capability`
    // before refusing". With discovery suppressed (ToolPolicy.ActiveOnly/None/
    // Exclusive) there is no such tool, so an informed refusal is the only
    // option — never challenge it.
    if (!findCapabilityAvailable || !sigil.refusalDetector.isRefusal(content)) Task.pure(None)
    else sigil.withDB(_.conversationEvents(convId)).map { allEvents =>
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
    val reason =
      "Your previous `respond` refused the user without first calling `find_capability` (see the " +
        "system prompt — a refusal not preceded by `find_capability` is a bug). The tool catalog " +
        "likely contains capabilities you haven't discovered. Call `find_capability` with keywords " +
        "describing what the user asked, review the matches, then decide whether to refuse based on " +
        "what discovery actually returns. If no relevant capability surfaces, refuse with the " +
        "specifics of what you searched and what wasn't there."
    SyntheticDiagnostic("_refusal_challenge", caller, convId, topicId,
      reason      = reason,
      disposition = MessageDisposition.Failure(recoverable = true))
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
    sigil.withDB(_.conversationEvents(convId)).map { allEvents =>
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
    val reason =
      s"You already called `find_capability` with keywords `$normalizedKeywords` earlier this " +
        "turn. The ranker is deterministic — the same keywords will return the same hits. " +
        "Either pick a different tool from the prior results (review the previous " +
        "`find_capability` result Message in your context), or call `find_capability` again " +
        "with DIFFERENT keywords that describe the action shape more specifically. Repeating " +
        "the same search will not produce a different answer."
    SyntheticDiagnostic("_repeated_query_intercept", caller, convId, topicId,
      reason      = reason,
      disposition = MessageDisposition.Failure(recoverable = true))
  }


  /** Public alias for [[executeAtomic]] — exposes the consent +
    * precondition gates the agent loop runs before dispatching a
    * tool's `execute`, so apps and specs can drive the same path
    * without going through a full provider round-trip. */
  def dispatchAtomic(tool: Tool,
                     input: ToolInput,
                     context: TurnContext,
                     originatingInvokeId: Id[Event],
                     currentMessageId: Option[Id[Event]] = None,
                     invokedName: Option[ToolName] = None): Stream[Signal] =
    // External callers (tests, app code driving the dispatch path directly)
    // overwhelmingly use the tool's own name; let them omit it. The
    // orchestrator's wire path passes the model-invoked name explicitly so
    // UnknownTool can render it in its failure message.
    executeAtomic(tool, input, context, originatingInvokeId, currentMessageId, invokedName.getOrElse(tool.name))

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
                            originatingInvokeId: Id[Event],
                            currentMessageId: Option[Id[Event]],
                            invokedName: ToolName): Stream[Signal] = {
    // Bug #69 — stamp every event the tool emits with the originating
    // ToolInvoke's id (unless the tool set an explicit origin
    // itself — apps with multi-source emissions can override the
    // default). The framework's invariant is "every MessageRole.Tool
    // event carries `origin` pointing to its parent ToolInvoke" so
    // FrameBuilder pairs by parent rather than by scan, and multiple
    // Tool events from one executeResult all pair to the same call.
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
      runExecute(tool, input, context, originatingInvokeId, currentMessageId, invokedName)
    else Stream.force(consentOutcome(tool, context, originatingInvokeId).flatMap {
      case Left(blockedSignals) => Task.pure(Stream.emits(blockedSignals))
      case Right(()) =>
        if (tool.preconditions.isEmpty)
          Task.pure(runExecute(tool, input, context, originatingInvokeId, currentMessageId, invokedName))
        else preflightOutcome(tool, context, originatingInvokeId).map {
          case Right(()) => runExecute(tool, input, context, originatingInvokeId, currentMessageId, invokedName)
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

  /** Tool execution path — drive the tool's `Stream[Signal]` (Sigil
    * #265) and origin-stamp / settle ancillary Events. Pass-through
    * for the settling [[ToolDelta]] the tool emits last (it already
    * carries `state = Some(Complete)` and targets the originating
    * invoke). Extracted so both fast (no-precondition) and slow
    * (precondition-gated) executeAtomic paths share one
    * implementation.
    *
    * Bug #56 follow-up — when the tool is in [[UserVisibleTerminalTools]]
    * (the respond family + `no_response`), stamp `internal = true` on
    * any [[ToolDelta]] in the output stream so the result-settle delta
    * matches the input-settle delta the orchestrator already emitted
    * with that flag set. Clients filtering chips by `internal` see a
    * consistent lifecycle. */
  private def runExecute(tool: Tool,
                         input: ToolInput,
                         context: TurnContext,
                         originatingInvokeId: Id[Event],
                         currentMessageId: Option[Id[Event]],
                         invokedName: ToolName): Stream[Signal] = {
    val toolIsInternal = Orchestrator.UserVisibleTerminalTools.contains(invokedName.value)
    tool.execute(input, context, originatingInvokeId, invokedName, currentMessageId).flatMap {
      case ev: Event =>
        val stamped = if (ev.origin.isDefined) ev else ev.withOrigin(Some(originatingInvokeId))
        Stream.emits(List[Signal](
          stamped,
          StateDelta(target = stamped._id, conversationId = stamped.conversationId, state = EventState.Complete)
        ))
      case td: ToolDelta if toolIsInternal && !td.internal =>
        Stream.emit(td.copy(internal = true))
      case other => Stream.emit(other)
    }
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

  /** Whitespace-normalised form used to compare a streaming respond's
    * content against the caller's preceding message. Collapses runs of
    * whitespace and trims so markdown paragraph-join differences (a
    * rendered frame joins blocks with single newlines; the raw arg may
    * use blank lines) don't mask an otherwise-identical repeat. */
  private[orchestrator] def normalizeForDedup(s: String): String =
    s.trim.replaceAll("\\s+", " ")

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

  /** Resolve a [[ProviderImage]] to a fetchable URL. A
    * [[ProviderImage.Hosted]] URL passes through unchanged;
    * [[ProviderImage.Inline]] bytes are persisted via `storeBytes` so
    * the multi-megabyte payload never enters conversation history, and
    * the stored file's URL is returned. */
  private def resolveProviderImage(sigil: Sigil,
                                   image: ProviderImage,
                                   category: StoredFileCategory,
                                   expiresAt: Option[lightdb.time.Timestamp]): Task[spice.net.URL] =
    image match {
      case ProviderImage.Hosted(url) => Task.pure(url)
      case ProviderImage.Inline(base64, contentType) =>
        val bytes = java.util.Base64.getDecoder.decode(base64)
        sigil.storeBytes(_root_.sigil.GlobalSpace, bytes, contentType,
          category = category, expiresAt = expiresAt).map(sigil.storageUrl)
    }
}
