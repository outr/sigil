package sigil.provider

import lightdb.id.Id
import lightdb.time.Timestamp
import sigil.conversation.{Conversation, DiscoveredCapability, TopicEntry, TurnInput}
import sigil.db.Model
import sigil.participant.ParticipantId
import sigil.tool.{Tool, ToolInput}

/**
 * A conversation-aware provider request — agent turns build these. Carries
 * the topic stack, conversation view, instructions, mode, and everything
 * the framework needs to render a full system prompt and message log.
 *
 * Not serializable — `tools` carry executable behavior. If/when replay or
 * caching is needed, extract a snapshot with just the serializable fields.
 *
 * @param turnInput      the curator's per-turn output — carries the
 *                       [[sigil.conversation.ConversationView]] (frames +
 *                       participant projections) plus the memory / summary /
 *                       information selections the provider should render.
 * @param currentTopic   the active topic at request-build time. Events the
 *                       orchestrator emits (Message, ToolInvoke, …) land
 *                       under this topic unless a [[sigil.event.TopicChange]]
 *                       fires during the turn.
 * @param previousTopics earlier entries on the conversation's topic stack —
 *                       subjects this conversation has been on before and
 *                       could return to. Rendered in the system prompt as
 *                       "Previous topics" so the LLM can recognize implicit
 *                       returns.
 * @param chain          authority lineage for this invocation — the originating
 *                       participant followed by each propagator. Forwarded to
 *                       `TurnContext.chain` when a tool executes. Supplied fresh
 *                       per invocation; never persisted.
 */
case class ConversationRequest(conversationId: Id[Conversation],
                               /** Sigil #277 — required Model record, not
                                 * `Id[Model]`. Resolved from
                                 * [[sigil.cache.ModelRegistry]] at the runtime
                                 * boundary (`Sigil.runAgentLoop`); unregistered
                                 * ids throw [[UnregisteredModelException]]
                                 * before this carrier is constructed. */
                               model: Model,
                               instructions: Instructions,
                               turnInput: TurnInput,
                               currentMode: Mode,
                               currentTopic: TopicEntry,
                               previousTopics: List[TopicEntry] = Nil,
                               generationSettings: GenerationSettings,
                               tools: Vector[Tool] = Vector.empty,
                               builtInTools: Set[BuiltInTool] = Set.empty,
                               chain: List[ParticipantId] = Nil,
                               roles: List[sigil.role.Role] = Nil,
                               isGreeting: Boolean = false,
                               /** Sigil bug #125 — when `true`, the framework
                                 * forces the provider's tool_choice to
                                 * `respond` so the model must synthesize a
                                 * reply on this turn. Set by the iteration-cap
                                 * soft-stop path. */
                               forceResponseSynthesis: Boolean = false,
                               /** Sigil bug #226 — snapshot of the per-agent-loop
                                 * `find_capability` cache surfaced into the
                                 * system prompt's "Capabilities you've already
                                 * discovered" section. Lifetime is bounded by
                                 * the agent loop on the caller side
                                 * ([[sigil.TurnContext.discoveredCapabilities]]);
                                 * the renderer just reads what the loop has
                                 * accumulated so far. Empty on a fresh user
                                 * turn. */
                               discoveredCapabilities: Map[String, DiscoveredCapability] = Map.empty,
                               /** Sigil bug #281 follow-up — the LIVE atomic
                                 * reference behind [[discoveredCapabilities]],
                                 * threaded through from the agent loop's
                                 * `TurnContext.discoveredCapabilitiesRef`.
                                 * Tools running under the orchestrator
                                 * (`FindCapabilityTool` in particular) write
                                 * to this ref via
                                 * [[sigil.TurnContext.recordDiscovery]] so the
                                 * NEXT iteration's `runAgentTurn` sees the
                                 * discovered tools both in the system prompt
                                 * section AND in the wire-roster.
                                 *
                                 * Without this shared ref, the orchestrator's
                                 * fresh `TurnContext` would carry its own
                                 * empty ref — writes from tool dispatch never
                                 * reached the agent loop's view, so
                                 * `find_capability` → next-turn invoke was
                                 * broken: matches were rendered in the prompt
                                 * once (from the snapshot) but the discovered
                                 * tool wasn't in the wire `tools` array, and
                                 * the model couldn't call what wasn't there. */
                               discoveredCapabilitiesRef: java.util.concurrent.atomic.AtomicReference[Map[String, DiscoveredCapability]] =
                                 new java.util.concurrent.atomic.AtomicReference(Map.empty[String, DiscoveredCapability]),
                               /** Sigil #411 — TURN-scoped cache of settled read-tool
                                 * results, keyed by the canonical `(toolName, args)`
                                 * key the #87 dedupe already computes. Created once per
                                 * turn in the agent loop and threaded through each
                                 * iteration's request (like [[discoveredCapabilitiesRef]]),
                                 * so a retry that re-issues an identical READ call is
                                 * served from cache instead of re-executing. #87's
                                 * dedupe is per-completion (`State.dispatchedKeys`), so
                                 * without this a no-tool-call retry / recoverable
                                 * provider-error re-iterate re-runs the whole tool batch
                                 * — a real latency/quota tax on side-effecting reads
                                 * (an on-prem RPC, a paid web search). ONLY read tools
                                 * (`Tool.readOnly`) are cached; writes always execute so
                                 * a retry can't double-submit. */
                               toolResultCacheRef: java.util.concurrent.atomic.AtomicReference[Map[String, Vector[sigil.tool.model.ResponseContent]]] =
                                 new java.util.concurrent.atomic.AtomicReference(Map.empty[String, Vector[sigil.tool.model.ResponseContent]]),
                               /** Sigil #304 — turn-start wall-clock threaded through
                                 * from [[sigil.TurnContext.turnStartedAt]] for the
                                 * orchestrator's duplicate-call cap to scope its
                                 * count to "invocations made during THIS turn"
                                 * rather than the projection's full rolling window.
                                 * `None` for one-shot / synthetic requests with no
                                 * owning agent loop; the cap then counts every
                                 * recent invocation, preserving prior behaviour. */
                               turnStartedAt: Option[Timestamp] = None,
                               requestId: Id[ProviderRequest] = Id())
  extends ProviderRequest {

  /** Sigil #274 — the wire-advertised tool roster. When
    * [[forceResponseSynthesis]] is set, the roster is restricted to the
    * respond family so the model MUST emit one of those terminal calls.
    * Both the wire path ([[sigil.provider.Provider.translate]]) and the
    * dispatch path ([[sigil.orchestrator.Orchestrator]]) must agree on
    * what's in scope — without that, the model could call a tool the
    * accumulator's parser doesn't know about (emitting `JsonInput`) while
    * the orchestrator's `toolsByName` finds the real tool and routes
    * the untyped input to its typed body, throwing `ClassCastException`.
    *
    * Apps overriding this method to apply additional filters (per-turn
    * gating, A/B experiments, …) MUST keep the symmetry — every consumer
    * downstream of the request must look at the same set. */
  def effectiveTools: Vector[Tool] =
    if (forceResponseSynthesis) {
      val respondFamily = sigil.tool.core.CoreTools.atomicContentToolNames
      tools.filter(t => respondFamily.contains(t.schema.name))
    } else tools
}
