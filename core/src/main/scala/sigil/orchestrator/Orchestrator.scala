package sigil.orchestrator

import lightdb.id.Id
import lightdb.time.Timestamp
import rapid.{Stream, Task}
import sigil.Sigil
import sigil.conversation.{ContextFrame, Conversation, Topic, TopicShiftResult}
import sigil.event.{Event, Message, MessageDisposition, MessageRole, MessageVisibility, Reasoning, TopicChange, TopicChangeKind, ToolInvoke}
import sigil.participant.ParticipantId
import sigil.provider.{CallId, ConversationRequest, Provider, ProviderEvent, StopReason}
import sigil.signal.{MessageContentDelta, ContentKind, EventState, ImageDelta, MessageDelta, Signal, StateDelta, ToolDelta}
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

    provider(request)
      .flatMap(pe => translate(pe, sigil, request, conversation, toolsByName, state))
      .onErrorFinalize { _ =>
        // The `Done`/`Error` ProviderEvent arms emit orphan-settle
        // ToolDeltas via `Stream.emits` so they reach the consumer
        // through the normal `evalTap(publish)` path. Those arms only
        // fire when the provider stream actually reached completion —
        // a mid-stream Task error (HTTP read fails, context-overflow
        // 400 thrown by the wire layer, fiber cancellation) skips
        // them, leaving any in-flight ToolInvoke at state=Active
        // forever and clients believing the agent is still working.
        // We can't emit signals into a stream that's already errored,
        // so we publish the orphan settle directly through the host
        // Sigil's signal hub before re-raising. Failures during the
        // settle publish are swallowed — we already have the original
        // error to surface, and a corrupt settle shouldn't mask it.
        val orphans = settleOrphanToolInvoke(state, convId)
        orphans.foldLeft(Task.unit) { (acc, sig) =>
          acc.flatMap(_ => sigil.publish(sig).handleError(_ => Task.unit))
        }
      }
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
        val invokeId = Event.id()
        state.activeCalls(callId) = ActiveCall(toolName, invokeId)
        state.activeMessageId = None
        state.currentKind = None
        state.currentArg = None
        // Bug #75 — flag that a tool call happened this turn. The
        // Done handler uses this to distinguish "model emitted plain
        // text and no tool call" (drift; needs framework pushback)
        // from "model emitted plain text in addition to tool calls"
        // (some providers leak narration alongside tool args; not a
        // policy violation).
        state.sawAnyToolCall = true
        // Bug #56 — mark `respond` / `respond_options` /
        // `respond_field` / `respond_failure` / `no_response` as
        // framework-internal so client UIs can filter the chip
        // (the user-facing speech reaches the wire as a `Message`
        // + `MessageDelta` already; the tool chip would render the
        // same content twice). The framework's silent-turn
        // detector still sees these events for its lifecycle
        // tracking — this is purely a wire-level rendering hint.
        val isInternal = Orchestrator.UserVisibleTerminalTools.contains(toolName)
        val invoke = ToolInvoke(
          toolName = ToolName(toolName),
          participantId = caller,
          conversationId = convId,
          topicId = topicId,
          _id = invokeId,
          state = EventState.Active,
          internal = isInternal,
          // Sigil bug #167 r5 — persist the wire-level call_id from
          // the provider's response so subsequent turns can render
          // function_call_output with the original id (OpenAI's
          // previous_response_id state matches by call_id).
          callId = Some(callId.value)
        )
        Stream.emits(List(invoke))

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
        val (createMessageSignal, msgId) = state.activeMessageId match {
          case Some(id) => (None, id)
          case None =>
            val id = Event.id()
            state.activeMessageId = Some(id)
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

      case ProviderEvent.ToolCallComplete(callId, input) =>
        val active = state.activeCalls.remove(callId).getOrElse {
          throw new IllegalStateException(
            s"ToolCallComplete($callId) without a preceding ToolCallStart. " +
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
        val toolDeltaPrefix: List[Signal] = List(ToolDelta(
          target = invokeId,
          conversationId = convId,
          input = Some(input),
          state = Some(EventState.Complete),
          internal = isInternal
        ))
        state.activeMessageId match {
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
                val parsed = MarkdownContentParser.parse(r.content)
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
              // Bug #87 — dedupe identical (toolName + canonical args
              // JSON) calls within the same completion. When the model
              // emits N parallel function_calls that share a key
              // (parallel hedging, or the same retry hitting both a
              // generic and specific path), execute the work once and
              // route the duplicates to a synthesized Tool-role
              // pointer Message paired to their own ToolInvoke. Wire
              // shape stays well-formed; the underlying tool runs once.
              val toolName = active.toolName
              val argsKey = canonicalArgsKey(toolName, input)
              state.dispatchedKeys.get(argsKey) match {
                case Some(firstInvokeId) =>
                  val dupeMsg = Message(
                    participantId  = caller,
                    conversationId = convId,
                    topicId        = topicId,
                    role           = MessageRole.Tool,
                    content        = Vector(ResponseContent.Text(
                      s"(deduplicated: identical `$toolName` call already dispatched in this completion " +
                        s"as ${firstInvokeId.value}; see that result)"
                    )),
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
                      modelId             = Some(request.modelId)
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
                  case _ =>
                    Task.pure(sig)
                }
              }
              // Bug #167 — guarantee at least one result-shaped event
              // (`role == MessageRole.Tool`) is emitted for non-atomic-
              // content tools. Atomic content tools (respond,
              // respond_options, …) emit a `Standard`-role Message that
              // the frame renderer pairs with a synthetic empty
              // `function_call_output` (sigil bug #19), so the wire
              // shape is already satisfied for those. Every other tool
              // is expected to produce a `Tool`-role Message or
              // `ToolResults` event; if the tool's executeTyped path
              // returned a stream that completed without emitting any
              // result-shaped event (silent-failure path that swallowed
              // an error into `Task.unit`, or a transform that filtered
              // results out), inject a synthetic Tool-role Failure so
              // the wire's function_call ↔ function_call_output pairing
              // stays valid.
              val needsResultGuard = !CoreTools.atomicContentToolNames.contains(ToolName(active.toolName))
              val guarded: Stream[Signal] =
                if (!needsResultGuard) tracked
                else Stream.force(tracked.toList.map { collected =>
                  val hasResult = collected.exists {
                    case e: Event if e.role == MessageRole.Tool => true
                    case _                                       => false
                  }
                  if (hasResult) Stream.emits(collected)
                  else Stream.emits(collected :+ Message(
                    participantId  = caller,
                    conversationId = convId,
                    topicId        = topicId,
                    role           = MessageRole.Tool,
                    content        = Vector(ResponseContent.Text(
                      s"Tool '${active.toolName}' executed but emitted no result. " +
                        "This is typically a tool-side bug (executeTyped swallowed an error " +
                        "without surfacing it). The framework's paired-call wire contract " +
                        "still requires a result entry, so this synthetic placeholder fills it."
                    )),
                    state          = EventState.Complete,
                    disposition    = MessageDisposition.Failure(recoverable = true),
                    visibility     = MessageVisibility.Agents,
                    origin         = Some(invokeId)
                  ))
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

      case ProviderEvent.Usage(usage) =>
        // Bug #55 — fall back to the last user-visible Message id when
        // no streaming activeMessageId exists. Tool-call-only models
        // (llama.cpp's grammar-constrained respond invocations) build
        // the agent's user-visible Message inside the tool's
        // `executeTyped`, so the streaming-text path never fires; this
        // fallback lets per-turn token usage land on that Message.
        state.activeMessageId.orElse(state.lastUserVisibleMessageId) match {
          case Some(msgId) =>
            Stream.emits(List(MessageDelta(target = msgId, conversationId = convId, usage = Some(usage))))
          case None => Stream.empty
        }

      case ProviderEvent.TextDelta(text) =>
        // Bug #75 — accumulate plain-text fragments so the Done
        // handler can produce a diagnostic if the model never
        // wrapped them in a tool call. Don't emit anything here —
        // the diagnostic is post-stream.
        state.plainTextBuffer.append(text)
        Stream.empty
      case ProviderEvent.ThinkingDelta(_)                 => Stream.empty
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
        val closeOrphan = settleOrphanToolInvoke(state, convId)
        // Sigil bug #123 — when a streaming completion settles with
        // `finish_reason=length` mid-tool-call-args, the provider
        // truncated the args before the JSON closed. The orphan
        // settle's `ToolDelta(input=None, state=Complete)` leaves
        // the function_call without a paired Tool-role event, so
        // the frame renderer surfaces the misleading "tool's
        // executeTyped — please report it" framework error and the
        // agent has no signal to refine its inputs.
        //
        // Emit a Tool-role `Failure` Message paired to each orphan
        // invoke (via `origin`) with a concrete diagnosis the agent
        // can act on. Closes the function_call ↔ function_call_output
        // pair AND replaces the bogus "report it" message.
        val truncationDiagnostic: List[Signal] = stopReason match {
          case StopReason.MaxTokens if orphanedCalls.nonEmpty =>
            orphanedCalls.flatMap { active =>
              val reason =
                s"Your `${active.toolName}` call was truncated at max_tokens — the arguments " +
                  "never fully arrived, so the tool didn't run. Reduce argument size (e.g. don't " +
                  "inline whole files into a `text:` field — read the file separately), split the " +
                  "work across multiple smaller calls, or request a larger max_tokens for this turn."
              val diag = Message(
                participantId  = caller,
                conversationId = convId,
                topicId        = topicId,
                role           = MessageRole.Tool,
                content        = Vector(ResponseContent.Text(reason)),
                disposition    = MessageDisposition.Failure(recoverable = true),
                state          = EventState.Complete,
                visibility     = MessageVisibility.Agents,
                origin         = Some(active.invokeId)
              )
              List[Signal](diag)
            }
          case _ => Nil
        }
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
        val orphanSettle = settleOrphanToolInvoke(state, convId, error = Some(msg))
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
        Stream.emits(orphanSettle ++ preludeSignals :+ (errorMessage: Signal))
    }
  }

  /**
   * Emit a synthetic terminal `ToolDelta` for any in-flight
   * `ToolInvoke` so it lands at `state=Complete` instead of getting
   * stuck at `Active`. Idempotent — if no tool call is open the
   * returned list is empty. Clears `state.activeToolInvokeId` /
   * `state.activeToolName` either way.
   */
  private def settleOrphanToolInvoke(state: State,
                                     convId: lightdb.id.Id[Conversation],
                                     error: Option[String] = None): List[Signal] = {
    val closes = state.activeCalls.values.toList.map { active =>
      ToolDelta(
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
      ): Signal
    }
    state.activeCalls.clear()
    closes
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
