package sigil.orchestrator

import lightdb.id.Id
import lightdb.time.Timestamp
import rapid.{Stream, Task}
import sigil.Sigil
import sigil.conversation.{Conversation, Topic}
import sigil.event.{Event, Message, TopicChange, TopicChangeKind, ToolInvoke}
import sigil.participant.ParticipantId
import sigil.provider.{Provider, ProviderEvent, ProviderRequest}
import sigil.signal.{ContentDelta, ContentKind, EventState, MessageDelta, Signal, StateDelta, ToolDelta}
import sigil.tool.ToolName
import sigil.tool.model.{RespondInput, TopicChangeType}
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

  def process(sigil: Sigil, provider: Provider, request: ProviderRequest): Stream[Signal] = {
    val conversation: Conversation = Conversation(
      currentTopicId = request.currentTopicId,
      _id = request.conversationId
    )
    // Keyed by wire-level string so the provider's `activeToolName`
    // lookup stays a plain String comparison (the provider reports tool
    // names via the raw JSON wire).
    val toolsByName: Map[String, Tool[? <: ToolInput]] = request.tools.map(t => t.schema.name.value -> t).toMap
    val state = new State()

    provider(request).flatMap(pe => translate(pe, sigil, request, conversation, toolsByName, state))
  }

  final private class State {
    var activeToolName: Option[String] = None
    var activeToolInvokeId: Option[lightdb.id.Id[Event]] = None
    var activeMessageId: Option[lightdb.id.Id[Event]] = None
    var currentKind: Option[ContentKind] = None
    var currentArg: Option[String] = None

    /**
     * Accumulated text for the current open content block. Flushed as a
     * `ContentDelta(complete = true, delta = full text)` when the block
     * closes (next ContentBlockStart or ToolCallComplete).
     */
    val currentBuffer: StringBuilder = new StringBuilder
  }

  private def translate(event: ProviderEvent,
                        sigil: Sigil,
                        request: ProviderRequest,
                        conversation: Conversation,
                        toolsByName: Map[String, Tool[? <: ToolInput]],
                        state: State): Stream[Signal] = {
    val caller = request.chain.lastOption.getOrElse {
      throw new IllegalStateException("ProviderRequest.chain is empty; orchestrator needs at least one participant.")
    }
    val convId = request.conversationId
    val topicId = request.currentTopicId

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
          content = Some(ContentDelta(kind = kind, arg = state.currentArg, complete = false, delta = text))
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
            // Streaming path — close the open content block (if any), close the Message, close the ToolInvoke.
            val closeBlock = closeCurrentBlock(state, convId)
            val messageDelta = MessageDelta(target = msgId, conversationId = convId, state = Some(EventState.Complete))
            // If the streamed tool was `respond`, resolve topic-change
            // intent (switch / rename / no-op) against the current Topic
            // record and emit a TopicChange prelude if one fires. This is
            // async (DB read + possibly an insert for a new Topic), so the
            // whole emit pivots through Stream.force.
            (state.activeToolName, input) match {
              case (Some("respond"), r: RespondInput) =>
                Stream.force(
                  resolveTopicPrelude(sigil, r, caller, conversation).map { prelude =>
                    Stream.emits(prelude ::: closeBlock ::: List[Signal](toolDelta, messageDelta))
                  }
                )
              case _ =>
                Stream.emits(closeBlock ::: List[Signal](toolDelta, messageDelta))
            }
          case None =>
            // Atomic path — run execute and forward resulting Events.
            val tool = toolsByName.get(state.activeToolName.getOrElse(""))
            val executed: Stream[Signal] = tool match {
              case Some(t) => executeAtomic(
                  t,
                  input,
                  TurnContext(
                    sigil = sigil,
                    chain = request.chain,
                    conversation = conversation,
                    conversationView = request.turnInput.conversationView,
                    turnInput = request.turnInput
                  )
                )
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

      case ProviderEvent.TextDelta(_) => Stream.empty
      case ProviderEvent.ThinkingDelta(_) => Stream.empty
      case ProviderEvent.Done(_) => Stream.empty
      case ProviderEvent.Error(_) => Stream.empty
    }
  }

  /**
   * Dispatches an atomic tool's `execute` and forwards its events as
   * signals. Each event the tool emits is followed by a
   * `StateDelta(Complete)` so the uniform Active → Complete lifecycle
   * holds for atomic tools too: subscribers see a reactive pulse on the
   * event, then a settle via the delta. Tools that explicitly emit
   * `state = Complete` still get a closing `StateDelta`, which is an
   * idempotent no-op (the event is already Complete).
   */
  private def executeAtomic[I <: ToolInput](tool: Tool[I], input: ToolInput, context: TurnContext): Stream[Signal] = {
    val typedInput = input.asInstanceOf[I]
    tool.execute(typedInput, context).flatMap { ev =>
      Stream.emits(List[Signal](
        ev,
        StateDelta(target = ev._id, conversationId = ev.conversationId, state = EventState.Complete)
      ))
    }
  }

  /**
   * Decide whether a respond call's `topicChangeType` + `topic` implies a
   * topic transition, and if so return the [[TopicChange]] (plus its
   * settling [[StateDelta]]) as a ready-to-emit prelude.
   *
   * The LLM's categorical choice is authoritative; the label comparison
   * is a safety net for inconsistent input.
   *
   *   - `TopicChangeType.NoChange`                   → empty prelude.
   *   - `r.topic == currentLabel` (regardless of type) → empty prelude.
   *     (Label is the source of truth when the LLM declares a change but
   *     then supplies the unchanged label.)
   *   - `TopicChangeType.Change` + different label   → find existing Topic
   *     in this conversation whose label (case-insensitively) matches;
   *     if found, switch to it; otherwise insert a new Topic record and
   *     switch. Emits `TopicChange(Switch, ...)`.
   *   - `TopicChangeType.Update` + different label   → if the current
   *     Topic is not `labelLocked`, update its label in-place. Emits
   *     `TopicChange(Rename, ...)`. Locked Topic → empty prelude.
   */
  private def resolveTopicPrelude(sigil: Sigil,
                                  r: RespondInput,
                                  caller: ParticipantId,
                                  conversation: Conversation): Task[List[Signal]] =
    r.topicChangeType match {
      case TopicChangeType.NoChange => Task.pure(Nil)
      case TopicChangeType.Change | TopicChangeType.Update =>
        sigil.withDB(_.topics.transaction(_.get(conversation.currentTopicId))).flatMap {
          case None =>
            // Current topic record missing — nothing to compare against; skip.
            Task.pure(Nil)
          case Some(current) =>
            if (r.topic.isEmpty || r.topic == current.label) Task.pure(Nil)
            else r.topicChangeType match {
              case TopicChangeType.Change =>
                resolveSwitch(sigil, r, caller, conversation, current)
              case TopicChangeType.Update if !current.labelLocked =>
                resolveRename(sigil, r, caller, conversation, current)
              case _ => Task.pure(Nil) // Update on a labelLocked topic: suppressed.
            }
        }
    }

  private def resolveSwitch(sigil: Sigil,
                            r: RespondInput,
                            caller: ParticipantId,
                            conversation: Conversation,
                            current: Topic): Task[List[Signal]] = {
    val findExisting = sigil.withDB(_.topics.transaction { tx =>
      import lightdb.filter.*
      tx.query.filter(_.conversationId === conversation._id).toList
    })
    findExisting.flatMap { all =>
      val matched = all.find(t => t.label.equalsIgnoreCase(r.topic) && t._id != current._id)
      val topicTask: Task[Topic] = matched match {
        case Some(existing) => Task.pure(existing)
        case None =>
          val created = Topic(
            conversationId = conversation._id,
            label = r.topic,
            createdBy = caller
          )
          sigil.withDB(_.topics.transaction(_.upsert(created)))
      }
      topicTask.map { topic =>
        val tc = TopicChange(
          kind = TopicChangeKind.Switch(previousTopicId = current._id),
          newLabel = topic.label,
          participantId = caller,
          conversationId = conversation._id,
          topicId = topic._id
        )
        List[Signal](
          tc,
          StateDelta(target = tc._id, conversationId = conversation._id, state = EventState.Complete)
        )
      }
    }
  }

  private def resolveRename(sigil: Sigil,
                            r: RespondInput,
                            caller: ParticipantId,
                            conversation: Conversation,
                            current: Topic): Task[List[Signal]] = {
    val renamed = current.copy(label = r.topic, modified = Timestamp())
    sigil.withDB(_.topics.transaction(_.upsert(renamed))).map { _ =>
      val tc = TopicChange(
        kind = TopicChangeKind.Rename(previousLabel = current.label),
        newLabel = r.topic,
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

  private def kindOf(name: String): ContentKind =
    scala.util.Try(ContentKind.valueOf(name)).getOrElse(ContentKind.Text)

  /**
   * Emit a `MessageDelta` with `ContentDelta(complete = true, delta = full
   * block text)` if a content block is currently open and the buffer has
   * content. Resets the block buffer + kind. The full text closes the block
   * for the DB applier (which appends a fully-formed `ResponseContent`);
   * subscribers that already saw the streaming chunks can treat this as the
   * canonical final form.
   */
  private def closeCurrentBlock(state: State, convId: lightdb.id.Id[Conversation]): List[Signal] = {
    val emit = for {
      msgId <- state.activeMessageId
      kind <- state.currentKind
      if state.currentBuffer.nonEmpty
    } yield {
      val text = state.currentBuffer.toString
      MessageDelta(
        target = msgId,
        conversationId = convId,
        content = Some(ContentDelta(kind = kind, arg = state.currentArg, complete = true, delta = text))
      )
    }
    state.currentBuffer.clear()
    state.currentKind = None
    state.currentArg = None
    emit.toList
  }
}
