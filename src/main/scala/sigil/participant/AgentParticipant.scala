package sigil.participant

import lightdb.id.Id
import rapid.{Stream, Task}
import sigil.TurnContext
import sigil.db.Model
import sigil.event.{AgentState, Event, Message}
import sigil.orchestrator.Orchestrator
import sigil.provider.{GenerationSettings, Instructions, Mode, Provider, ProviderRequest}
import sigil.signal.{AgentActivity, AgentStateDelta, EventState, Signal}
import sigil.tool.{Tool, ToolInput}

/**
 * A participant that acts autonomously — typically LLM-backed. Carries the
 * configuration needed to drive a provider round-trip and, by default,
 * implements `process` by running the standard LLM loop wrapped in an
 * [[AgentState]] lifecycle (Thinking → … → Idle).
 *
 * Single-threaded per agent: the app-level dispatcher guarantees one
 * `process` invocation is in flight at a time. Events that arrive during
 * processing accumulate in the agent's mailbox for the next invocation.
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
   * Current operating mode. Default `Conversation`. Apps override to derive
   * from the latest `ModeChange` event in the conversation, or from app
   * state.
   */
  def currentMode: Mode = Mode.Conversation

  /**
   * Entry point invoked by the dispatcher. Default delegates to
   * [[defaultProcess]]; override for fundamentally different agent shapes.
   */
  override def process(context: TurnContext, triggers: List[Event]): Stream[Signal] =
    defaultProcess(context, triggers)

  /**
   * Standard LLM-loop behavior:
   *
   *   1. Decide whether to respond. Default rule: any trigger is a Message
   *      from another participant. Override [[shouldRespond]] for fancier
   *      logic (mentions, access rules, persona matching).
   *   2. Emit [[AgentState]] with `activity = Thinking, state = Active`.
   *   3. Run the provider round-trip via [[Orchestrator.process]] — produces
   *      ToolInvokes, Messages, Deltas for everything the LLM emits.
   *      Transitions activity to `Typing` the first time the orchestrator
   *      emits a new [[sigil.event.Message]] (streaming content has
   *      started).
   *   4. Emit [[AgentStateDelta]] with `activity = Idle, state = Complete`
   *      at the end of the stream.
   */
  protected def defaultProcess(context: TurnContext, triggers: List[Event]): Stream[Signal] = {
    if (!shouldRespond(context, triggers)) Stream.empty
    else runTurn(context)
  }

  /**
   * Default "should this agent respond?" rule: any trigger is a Message
   * from a participant that isn't me. Override for mention-based routing,
   * persona-specific filters, etc.
   */
  protected def shouldRespond(context: TurnContext, triggers: List[Event]): Boolean =
    triggers.exists {
      case m: Message => m.participantId != id
      case _          => false
    }

  private def runTurn(context: TurnContext): Stream[Signal] = {
    val agentStateId = Event.id()
    val conversationId = context.conversation.id
    val effectiveChain = context.chain :+ id

    val agentState = AgentState(
      agentId = id,
      participantId = id,
      conversationId = conversationId,
      _id = agentStateId,
      activity = AgentActivity.Thinking,
      state = EventState.Active
    )

    val request = ProviderRequest(
      conversationId = conversationId,
      modelId = modelId,
      instructions = instructions,
      context = context.conversationContext,
      currentMode = currentMode,
      generationSettings = generationSettings,
      tools = tools,
      chain = effectiveChain
    )

    val typingEmitted = new java.util.concurrent.atomic.AtomicBoolean(false)
    val providerStream: Stream[Signal] =
      Stream.force(provider.map(p => Orchestrator.process(context.sigil, p, request))).flatMap { sig =>
        val prefix: List[Signal] = sig match {
          case _: Message if typingEmitted.compareAndSet(false, true) =>
            List(AgentStateDelta(
              target = agentStateId,
              conversationId = conversationId,
              activity = Some(AgentActivity.Typing)
            ))
          case _ => Nil
        }
        Stream.emits(prefix :+ sig)
      }

    val idleDelta = AgentStateDelta(
      target = agentStateId,
      conversationId = conversationId,
      activity = Some(AgentActivity.Idle),
      state = Some(EventState.Complete)
    )

    Stream.emits(List[Signal](agentState)) ++ providerStream ++ Stream.emits(List[Signal](idleDelta))
  }
}
