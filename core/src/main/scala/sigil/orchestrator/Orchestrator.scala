package sigil.orchestrator

import lightdb.id.Id
import lightdb.time.Timestamp
import rapid.{Stream, Task}
import sigil.Sigil
import sigil.conversation.{ContextFrame, Conversation, Topic, TopicShiftResult}
import sigil.event.{Event, Message, MessageRole, MessageVisibility, Reasoning, TopicChange, TopicChangeKind, ToolInvoke}
import sigil.participant.ParticipantId
import sigil.provider.{ConversationRequest, Provider, ProviderEvent}
import sigil.signal.{MessageContentDelta, ContentKind, EventState, ImageDelta, MessageDelta, Signal, StateDelta, ToolDelta}
import sigil.tool.model.{MarkdownContentParser, RespondInput, ResponseContent}
import sigil.tool.ToolName
import sigil.TurnContext
import sigil.tool.{Tool, ToolInput}

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

  private final class State {
    var activeToolName: Option[String] = None
    var activeToolInvokeId: Option[lightdb.id.Id[Event]] = None
    /** Bug #69 — track the most recently settled ToolInvoke's id so a
      * `ProviderEvent.Error` arriving after `ToolCallComplete` (e.g. a
      * stream-level error after the tool itself succeeded) can still
      * stamp `origin` on the error Message. Without this fallback the
      * post-completion error path would emit a Tool-role event with
      * no parent, violating the framework's invariant. */
    var lastSettledInvokeId: Option[lightdb.id.Id[Event]] = None
    var activeMessageId: Option[lightdb.id.Id[Event]] = None
    var currentKind: Option[ContentKind] = None
    var currentArg: Option[String] = None
    /** Accumulated text for the current open content block. Flushed as a
      * `MessageContentDelta(complete = true, delta = full text)` when the block
      * closes (next ContentBlockStart or ToolCallComplete). */
    val currentBuffer: StringBuilder = new StringBuilder
    /** Accumulates every text fragment the agent produced across the
      * whole turn. Used by the per-turn memory extractor after `Done`. */
    val turnBuffer: StringBuilder = new StringBuilder
    /** Set to true once the per-turn extractor has been fired for this
      * turn so repeated `Done` events (Usage → Done race) don't
      * double-fire. */
    var extractorFired: Boolean = false
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
      * `ResponseContent.Failure` diagnostic the agent's next
      * iteration reads and can self-correct from. */
    val plainTextBuffer: StringBuilder = new StringBuilder
    var sawAnyToolCall: Boolean = false
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

    event match {
      case ProviderEvent.ToolCallStart(_, toolName) =>
        val invokeId = Event.id()
        state.activeToolName = Some(toolName)
        state.activeToolInvokeId = Some(invokeId)
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
          internal = isInternal
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
            val msg = Message(
              participantId = caller,
              conversationId = convId,
              topicId = topicId,
              content = Vector.empty,
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

      case ProviderEvent.ToolCallComplete(_, input) =>
        val invokeId = state.activeToolInvokeId.getOrElse {
          throw new IllegalStateException("ToolCallComplete without a preceding ToolCallStart.")
        }
        // Clear the active-tool tracking so a subsequent `Done` doesn't
        // re-emit a duplicate orphan-settle ToolDelta. The settle the
        // current handler emits below IS the canonical close for this
        // call; the orphan path is only for streams that ended between
        // `ToolCallStart` and `ToolCallComplete`. Bug #49 surfaced this
        // duplicate in the back-to-back-tool-call test.
        state.activeToolInvokeId = None
        // Bug #69 — record this so a ProviderEvent.Error arriving
        // after settle has a parent to stamp on its error Message.
        state.lastSettledInvokeId = Some(invokeId)
        // Bug #56 — mark the settle delta `internal` for the respond
        // family so clients that filter chips by that flag stay
        // consistent across the call's lifecycle (Active invoke,
        // Complete settle). The flag isn't load-bearing for any
        // framework-internal logic — it's a hint for client UIs.
        val isInternal = state.activeToolName.exists(Orchestrator.UserVisibleTerminalTools.contains)
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
            // settle.
            val closeBlock = closeCurrentBlock(state, convId)
            (state.activeToolName, input) match {
              case (Some("respond"), r: RespondInput) =>
                val parsed = MarkdownContentParser.parse(r.content)
                val settle = MessageDelta(
                  target = msgId,
                  conversationId = convId,
                  contentReplacement = Some(parsed),
                  state = Some(EventState.Complete)
                )
                Stream.force(
                  resolveTopicPrelude(sigil, r.topicLabel, r.topicSummary, caller, conversation, request).map { prelude =>
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
            val tool = toolsByName.get(state.activeToolName.getOrElse(""))
            val toolName = state.activeToolName.getOrElse("?")
            val executed: Stream[Signal] = tool match {
              case Some(t) =>
                Stream.force(
                  Task(executeAtomic(t, input, TurnContext(
                    sigil = sigil,
                    chain = request.chain,
                    conversation = conversation,
                    conversationView = request.turnInput.conversationView,
                    turnInput = request.turnInput
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
              case None => Stream.empty
            }
            Stream.emits(toolDeltaPrefix) ++ executed
        }

      case ProviderEvent.Usage(usage) =>
        state.activeMessageId match {
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
                    state = EventState.Complete
                  )
                ))
            }
        }

      case ProviderEvent.Done(_)                          =>
        // Settle any in-flight tool call before terminating. If the
        // provider stream ends between `ToolCallStart` and
        // `ToolCallComplete` (token-budget cutoff, network drop, mid-args
        // 5xx), the unsettled `ToolInvoke` would otherwise stay
        // `state=Active` in the events store forever, and clients reading
        // that state believe the agent is still working.
        val closeOrphan = settleOrphanToolInvoke(state, convId)
        if (!state.extractorFired) {
          state.extractorFired = true
          fireMemoryExtractor(sigil, request, state).startUnit()
        }
        // Bug #75 — if the model emitted plain text without any tool
        // call this turn, the framework was silently dropping the
        // text and bug-#46's placeholder fired post-loop with no
        // feedback to the model. Now the orchestrator surfaces the
        // drop as a tool-call-shaped diagnostic the agent reads on
        // its next iteration — structurally identical to a script
        // compile error / arg-parse error / validator failure: a
        // Tool-role Message carrying
        // `ResponseContent.Failure(reason, recoverable = true)`,
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
              content        = Vector(ResponseContent.Failure(reason = reason, recoverable = true)),
              state          = EventState.Complete,
              visibility     = MessageVisibility.Agents,
              origin         = Some(syntheticInvokeId)
            )
            List[Signal](syntheticInvoke, diagnosticMessage)
          } else Nil
        Stream.emits(closeOrphan ++ plainTextDiagnostic)
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
        // missing-origin throw downstream. In practice
        // ProviderEvent.Error always arrives with an active invoke
        // (validator failures, malformed tool args), so the fallback
        // path is unreachable.
        val errorOrigin = state.activeToolInvokeId.orElse(state.lastSettledInvokeId)
        val errorMessage = Message(
          participantId  = caller,
          conversationId = convId,
          topicId        = topicId,
          role           = MessageRole.Tool,
          content        = Vector(ResponseContent.Text(s"Provider error: $msg")),
          state          = EventState.Complete,
          visibility     = MessageVisibility.Agents,
          origin         = errorOrigin
        )
        Stream.emits(orphanSettle :+ (errorMessage: Signal))
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
    val closes = state.activeToolInvokeId.toList.map { invokeId =>
      ToolDelta(
        target = invokeId,
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
    state.activeToolInvokeId = None
    state.activeToolName = None
    closes
  }

  /**
   * Fire-and-forget per-turn memory extraction. Extracts the last
   * user-authored text frame from the turn's view, pairs it with
   * the agent's accumulated response text, and hands both to the
   * app-wired [[sigil.conversation.compression.extract.MemoryExtractor]].
   * Failures are logged but never propagate — extraction is best-
   * effort latency-hidden work.
   */
  private def fireMemoryExtractor(sigil: Sigil,
                                  request: ConversationRequest,
                                  state: State): Task[Unit] = {
    val agentResponse = state.turnBuffer.toString.trim
    val caller = request.chain.lastOption
    val userText = request.turnInput.conversationView.frames.reverseIterator
      .collectFirst {
        case t: ContextFrame.Text if !caller.contains(t.participantId) => t.content
      }
      .getOrElse("")
    if (userText.isEmpty && agentResponse.isEmpty) Task.unit
    else sigil.memoryExtractor
      .extract(
        sigil = sigil,
        conversationId = request.conversationId,
        modelId = request.modelId,
        chain = request.chain,
        userMessage = userText,
        agentResponse = agentResponse
      )
      .unit
      .handleError { e =>
        Task(scribe.warn(s"MemoryExtractor failed for conversation ${request.conversationId.value}: ${e.getMessage}"))
      }
  }

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
    tool.execute(input, context).flatMap { ev =>
      val stamped = if (ev.origin.isDefined) ev else ev.withOrigin(Some(originatingInvokeId))
      Stream.emits(List[Signal](
        stamped,
        StateDelta(target = stamped._id, conversationId = stamped.conversationId, state = EventState.Complete)
      ))
    }
  }

  /**
   * Given a respond call's `topicLabel` + `topicSummary`, decide via the
   * framework's two-step classifier (see [[Sigil.classifyTopicShift]])
   * what kind of topic transition — if any — this turn represents, and
   * return the signal prelude to emit (a TopicChange + its settling
   * StateDelta, or nothing).
   *
   * Fast-path shortcuts avoid a classifier call when the answer is
   * unambiguous from label equality:
   *   - proposed label equals the active topic's label → NoChange
   *   - proposed label equals a prior topic's label → Return to that prior
   */
  private def resolveTopicPrelude(sigil: Sigil,
                                  proposedLabel: String,
                                  proposedSummary: String,
                                  caller: ParticipantId,
                                  conversation: Conversation,
                                  request: ConversationRequest): Task[List[Signal]] = {
    val currentEntry = request.currentTopic
    val priors = request.previousTopics

    // Quick label-match shortcuts — no classifier LLM call needed.
    if (proposedLabel.equalsIgnoreCase(currentEntry.label)) {
      return Task.pure(Nil)
    }
    priors.find(_.label.equalsIgnoreCase(proposedLabel)) match {
      case Some(prior) =>
        return Task.pure(emitSwitchSignals(caller, conversation._id, currentEntry.id, prior.id, prior.label, prior.summary))
      case None => // fall through to classifier
    }

    val userMessage = request.turnInput.conversationView.frames.reverseIterator.collectFirst {
      case t: ContextFrame.Text if t.participantId != caller => t.content
    }.getOrElse("")

    sigil.classifyTopicShift(
      modelId = request.modelId,
      chain = request.chain,
      current = currentEntry,
      priors = priors,
      proposedLabel = proposedLabel,
      proposedSummary = proposedSummary,
      userMessage = userMessage
    ).flatMap {
      case TopicShiftResult.NoChange =>
        Task.pure(Nil)
      case TopicShiftResult.Refine =>
        resolveRename(sigil, proposedLabel, proposedSummary, caller, conversation, currentEntry.id)
      case TopicShiftResult.New =>
        resolveNew(sigil, proposedLabel, proposedSummary, caller, conversation, currentEntry.id)
      case TopicShiftResult.Return(prior) =>
        Task.pure(emitSwitchSignals(caller, conversation._id, currentEntry.id, prior.id, prior.label, prior.summary))
    }
  }

  /** Create a fresh Topic record with the proposed label + summary and
    * emit a TopicChange(Switch) pointing to it. */
  private def resolveNew(sigil: Sigil,
                         proposedLabel: String,
                         proposedSummary: String,
                         caller: ParticipantId,
                         conversation: Conversation,
                         previousTopicId: lightdb.id.Id[Topic]): Task[List[Signal]] = {
    val created = Topic(
      conversationId = conversation._id,
      label = proposedLabel,
      summary = proposedSummary,
      createdBy = caller
    )
    sigil.withDB(_.topics.transaction(_.upsert(created))).map { stored =>
      emitSwitchSignals(caller, conversation._id, previousTopicId, stored._id, stored.label, stored.summary)
    }
  }

  /** Update the current Topic's label + summary in place and emit a
    * TopicChange(Rename). Suppressed if `labelLocked` is set. */
  private def resolveRename(sigil: Sigil,
                            proposedLabel: String,
                            proposedSummary: String,
                            caller: ParticipantId,
                            conversation: Conversation,
                            currentTopicId: lightdb.id.Id[Topic]): Task[List[Signal]] = {
    sigil.withDB(_.topics.transaction(_.get(currentTopicId))).flatMap {
      case None => Task.pure(Nil)
      case Some(current) if current.labelLocked => Task.pure(Nil)
      case Some(current) =>
        val renamed = current.copy(label = proposedLabel, summary = proposedSummary, modified = Timestamp())
        sigil.withDB(_.topics.transaction(_.upsert(renamed))).map { _ =>
          val tc = TopicChange(
            kind = TopicChangeKind.Rename(previousLabel = current.label),
            newLabel = proposedLabel,
            newSummary = proposedSummary,
            participantId = caller,
            conversationId = conversation._id,
            topicId = current._id
          )
          List[Signal](
            tc,
            StateDelta(target = tc._id, conversationId = conversation._id, state = EventState.Complete)
          )
        }
    }
  }

  private def emitSwitchSignals(caller: ParticipantId,
                                 convId: lightdb.id.Id[Conversation],
                                 previousTopicId: lightdb.id.Id[Topic],
                                 newTopicId: lightdb.id.Id[Topic],
                                 newLabel: String,
                                 newSummary: String): List[Signal] = {
    val tc = TopicChange(
      kind = TopicChangeKind.Switch(previousTopicId = previousTopicId),
      newLabel = newLabel,
      newSummary = newSummary,
      participantId = caller,
      conversationId = convId,
      topicId = newTopicId
    )
    List[Signal](
      tc,
      StateDelta(target = tc._id, conversationId = convId, state = EventState.Complete)
    )
  }

  private def kindOf(name: String): ContentKind =
    scala.util.Try(ContentKind.valueOf(name)).getOrElse(ContentKind.Text)

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
