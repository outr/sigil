package sigil.participant

import lightdb.id.Id
import rapid.Stream
import sigil.TurnContext
import sigil.behavior.{Behavior, GeneralistBehavior}
import sigil.conversation.{ActiveSkillSlot, SkillSource}
import sigil.db.Model
import sigil.event.Event
import sigil.provider.{GenerationSettings, Instructions}
import sigil.signal.Signal
import sigil.tool.ToolName

/**
 * A participant that acts autonomously — typically LLM-backed. Pure data:
 * every field is serializable so the full `AgentParticipant` can round-trip
 * through fabric RW and be persisted on [[sigil.conversation.Conversation]].
 *
 * Runtime dependencies — the live [[sigil.provider.Provider]] and the tool
 * instances to hand to it — are resolved lazily inside the framework's
 * default per-behavior turn ([[sigil.Sigil.defaultProcess]]). The agent
 * itself stays purely descriptive; the "how to run it right now" lives
 * on Sigil.
 *
 * The framework dispatcher (`Sigil.publish`) owns the surrounding
 * [[sigil.event.AgentState]] lifecycle: it claims an `AgentState(Active)`
 * with `activity = Thinking` before invoking `process`, and emits the
 * terminal `AgentStateDelta(Idle, Complete)` after the agent's self-loop
 * settles.
 *
 * Behaviors are the role-shaping primitive. An agent's
 * [[behaviors]] list is iterated each turn; for each behavior the
 * framework injects an [[ActiveSkillSlot]] into the agent's projection
 * (per-turn view copy) and delegates to
 * [[sigil.Sigil.process]] which apps override to specialize per-behavior
 * dispatch (e.g. a Worker behavior that reacts to triggers without
 * calling the LLM). The default behavior list is
 * `List(GeneralistBehavior)` — agents always have a real role; an
 * empty list is rejected.
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
   * Permanent role assignments for this agent. Iterated per turn —
   * each behavior's slot is injected into the agent's projection and
   * dispatched independently via [[sigil.Sigil.process]].
   *
   * Defaults to `List(GeneralistBehavior)` so vanilla agents have a
   * real role. Must be non-empty (enforced via `require` at trait
   * initialization); apps that override must supply at least one
   * behavior.
   */
  def behaviors: List[Behavior] = List(GeneralistBehavior)

  require(behaviors.nonEmpty, s"AgentParticipant.behaviors must be non-empty (id=${id.value})")

  /**
   * Final dispatch entry point. Iterates [[behaviors]] in declaration
   * order; for each behavior, injects its [[ActiveSkillSlot]] into
   * the agent's per-turn projection and delegates to
   * [[sigil.Sigil.process]]. Apps customize per-behavior turn shapes
   * by overriding `Sigil.process`, not by overriding `process` here.
   */
  final override def process(context: TurnContext, triggers: Stream[Event]): Stream[Signal] =
    Stream.emits(behaviors).flatMap { b =>
      context.sigil.process(this, b, enrichedContextFor(context, b), triggers)
    }

  private def enrichedContextFor(context: TurnContext, behavior: Behavior): TurnContext = {
    val slot = behavior.skill.orElse {
      if (behavior.description.nonEmpty)
        Some(ActiveSkillSlot(name = behavior.name, content = behavior.description))
      else None
    }
    slot match {
      case None => context
      case Some(s) =>
        val proj = context.conversationView.projectionFor(id)
        val updatedProj = proj.copy(
          activeSkills = proj.activeSkills + (SkillSource.Behavior(behavior.name) -> s)
        )
        val updatedView = context.conversationView.copy(
          participantProjections = context.conversationView.participantProjections + (id -> updatedProj)
        )
        context.copy(
          conversationView = updatedView,
          turnInput = context.turnInput.copy(conversationView = updatedView)
        )
    }
  }
}
