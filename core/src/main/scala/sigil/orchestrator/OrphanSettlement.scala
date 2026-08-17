package sigil.orchestrator

import lightdb.id.Id
import lightdb.time.Timestamp
import rapid.{Stream, Task}
import sigil.Sigil
import sigil.conversation.{ContextFrame, Conversation, Topic, TopicShiftResult}
import sigil.event.{Event, Message, MessageDisposition, MessageRole, MessageVisibility, Reasoning, TopicChange, TopicChangeKind, ToolInvoke, ToolOutcome}
import sigil.governor.{OutcomeVerdict, TurnOutcome}
import sigil.participant.ParticipantId
import sigil.provider.{CallId, ConversationRequest, Provider, ProviderEvent, ProviderImage, SchemaDialect, StopReason, XmlToolCallSanitizer}
import sigil.storage.StoredFileCategory
import sigil.signal.{MessageContentDelta, ContentKind, EventState, ImageDelta, MessageDelta, Signal, StateDelta, ThinkingChunk, ToolDelta, XmlToolCallLeak}
import sigil.tool.core.{CoreTools, FindCapabilityInput, RespondFamilyTool, UnknownTool}
import sigil.tool.model.{MarkdownContentParser, RespondInput, ResponseContent}
import sigil.tool.ToolName
import sigil.TurnContext
import sigil.tool.{CachedToolRead, DecodeError, DecodedCall, Freshness, GateContext, JsonInput, RefusalPayload, Tool, ToolExecutor, ToolInput, ToolRoster, WireCall}

/**
 * Turn-end settle guarantee for the orchestrator's in-flight events.
 * Maintains the open-Message registry as signals stream past, sweeps
 * anything still Active when the provider stream terminates, and
 * builds the terminal deltas for an orphaned tool invoke or an
 * orphaned streaming Message. Also owns the content-block close that
 * flushes the current buffer as a completed [[MessageDelta]].
 */
private[orchestrator] object OrphanSettlement {

  /** Maintain `state.openEvents` as signals stream past — the
    * automatic registration that lets the turn-end settle guarantee
    * cover every kind of Active Message without per-kind wiring. An
    * Active Message registers; a settling StateDelta / MessageDelta,
    * or a Message emitted already Complete, deregisters. */
  def trackOpenEvent(state: State, signal: Signal): Unit = signal match {
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
  def settleOpenEvents(state: State, convId: Id[Conversation], failed: Boolean): List[Signal] = {
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
  def settleOrphanToolInvoke(state: State,
                                     convId: lightdb.id.Id[Conversation],
                                     caller: ParticipantId,
                                     topicId: lightdb.id.Id[Topic],
                                     error: Option[String] = None,
                                     reasonFor: ActiveCall => String =
                                       a => s"Tool `${a.toolName}` did not produce a result",
                                     recoverable: Boolean = false): List[Signal] = {
    val signals = state.activeCalls.values.toList.flatMap { active =>
      val isInternal = RespondFamilyTool.containsRaw(active.toolName)
      val synthInvoke: Signal = ToolInvoke(
        toolName       = ToolName.internal(active.toolName),
        participantId  = caller,
        conversationId = convId,
        topicId        = topicId,
        _id            = active.invokeId,
        state          = EventState.Active,
        internal       = isInternal,
        // The call was emitted by the completion this accumulator drained, so
        // an orphan settled out of a batch still replays inside that batch.
        completionId   = Some(state.completionId)
      )
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
   * Settle the in-flight streaming Message that was
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
  def settleOrphanMessage(state: State,
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

  /**
   * Emit a `MessageDelta` with `MessageContentDelta(complete = true, delta = full
   * block text)` if a content block is currently open and the buffer has
   * content. Resets the block buffer + kind. The full text closes the block
   * for the DB applier (which appends a fully-formed `ResponseContent`);
   * subscribers that already saw the streaming chunks can treat this as the
   * canonical final form.
   */
  def closeCurrentBlock(state: State, convId: lightdb.id.Id[Conversation]): List[Signal] = {
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
}