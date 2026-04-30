package sigil.participant

import lightdb.id.Id
import rapid.{Stream, Task}
import sigil.TurnContext
import sigil.role.{GeneralistRole, Role}
import sigil.db.Model
import sigil.event.Event
import sigil.provider.{BuiltInTool, ConversationWork, GenerationSettings, Instructions, ToolPolicy, WorkType}
import sigil.signal.Signal
import sigil.tool.ToolName

/**
 * A participant that acts autonomously — typically LLM-backed. Pure data:
 * every field is serializable so the full `AgentParticipant` can round-trip
 * through fabric RW and be persisted on [[sigil.conversation.Conversation]].
 *
 * Runtime dependencies — the live [[sigil.provider.Provider]] and the tool
 * instances to hand to it — are resolved lazily inside the framework's
 * default turn ([[sigil.Sigil.defaultProcess]]). The agent itself stays
 * purely descriptive; the "how to run it right now" lives on Sigil.
 *
 * The framework dispatcher (`Sigil.publish`) owns the surrounding
 * [[sigil.event.AgentState]] lifecycle: it claims an `AgentState(Active)`
 * with `activity = Thinking` before invoking `process`, and emits the
 * terminal `AgentStateDelta(Idle, Complete)` after the agent's self-loop
 * settles.
 *
 * **Roles compose into a single dispatch.** An agent's [[roles]] list is
 * fully merged into one prompt + one LLM call per turn. Multiple roles
 * shape *how* the agent responds (the system prompt enumerates them
 * with a "You serve the following roles:" preamble + per-role
 * description); they do NOT cause multiple responses. Apps express
 * "Planner+Critic"-style agents as `roles = List(PlannerRole,
 * CriticRole)` and read back one merged response per turn.
 *
 * **Tools, greeting, and dispatch overrides are agent-level**, not
 * role-level: one tool roster, one greet decision, one dispatch path
 * per agent. Non-LLM "react to triggers without calling the model"
 * shapes belong on a separate participant trait, not as a Role
 * variant.
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
   * Tool-availability policy folded with the current Mode's policy by
   * [[sigil.Sigil.effectiveToolNames]] to compose the agent's effective
   * roster for the turn. Agent-level (not per-role) — a multi-role
   * agent has one effective tool set per turn.
   */
  def tools: ToolPolicy = ToolPolicy.Standard

  /**
   * Provider-managed tools the agent has unconditional access to —
   * unioned with the current Mode's [[sigil.provider.Mode.builtInTools]]
   * by [[sigil.Sigil.defaultProcess]] before the request hits the
   * provider. Agents that should always have native web search
   * (regardless of mode) declare `builtInTools = Set(BuiltInTool.WebSearch)`
   * here; mode-scoped opt-ins live on the Mode. Models without
   * server-side support silently drop the opt-in. Default empty.
   */
  def builtInTools: Set[BuiltInTool] = Set.empty

  /**
   * Category of work the agent's calls represent — fed to the
   * conversation's [[sigil.provider.ProviderStrategy]] (when one is
   * resolved) so per-work-type model chains pick the right model.
   *
   * Default [[ConversationWork]] suits chat-shaped agents. Apps with
   * specialized agent surfaces — a coding assistant, a classifier,
   * a summarization agent — set the agent's work type to match.
   * Strategy resolution is `Mode.strategyId.orElse(space-assigned)`,
   * so a mode override beats a space-level default. */
  def workType: WorkType = ConversationWork

  /**
   * When `true`, the framework fires the agent's [[processGreeting]]
   * once the moment it joins a conversation (whether the conversation
   * was just created via [[sigil.Sigil.newConversation]] or the agent
   * was added later via [[sigil.Sigil.addParticipant]]). The agent's
   * roles' descriptions and skills drive the greeting wording — the
   * framework injects no synthetic prompt; a role whose description
   * says "On entering an empty conversation, introduce yourself"
   * produces the greeting. Default `false`.
   */
  def greetsOnJoin: Boolean = false

  /**
   * Permanent role assignments for this agent. Combined into a single
   * merged prompt per turn — multiple roles shape how the agent
   * responds, not how many times. See the trait-level docs for the
   * single-dispatch semantics.
   *
   * Defaults to `List(GeneralistRole)` so vanilla agents have a real
   * role. Must be non-empty (enforced via `require` at trait
   * initialization); apps that override must supply at least one
   * role.
   */
  def roles: List[Role] = List(GeneralistRole)

  require(roles.nonEmpty, s"AgentParticipant.roles must be non-empty (id=${id.value})")

  /**
   * Resolve this agent's roles for a particular turn. Default returns
   * the static `roles` field synchronously; apps storing roles in a DB
   * (Voidcraft personas, Sage `PersonaCollection`) override to consult
   * persistence each turn.
   *
   * The framework calls this in [[sigil.Sigil.runAgentTurn]] before
   * building the [[sigil.provider.ConversationRequest]]; the resolved
   * list (or the static `roles` field as fallback) becomes
   * `request.roles`.
   *
   * Returning an empty list is treated as a programmer error and
   * raises at the call site — express "no special role" by returning
   * `List(GeneralistRole)`.
   */
  def resolveRoles(context: TurnContext): Task[List[Role]] = Task.pure(roles)

  /**
   * Final dispatch entry point. One [[sigil.Sigil.process]] call per
   * turn, regardless of role count — the prompt rendering folds every
   * role into one merged context. Apps customize per-agent turn
   * shapes by overriding `Sigil.process`, not by overriding `process`
   * here.
   */
  final override def process(context: TurnContext, triggers: Stream[Event]): Stream[Signal] =
    context.sigil.process(this, context, triggers)

  /**
   * Greet entry point — called by the framework when this agent joins
   * a conversation (either via [[sigil.Sigil.newConversation]] or
   * [[sigil.Sigil.addParticipant]]). Same merged dispatch as a normal
   * turn but with an empty trigger stream; the role(s)'s skill /
   * description text owns the greeting wording.
   *
   * Caller (`Sigil.fireGreeting`) only invokes this when
   * `greetsOnJoin == true`, so this method itself doesn't gate.
   */
  final def processGreeting(context: TurnContext): Stream[Signal] =
    context.sigil.process(this, context, Stream.empty)
}
