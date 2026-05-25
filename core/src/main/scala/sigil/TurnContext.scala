package sigil

import lightdb.id.Id
import lightdb.time.Timestamp
import sigil.conversation.{Conversation, DiscoveredCapability, TurnInput}
import sigil.db.Model
import sigil.event.Event
import sigil.participant.ParticipantId
import sigil.tool.ToolName

import java.util.concurrent.atomic.AtomicReference

/**
 * Turn-scope runtime context supplied at the boundary of a unit of work.
 * Handed to [[sigil.participant.Participant.process]], to provider routing,
 * to role-source resolution, and to the framework's pre-dispatch wiring.
 *
 * Tool bodies receive a narrower [[sigil.tool.ToolContext]] — that type
 * wraps a `TurnContext` and adds the tool-only side channel (progress, log,
 * emit, setSummary) plus guaranteed-set fields (`invokeId`, `toolName`).
 * The tool-side methods used to live on `TurnContext` with silent no-op
 * fallbacks when `currentToolInvokeId` was `None`; they moved to
 * `ToolContext` so the type tells the truth about which methods work where.
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
 * @param model               the [[sigil.db.Model]] driving this turn —
 *                            resolved at the runtime boundary
 *                            ([[sigil.Sigil.runAgentLoop]]) via
 *                            [[sigil.cache.ModelRegistry]] and threaded
 *                            through every per-turn carrier (sigil #277).
 *                            Atomic content tools (`respond`,
 *                            `respond_options`, …) stamp their emitted
 *                            [[sigil.event.Message]]s with `model._id`;
 *                            renderers / cost projection / strategy
 *                            callbacks read other per-model facts off the
 *                            record directly. The pre-route classifier
 *                            stub uses the agent's nominal Model — even
 *                            paths that haven't picked a routed model yet
 *                            hold a real Model in hand.
 *
 * Chain is runtime-only — never persisted on Events. Each invocation's caller
 * supplies it fresh.
 */
case class TurnContext(sigil: Sigil,
                       chain: List[ParticipantId],
                       conversation: Conversation,
                       turnInput: TurnInput,
                       /** Sigil #277 — required Model record. */
                       model: Model,
                       currentAgentStateId: Option[Id[Event]] = None,
                       correlationId: String = TurnContext.freshCorrelationId(),
                       isGreeting: Boolean = false,
                       /** Sigil bug #125 — when `true`, the framework forces
                         * the provider's `tool_choice` to `respond` for this
                         * turn so the model can't pick another tool. Set by
                         * the iteration-cap soft-stop path to synthesise a
                         * reply from the agent's gathered context rather than
                         * discarding it via [[sigil.AgentRunawayException]]. */
                       forceResponseSynthesis: Boolean = false,
                       /** Per-agent-loop cache of `find_capability` matches,
                         * keyed by the normalised query. Populated when the
                         * agent invokes `find_capability`; rendered into the
                         * "Capabilities you've already discovered" section of
                         * the system prompt so subsequent iterations within
                         * the same loop don't re-run discovery for tools the
                         * agent has already seen.
                         *
                         * NOT persisted. The same `AtomicReference` is shared
                         * across every iteration's `TurnContext` of one agent
                         * loop, so an entry recorded in iteration 1 is
                         * visible on iteration 2. When the loop terminates,
                         * the reference goes out of scope and a new loop
                         * starts with a fresh empty map — preventing the
                         * cross-turn prompt pollution where unrelated tools
                         * from a prior task surfaced on every subsequent
                         * turn. */
                       discoveredCapabilitiesRef: AtomicReference[Map[String, DiscoveredCapability]] =
                         new AtomicReference(Map.empty[String, DiscoveredCapability])) {

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

  /** Convenience — `model._id`. Use when only the id is needed (wire
    * serialization, projection write-back, event stamping). */
  def modelId: Id[Model] = model._id

  /** Snapshot of the per-agent-loop `find_capability` cache. Renderers
    * read this when assembling the "Capabilities you've already
    * discovered" section of the system prompt. The map is empty on a
    * fresh loop and grows as the agent invokes `find_capability`
    * within the loop's iterations. */
  def discoveredCapabilities: Map[String, DiscoveredCapability] =
    discoveredCapabilitiesRef.get()

  /** Record a `find_capability` result against the per-loop cache.
    * Called by [[sigil.tool.core.FindCapabilityTool]] after discovery
    * runs; preserves `firstSeen` across re-issues of the same query
    * and advances `lastSeen`. No-op when the query is empty (matches
    * the prior persisted-projection behaviour). */
  def recordDiscovery(query: String, matches: List[ToolName]): Unit =
    if (query.nonEmpty) {
      val now = Timestamp()
      val _ = discoveredCapabilitiesRef.updateAndGet { current =>
        current.updatedWith(query) {
          case Some(existing) => Some(existing.copy(matches = matches, lastSeen = now))
          case None           => Some(DiscoveredCapability(matches = matches, firstSeen = now, lastSeen = now))
        }
      }
    }

  /** Drop every entry from the per-loop `find_capability` cache.
    * Invoked when the agent settles its turn via a terminal respond-
    * family call so the next iteration (or, more typically, the next
    * agent loop) starts with a clean prompt. The natural end-of-loop
    * release also drops the cache implicitly because the
    * `AtomicReference` goes out of scope; this explicit clear is the
    * traceable in-loop signal. */
  def clearDiscoveredCapabilities(): Unit =
    discoveredCapabilitiesRef.set(Map.empty)
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
