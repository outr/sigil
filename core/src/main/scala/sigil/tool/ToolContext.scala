package sigil.tool

import lightdb.id.Id
import sigil.TurnContext
import sigil.conversation.Conversation
import sigil.db.Model
import sigil.event.{Event, LogLevel, ToolLog}
import sigil.participant.ParticipantId
import sigil.signal.{ToolDelta, ToolProgress}

import java.util.concurrent.atomic.AtomicReference

/**
 * Per-dispatch context handed to a tool's `executeResult` / `executeOutput`
 * body. Narrower than [[TurnContext]] — exposes only what tool code legitimately
 * needs, with the tool-only side-channel methods ([[emit]], [[reportProgress]],
 * [[toolLog]], [[setSummary]]) guaranteed by construction to have an
 * [[invokeId]] to pair against. No silent no-ops.
 *
 * Constructed by the framework at tool dispatch ([[Tool.execute]]); not built
 * by callers. The underlying [[TurnContext]] is exposed via [[turn]] for the
 * less-common turn-scope fields (`turnInput`, `routedModelId`,
 * `correlationId`, the `discoveredCapabilities` cache).
 *
 * @param turn             the broader turn-scope context this dispatch belongs
 *                         to. Tool bodies reach through for less-common fields.
 * @param invokeId         the [[sigil.event.ToolInvoke]] id this execution
 *                         is paired with. Always set — the framework only
 *                         constructs `ToolContext` when this is known.
 * @param toolName         the name of the tool currently dispatching. Used as
 *                         the default `attribution` on `ToolProgress` pulses.
 * @param currentMessageId the id of an in-flight assistant
 *                         [[sigil.event.Message]] when the orchestrator
 *                         pre-allocated one before dispatching an atomic
 *                         content tool (`respond_options`, `respond_field`,
 *                         `respond_failure`). Lets those tools adopt the
 *                         same Message id the streaming content path was
 *                         building toward, instead of opening a second
 *                         user-visible Message. `None` for ordinary tools.
 */
final case class ToolContext(turn: TurnContext,
                             invokeId: Id[Event],
                             toolName: ToolName,
                             currentMessageId: Option[Id[Event]] = None,
                             private val emittedEventsRef: AtomicReference[Vector[Event]] =
                               new AtomicReference(Vector.empty[Event])) {

  // ---- pass-throughs for fields every tool body reads ----

  def sigil: _root_.sigil.Sigil = turn.sigil

  /**
   * Whether large results are bounded/externalized (agent path) or captured
   * in full (workflow step execution). See [[TurnContext.overflowLargeResults]].
   */
  def overflowLargeResults: Boolean = turn.overflowLargeResults
  def chain: List[ParticipantId] = turn.chain
  def caller: ParticipantId = turn.caller
  def conversation: Conversation = turn.conversation

  /**
   * Sigil #277 — the Model this dispatch's parent turn is operating
   * against. Always set (it's required on the underlying TurnContext).
   * Tools read `model.contextLength`, `model.pricing`, etc. directly
   * off the record; previously they had to round-trip through the
   * registry via the old `Option[Id[Model]]`.
   */
  def model: Model = turn.model

  /**
   * Convenience — `model._id`.
   */
  def modelId: Id[Model] = turn.modelId
  def turnInput: _root_.sigil.conversation.TurnInput = turn.turnInput

  // ---- tool-only side channel ----

  /**
   * Publish a mid-execution progress pulse for this tool. Renders on the
   * tool-call chip without expanding it — transient Notice; no DB write.
   * Distinct from [[setSummary]], which is durable invoke state.
   *
   * @param message status string to render on the chip ("Imported 500 / 7,300
   *                events", "Compiling step 3/7", …).
   * @param percent optional `0.0..1.0` fraction. Clients render a thin
   *                progress bar when present, an indeterminate spinner
   *                otherwise.
   */
  def reportProgress(message: String, percent: Option[Double] = None): rapid.Task[Unit] =
    sigil.publish(ToolProgress(
      invokeId = invokeId,
      conversationId = conversation.id,
      message = message,
      percent = percent,
      attribution = Some(toolName)
    )).map(_ => ())

  /**
   * Emit one [[ToolLog]] line paired to this tool's invoke. Append-only
   * durable Event — survives reload + replay. Use for "what is the underlying
   * process actually doing right now?" output; use [[reportProgress]] for
   * "where am I in the task?" status.
   */
  def toolLog(content: String, level: LogLevel = LogLevel.Info): rapid.Task[Unit] =
    sigil.publish(ToolLog(
      content = content,
      level = level,
      participantId = caller,
      conversationId = conversation.id,
      topicId = conversation.currentTopicId,
      origin = Some(invokeId)
    )).map(_ => ())

  /**
   * Update the inline tool-call chip summary across this tool's execution
   * arc. Tool authors call this at meaningful points — start of work,
   * mid-flight progress, final result — to drive what users see on the chip
   * without expanding it. Each call publishes a [[ToolDelta]] that folds the
   * new value onto [[sigil.event.ToolInvoke.summary]]; the persisted invoke
   * always carries the most recent string.
   *
   * Distinct from [[reportProgress]]: progress pulses are transient Notices
   * (no DB write, fade as new ones arrive); the summary is durable invoke
   * state and persists across reload.
   */
  def setSummary(value: String): rapid.Task[Unit] =
    sigil.publish(ToolDelta(
      target = invokeId,
      conversationId = conversation.id,
      summary = Some(value)
    )).map(_ => ())

  /**
   * Record an ancillary durable [[Event]] from inside this tool's body —
   * a durable event that is *not* the tool's result.
   *
   * A tool's result event is constructed by the framework from the returned
   * [[ToolResult]]; a tool never emits it directly. But a few tools
   * legitimately emit *other* durable events as part of their effect —
   * `change_mode` emits a `ModeChange`, `respond` emits the user-visible
   * reply `Message` and `TopicChange`s. Those go through `emit`.
   *
   * `emit` does NOT publish — it buffers the event onto this context.
   * [[Tool.execute]] drains the buffer into the tool's result stream
   * (ancillary events first, then the framework-built result event), so
   * every caller sees one ordered stream and there is exactly one publish
   * path.
   */
  def emit(event: Event): rapid.Task[Unit] =
    rapid.Task {
      val _ = emittedEventsRef.updateAndGet(_ :+ event)
      ()
    }

  /**
   * Snapshot of the ancillary durable events this tool emitted via [[emit]],
   * in emission order. Read by [[Tool.execute]] after `executeResult`
   * resolves so the framework can drain the buffer into its result stream.
   */
  def emittedEvents: List[Event] = emittedEventsRef.get().toList
}
