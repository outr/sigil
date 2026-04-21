package sigil.participant

import lightdb.id.Id
import rapid.{Stream, Task}
import sigil.TurnContext
import sigil.db.Model
import sigil.event.{Event, Message}
import sigil.orchestrator.Orchestrator
import sigil.provider.{GenerationSettings, Instructions, Provider, ProviderRequest}
import sigil.signal.{AgentActivity, AgentStateDelta, Signal}
import sigil.tool.{Tool, ToolInput}

/**
 * A participant that acts autonomously — typically LLM-backed. Carries the
 * configuration needed to drive a provider round-trip and, by default,
 * implements `process` by running one LLM round-trip and translating its
 * provider events into [[Signal]]s.
 *
 * The framework dispatcher (`Sigil.publish`) owns the surrounding
 * [[sigil.event.AgentState]] lifecycle: it claims an `AgentState(Active)`
 * with `activity = Thinking` before invoking `process`, and emits the
 * terminal `AgentStateDelta(Idle, Complete)` after the agent's self-loop
 * settles. `defaultProcess` only emits the mid-turn `Typing` transition,
 * targeted at `context.currentAgentStateId`.
 *
 * Apps override `process` (or the protected `defaultProcess`) for custom
 * agent behaviors (Planner, Critic, Worker patterns). Vanilla agents just
 * supply configuration and let the default take over.
 */
trait AgentParticipant extends Participant {
  override def id: AgentParticipantId

  /** The model this agent uses for provider round-trips. */
  def modelId: Id[Model]

  /** Factory for the Provider that serves `modelId`. */
  def provider: Task[Provider]

  /** System / developer instructions prepended to every turn. */
  def instructions: Instructions = Instructions()

  /** Sampling + limits for provider requests. */
  def generationSettings: GenerationSettings = GenerationSettings()

  /** Tools available to the agent on every turn. */
  def tools: Vector[Tool[? <: ToolInput]] = Vector.empty

  /**
   * Entry point invoked by the dispatcher. Default delegates to
   * [[defaultProcess]]; override for fundamentally different agent shapes.
   */
  override def process(context: TurnContext, triggers: Stream[Event]): Stream[Signal] =
    defaultProcess(context, triggers)

  /**
   * Standard one-round-trip behavior:
   *
   *   1. Build a [[ProviderRequest]] from the agent's config + the curated
   *      [[sigil.conversation.ConversationContext]] in `context`.
   *   2. Invoke the provider; translate the resulting `ProviderEvent`s into
   *      `Signal`s via [[Orchestrator.process]].
   *   3. The first time the orchestrator emits a [[Message]] (streaming
   *      content has started), prepend an [[AgentStateDelta]] transitioning
   *      `activity = Typing`. Targets `context.currentAgentStateId`.
   *
   * This intentionally does NOT emit the surrounding `AgentState(Thinking,
   * Active)` event nor the terminal `AgentStateDelta(Idle, Complete)` — both
   * are owned by the framework dispatcher.
   *
   * `triggers` is unused here by default; the agent acts purely on the
   * curated context. Custom overrides can inspect triggers to tailor
   * behavior (e.g. mention-aware responses).
   */
  protected def defaultProcess(context: TurnContext, triggers: Stream[Event]): Stream[Signal] = {
    // Ensure the agent's id is `chain.last` — the chain's invariant is
    // "actor at the end". In the dispatcher path the chain already ends
    // with `agent.id`; in direct callers (tests, custom drivers) it might
    // not. Normalize either way.
    val effectiveChain = context.chain.filterNot(_ == id) :+ id
    val request = ProviderRequest(
      conversationId = context.conversation.id,
      modelId = modelId,
      instructions = instructions,
      context = context.conversationContext,
      currentMode = context.conversation.currentMode,
      generationSettings = generationSettings,
      tools = tools,
      chain = effectiveChain
    )

    val typingEmitted = new java.util.concurrent.atomic.AtomicBoolean(false)
    Stream.force(provider.map(p => Orchestrator.process(context.sigil, p, request))).flatMap { sig =>
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
  }
}
