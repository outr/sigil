package sigil.participant

import lightdb.id.Id
import rapid.{Stream, Task}
import sigil.TurnContext
import sigil.db.Model
import sigil.event.{Event, Message}
import sigil.orchestrator.Orchestrator
import sigil.provider.{ConversationRequest, GenerationSettings, Instructions, Provider}
import sigil.signal.{AgentActivity, AgentStateDelta, Signal}
import sigil.tool.{Tool, ToolInput, ToolName}

/**
 * A participant that acts autonomously — typically LLM-backed. Pure data:
 * every field is serializable so the full `AgentParticipant` can round-trip
 * through fabric RW and be persisted on [[sigil.conversation.Conversation]].
 *
 * Runtime dependencies — the live [[sigil.provider.Provider]] and the tool
 * instances to hand to it — are resolved lazily inside `defaultProcess` via
 * [[sigil.Sigil.providerFor]] and [[sigil.tool.ToolFinder.byName]]. This
 * keeps the agent itself purely descriptive ("what this agent is") and
 * leaves the "how to run it right now" to Sigil.
 *
 * The framework dispatcher (`Sigil.publish`) owns the surrounding
 * [[sigil.event.AgentState]] lifecycle: it claims an `AgentState(Active)`
 * with `activity = Thinking` before invoking `process`, and emits the
 * terminal `AgentStateDelta(Idle, Complete)` after the agent's self-loop
 * settles. `defaultProcess` only emits the mid-turn `Typing` transition,
 * targeted at `context.currentAgentStateId`.
 *
 * Apps override `process` (or the protected `defaultProcess`) for custom
 * agent behaviors (Planner, Critic, Worker patterns). Vanilla agents use
 * [[DefaultAgentParticipant]] without subclassing.
 */
trait AgentParticipant extends Participant {
  override def id: AgentParticipantId

  /** The model this agent uses for provider round-trips. */
  def modelId: Id[Model]

  /**
   * Tools available to this agent, referenced by `schema.name`. The
   * dispatcher rehydrates each name to a live `Tool` instance at call time
   * via [[sigil.tool.ToolFinder.byName]]. Names not found are dropped; the
   * agent runs with whatever the finder resolves.
   *
   * Use [[sigil.tool.core.CoreTools.coreToolNames]] for the framework
   * baseline, e.g.
   * `toolNames = CoreTools.coreToolNames ++ List("my_app_tool")`.
   */
  def toolNames: List[ToolName] = Nil

  /** System / developer instructions prepended to every turn. */
  def instructions: Instructions = Instructions()

  /** Sampling + limits for provider requests. */
  def generationSettings: GenerationSettings = GenerationSettings()

  /**
   * Entry point invoked by the dispatcher. Default delegates to
   * [[defaultProcess]]; override for fundamentally different agent shapes.
   */
  override def process(context: TurnContext, triggers: Stream[Event]): Stream[Signal] =
    defaultProcess(context, triggers)

  /**
   * Standard one-round-trip behavior:
   *
   *   1. Resolve the live [[sigil.provider.Provider]] via
   *      `sigil.providerFor(modelId, chain)` (chain carries the originating
   *      participant so app credential resolvers can pick the right keys).
   *   2. Resolve each name in `toolNames` to a live `Tool` via
   *      `sigil.findTools.byName(name, chain)`. Names that don't resolve
   *      are dropped.
   *   3. Build a [[ProviderRequest]] and run it; translate the provider's
   *      stream into `Signal`s via [[Orchestrator.process]].
   *   4. The first time the orchestrator emits a [[Message]] (streaming
   *      content has started), prepend an [[AgentStateDelta]] transitioning
   *      `activity = Typing`. Targets `context.currentAgentStateId`.
   *
   * This intentionally does NOT emit the surrounding `AgentState(Thinking,
   * Active)` event nor the terminal `AgentStateDelta(Idle, Complete)` —
   * both are owned by the framework dispatcher.
   *
   * `triggers` is unused here by default; the agent acts purely on the
   * curated context. Custom overrides can inspect triggers to tailor
   * behavior (e.g. mention-aware responses).
   */
  protected def defaultProcess(context: TurnContext, triggers: Stream[Event]): Stream[Signal] = {
    val sigil = context.sigil
    // Ensure the agent's id is `chain.last` — the chain's invariant is
    // "actor at the end". In the dispatcher path the chain already ends
    // with `agent.id`; in direct callers (tests, custom drivers) it might
    // not. Normalize either way.
    val effectiveChain = context.chain.filterNot(_ == id) :+ id

    // The agent's effective tool roster for THIS turn is computed by
    // `Sigil.effectiveToolNames` — it composes the current `Mode`'s
    // `ModeTools` policy with the participant's baseline roster and the
    // one-turn `suggestedTools` from `find_capability`.
    val suggested = context.conversationView.projectionFor(id).suggestedTools
    val effectiveNames = sigil.effectiveToolNames(this, context.conversation.currentMode, suggested).distinct

    val resolved: Task[(Provider, Vector[Tool[? <: ToolInput]])] =
      for {
        p <- sigil.providerFor(modelId, effectiveChain)
        t <- Task.sequence(effectiveNames.map(n => sigil.findTools.byName(n, effectiveChain)))
               .map(_.flatten.toVector)
      } yield (p, t)

    Stream.force(resolved.map { case (provider, tools) =>
      val request = ConversationRequest(
        conversationId = context.conversation.id,
        modelId = modelId,
        instructions = instructions,
        turnInput = context.turnInput,
        currentMode = context.conversation.currentMode,
        currentTopic = context.conversation.currentTopic,
        previousTopics = context.conversation.previousTopics,
        generationSettings = generationSettings,
        tools = tools,
        chain = effectiveChain
      )

      val typingEmitted = new java.util.concurrent.atomic.AtomicBoolean(false)
      Orchestrator.process(sigil, provider, request).flatMap { sig =>
        val prefix: List[Signal] = sig match {
          case _: Message if typingEmitted.compareAndSet(false, true) =>
            context.currentAgentStateId.toList.map { agentStateId =>
              AgentStateDelta(
                target = agentStateId,
                conversationId = context.conversation.id,
                activity = Some(AgentActivity.Typing)
              )
            }
          case _ => Nil
        }
        Stream.emits(prefix :+ sig)
      }
    })
  }
}
