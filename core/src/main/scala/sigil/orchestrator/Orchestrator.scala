package sigil.orchestrator

import lightdb.id.Id
import lightdb.time.Timestamp
import rapid.{Stream, Task}
import sigil.Sigil
import sigil.conversation.{ContextFrame, Conversation, Topic, TopicShiftResult}
import sigil.event.{Event, Message, TopicChange, TopicChangeKind, ToolInvoke}
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

  def process(sigil: Sigil, provider: Provider, request: ConversationRequest): Stream[Signal] = {
    val conversation: Conversation = Conversation(
      topics = (request.previousTopics :+ request.currentTopic),
      _id = request.conversationId
    )
    // Keyed by wire-level string so the provider's `activeToolName`
    // lookup stays a plain String comparison (the provider reports tool
    // names via the raw JSON wire).
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
        val invoke = ToolInvoke(
          toolName = ToolName(toolName),
          participantId = caller,
          conversationId = convId,
          topicId = topicId,
          _id = invokeId,
          state = EventState.Active
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
        val toolDelta = ToolDelta(
          target = invokeId,
          conversationId = convId,
          input = Some(input),
          state = Some(EventState.Complete)
        )
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
                    Stream.emits(prelude ::: closeBlock ::: List[Signal](toolDelta, settle))
                  }
                )
              case _ =>
                val settle = MessageDelta(
                  target = msgId,
                  conversationId = convId,
                  state = Some(EventState.Complete)
                )
                Stream.emits(closeBlock ::: List[Signal](toolDelta, settle))
            }
          case None =>
            // Atomic path — run execute and forward resulting Events.
            val tool = toolsByName.get(state.activeToolName.getOrElse(""))
            val executed: Stream[Signal] = tool match {
              case Some(t) => executeAtomic(t, input, TurnContext(
                sigil = sigil,
                chain = request.chain,
                conversation = conversation,
                conversationView = request.turnInput.conversationView,
                turnInput = request.turnInput
              ))
              case None => Stream.empty
            }
            Stream.emits(List[Signal](toolDelta)) ++ executed
        }

      case ProviderEvent.Usage(usage) =>
        state.activeMessageId match {
          case Some(msgId) =>
            Stream.emits(List(MessageDelta(target = msgId, conversationId = convId, usage = Some(usage))))
          case None => Stream.empty
        }

      case ProviderEvent.TextDelta(_)                     => Stream.empty
      case ProviderEvent.ThinkingDelta(_)                 => Stream.empty
      case ProviderEvent.ServerToolStart(_, _, _)         => Stream.empty
      case ProviderEvent.ServerToolComplete(_, _)         => Stream.empty

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
        Stream.emits(closeOrphan)
      case ProviderEvent.Error(_)                         =>
        Stream.emits(settleOrphanToolInvoke(state, convId))
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
                                     convId: lightdb.id.Id[Conversation]): List[Signal] = {
    val closes = state.activeToolInvokeId.toList.map { invokeId =>
      ToolDelta(
        target = invokeId,
        conversationId = convId,
        input = None,
        state = Some(EventState.Complete)
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
  private def executeAtomic(tool: Tool, input: ToolInput, context: TurnContext): Stream[Signal] = {
    tool.execute(input, context).flatMap { ev =>
      Stream.emits(List[Signal](
        ev,
        StateDelta(target = ev._id, conversationId = ev.conversationId, state = EventState.Complete)
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
