package sigil.provider

import lightdb.id.Id
import lightdb.time.Timestamp
import sigil.conversation.{Conversation, DiscoveredCapability, TopicEntry, TurnInput}
import sigil.db.Model
import sigil.participant.ParticipantId
import sigil.tool.{Tool, ToolRoster}

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
                               /** Model record resolved from
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
                               /** When `true`, the framework
                                 * forces the provider's tool_choice to
                                 * `respond` so the model must synthesize a
                                 * reply on this turn. Set by the iteration-cap
                                 * soft-stop path. */
                               forceResponseSynthesis: Boolean = false,
                               discoveredCapabilities: Map[String, DiscoveredCapability] = Map.empty,
                               /** The LIVE atomic
                                 * reference behind [[discoveredCapabilities]],
                                 * threaded through from the agent loop's
                                 * `TurnContext.discoveredCapabilitiesRef`.
                                 * Tools running under the orchestrator
                                 * (`FindCapabilityTool` in particular) write
                                 * to this ref via
                                 * [[sigil.TurnContext.recordDiscovery]] so the
                                 * NEXT iteration's `runAgentTurn` sees the
                                 * discovered tools both in the system prompt
                                 * section AND in the wire-roster. */
                               discoveredCapabilitiesRef: java.util.concurrent.atomic.AtomicReference[Map[String, DiscoveredCapability]] =
                                 new java.util.concurrent.atomic.AtomicReference(Map.empty[String, DiscoveredCapability]),
                               /** TURN-scoped cache of settled read-tool results,
                                 * keyed by the canonical `(toolName, args)` key the
                                 * within-completion dedupe already computes. Created
                                 * once per turn in the agent loop and threaded through
                                 * each iteration's request (like
                                 * [[discoveredCapabilitiesRef]]), so a retry that
                                 * re-issues an identical READ call is served from cache
                                 * instead of re-executing — a real latency/quota tax on
                                 * side-effecting reads otherwise. Caching derives from
                                 * the tool's declared [[sigil.tool.Freshness]]: Pure
                                 * entries persist for the turn, Stable entries are
                                 * invalidated when a mutating call with an overlapping
                                 * (or undeclared) [[sigil.tool.MutationTarget]] lands,
                                 * Volatile reads are never cached. Writes always
                                 * execute so a retry can't double-submit. */
                               toolResultCacheRef: java.util.concurrent.atomic.AtomicReference[Map[String, sigil.tool.CachedToolRead]] =
                                 new java.util.concurrent.atomic.AtomicReference(Map.empty[String, sigil.tool.CachedToolRead]),
                               /** Turn-start wall-clock threaded through
                                 * from [[sigil.TurnContext.turnStartedAt]] for the
                                 * orchestrator's duplicate-call cap to scope its
                                 * count to "invocations made during THIS turn"
                                 * rather than the projection's full rolling window.
                                 * `None` for one-shot / synthetic requests with no
                                 * owning agent loop; the cap then counts every
                                 * recent invocation, preserving prior behaviour. */
                               turnStartedAt: Option[Timestamp] = None,
                               /** The owning agent claim's tool-cancellation token,
                                 * threaded from [[sigil.TurnContext.cancellation]] so
                                 * the orchestrator's synthesized dispatch context
                                 * carries it into `ToolContext.checkpoint`. `None`
                                 * for one-shot / synthetic requests with no owning
                                 * agent loop — checkpoints are then no-ops. */
                               cancellation: Option[sigil.CancellationToken] = None,
                               requestId: Id[ProviderRequest] = Id())
  extends ProviderRequest {

  /** The wire-advertised tool roster. When
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

  /** The request's single name→tool resolution source, built once from
    * [[effectiveTools]]. The wire path passes this same instance into the
    * [[ProviderCall]] (so the streaming accumulator resolves against it)
    * and the orchestrator dispatches against it — both ends of the
    * dispatch see one roster by construction. */
  lazy val roster: ToolRoster = ToolRoster(effectiveTools)
}