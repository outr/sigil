package sigil

import lightdb.id.Id
import sigil.conversation.{Conversation, TurnInput}
import sigil.event.Event
import sigil.participant.ParticipantId
import sigil.signal.ToolProgress
import sigil.tool.ToolName

/**
 * Runtime context supplied at the boundary of a unit of work — both
 * [[sigil.tool.Tool.execute]] and [[sigil.participant.Participant.process]]
 * receive one. Identifies the active Sigil, the chain-of-responsibility, the
 * conversation being acted upon, and the curated view/input the caller should
 * use to compose provider requests or inspect prior events.
 *
 * @param sigil               the active Sigil instance. Consulted for
 *                            configuration (`testMode`), shared resources
 *                            (`withDB`), or anything else the app exposes on
 *                            its Sigil implementation. Per-invocation, never
 *                            cached.
 * @param chain               the chain of responsibility — the participant
 *                            that originated the request followed by each
 *                            participant that propagated it. `chain.last` is
 *                            the immediate caller (the participant actually
 *                            doing the work); earlier entries are the
 *                            authority lineage.
 * @param conversation        the conversation being acted upon.
 * @param turnInput           the curator's per-turn output — frames,
 *                            per-participant projections, memory /
 *                            summary / information selections plus any
 *                            app-supplied overlays. The single
 *                            self-contained DTO every provider call
 *                            renders from (bug #26 — replaces the
 *                            prior `conversationView` projection).
 * @param currentAgentStateId the id of the active [[sigil.event.AgentState]]
 *                            for the agent processing this turn, when
 *                            applicable. Set by the framework's dispatcher
 *                            so an agent can target lifecycle deltas
 *                            (Thinking → Typing transitions) at its own
 *                            AgentState without a DB lookup. `None` when no
 *                            AgentState is active (e.g., user-typed Message
 *                            being published externally).
 * @param currentMessageId    the id of the in-flight assistant
 *                            [[sigil.event.Message]] for the current turn,
 *                            when one is being assembled. Set by the
 *                            orchestrator when content/atomic block tool
 *                            output starts arriving so atomic content
 *                            tools (`respond_options`, `respond_field`,
 *                            `respond_failure`) can target their
 *                            [[sigil.signal.MessageDelta]] at the same
 *                            in-flight Message that the markdown content
 *                            stream is being merged into. `None` before
 *                            any content has been emitted on this turn.
 * @param currentToolInvokeId the id of the [[sigil.event.ToolInvoke]] this
 *                            execution belongs to — set by the
 *                            orchestrator when dispatching to
 *                            `executeTyped` so tools can publish
 *                            mid-execution [[sigil.signal.ToolProgress]]
 *                            pulses via [[reportProgress]] without
 *                            threading the id manually. `None` outside a
 *                            tool dispatch (Participant.process,
 *                            settled-effect callbacks, etc.).
 * @param currentToolName     the name of the tool currently dispatching,
 *                            paired with `currentToolInvokeId`. Used as
 *                            the default `attribution` on
 *                            `ToolProgress` pulses so clients can
 *                            label the chip without an extra lookup.
 *
 * Chain is runtime-only — never persisted on Events. Each invocation's caller
 * supplies it fresh.
 */
case class TurnContext(sigil: Sigil,
                       chain: List[ParticipantId],
                       conversation: Conversation,
                       turnInput: TurnInput,
                       currentAgentStateId: Option[Id[Event]] = None,
                       currentMessageId: Option[Id[Event]] = None,
                       correlationId: String = TurnContext.freshCorrelationId(),
                       currentToolInvokeId: Option[Id[Event]] = None,
                       currentToolName: Option[ToolName] = None) {

  /**
   * The participant currently acting — `chain.last`.
   */
  def caller: ParticipantId = chain.last

  /**
   * Convenience: derive a transient [[sigil.conversation.ConversationView]]
   * from `turnInput`. The hot path no longer carries a persisted view;
   * `turnInput` packs `conversationId` + `frames` +
   * `participantProjections` directly. Callers that prefer the older
   * "view" shape access it through this accessor.
   */
  def conversationView: _root_.sigil.conversation.ConversationView = turnInput.conversationView

  /**
   * Publish a mid-execution progress pulse for the currently-dispatching
   * tool. Reads `currentToolInvokeId` to wire the correlation id; no-op
   * outside a tool dispatch (no chip to attach to).
   *
   * @param message status string to render on the chip ("Imported 500 / 7,300
   *                events", "Compiling step 3/7", …).
   * @param percent optional `0.0..1.0` fraction. Clients render a thin
   *                progress bar when present, an indeterminate spinner
   *                otherwise. Caller responsible for clamping; the framework
   *                publishes whatever's passed.
   */
  def reportProgress(message: String, percent: Option[Double] = None): rapid.Task[Unit] =
    currentToolInvokeId match {
      case None => rapid.Task.unit
      case Some(invokeId) =>
        sigil.publish(ToolProgress(
          invokeId       = invokeId,
          conversationId = conversation.id,
          message        = message,
          percent        = percent,
          attribution    = currentToolName
        )).map(_ => ())
    }
}

object TurnContext {
  /** Generate a fresh correlation id for a turn that didn't inherit one
   * from an upstream context (HTTP request, queued job, etc.). The id
   * is opaque short-form — 8 chars sliced from `rapid.Unique()`; apps
   * that wire scribe MDC integration can read
   * [[TurnContext.correlationId]] and stamp it on every log line
   * emitted during the turn.
   *
   * Apps fronting Sigil with their own ingress (HTTP server, message
   * bus) should construct `TurnContext` with a `correlationId`
   * derived from the inbound request id so the entire flow — wire
   * call → orchestrator → tool execution → settled effects — shares
   * one trace id. */
  def freshCorrelationId(): String = rapid.Unique().take(8)
}
