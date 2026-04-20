package sigil.orchestrator

import rapid.Stream
import sigil.Sigil
import sigil.conversation.Conversation
import sigil.event.{Event, Message, ToolInvoke}
import sigil.provider.{Provider, ProviderEvent, ProviderRequest}
import sigil.signal.{ContentDelta, ContentKind, EventState, MessageDelta, Signal, ToolDelta}
import sigil.tool.{Tool, ToolContext, ToolInput}

/**
 * Stateless, per-invocation bridge between the provider wire stream and the
 * sigil `Signal` vocabulary.
 *
 *   - Consumes `Stream[ProviderEvent]` from a [[Provider]]
 *   - Emits `Stream[Signal]` — `Event`s (ToolInvoke, Message, …) and `Delta`s
 *     (ToolDelta, MessageDelta) in the order a subscriber can apply to
 *     reconstruct the durable state
 *   - Runs `tool.execute(input, ToolContext)` for atomic tools (those for
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
    val conversation: Conversation = new Conversation {
      override val id = request.conversationId
    }
    val toolsByName: Map[String, Tool[? <: ToolInput]] = request.tools.map(t => t.schema.name -> t).toMap
    val state = new State()

    provider(request).flatMap(pe => translate(pe, sigil, request, conversation, toolsByName, state))
  }

  private final class State {
    var activeToolName: Option[String] = None
    var activeToolInvokeId: Option[lightdb.id.Id[Event]] = None
    var activeMessageId: Option[lightdb.id.Id[Event]] = None
    var currentKind: Option[ContentKind] = None
    var currentArg: Option[String] = None
    /** Accumulated text for the current open content block. Flushed as a
      * `ContentDelta(complete = true, delta = full text)` when the block
      * closes (next ContentBlockStart or ToolCallComplete). */
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

    event match {
      case ProviderEvent.ToolCallStart(_, toolName) =>
        val invokeId = Event.id()
        state.activeToolName = Some(toolName)
        state.activeToolInvokeId = Some(invokeId)
        state.activeMessageId = None
        state.currentKind = None
        state.currentArg = None
        val invoke = ToolInvoke(
          toolName = toolName,
          participantId = caller,
          conversationId = convId,
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
            Stream.emits(closeBlock ::: List[Signal](toolDelta, messageDelta))
          case None =>
            // Atomic path — run execute and forward resulting Events.
            val tool = toolsByName.get(state.activeToolName.getOrElse(""))
            val executed: Stream[Signal] = tool match {
              case Some(t) => executeAtomic(t, input, ToolContext(sigil, request.chain, conversation))
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

      case ProviderEvent.TextDelta(_)     => Stream.empty
      case ProviderEvent.ThinkingDelta(_) => Stream.empty
      case ProviderEvent.Done(_)          => Stream.empty
      case ProviderEvent.Error(_)         => Stream.empty
    }
  }

  /** Dispatches an atomic tool's `execute` and forwards its events as signals. */
  private def executeAtomic[I <: ToolInput](tool: Tool[I], input: ToolInput, context: ToolContext): Stream[Signal] = {
    val typedInput = input.asInstanceOf[I]
    tool.execute(typedInput, context).map(ev => ev: Signal)
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
      kind  <- state.currentKind
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
