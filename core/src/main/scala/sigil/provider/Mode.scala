package sigil.provider

import fabric.rw.*
import lightdb.id.Id
import sigil.conversation.ActiveSkillSlot

/**
 * A behavioral mode for a conversation. Switching modes changes the
 * agent's instructions (`skill`), the tools it can see (`tools`), and
 * how `find_capability` scopes its discovery catalog.
 *
 * `Mode` is an open [[PolyType]] — Sigil ships only [[ConversationMode]]
 * by default. Apps define their own case objects (e.g. `WorkflowMode`,
 * `WebNavigationMode`, `CodingMode`) and register them via
 * `Sigil.modeRegistrations` so the polymorphic serializer resolves them
 * on read.
 *
 * The `name` field is the stable discriminator persisted in
 * `ModeChange` events and `Conversation.currentMode`. Keep it constant
 * across renames — changing `name` breaks historical records.
 */
trait Mode {
  /** Stable discriminator — what gets persisted. */
  def name: String

  /** One-line human-readable description; rendered into the system
    * prompt and into the `change_mode` tool's schema. */
  def description: String

  /** Curated keyword set boosting this mode's score in
    * [[sigil.Sigil.findModes]]. Pure metadata for discovery — never
    * shown to the user, never persisted on conversation events. Useful
    * when the mode's `name`/`description` doesn't include the natural
    * search terms (e.g. `WebResearchMode` should match "browse",
    * "google", "lookup"; `CodingMode` should match "function",
    * "refactor", "debug"). */
  def keywords: Set[String] = Set.empty

  /** Skill slot activated when this mode becomes current. `None`
    * means no mode-driven instructions; the agent uses whatever skills
    * come from other sources (discovery, user overrides). */
  def skill: Option[ActiveSkillSlot] = None

  /** Tool availability policy for this mode — see [[ToolPolicy]]. */
  def tools: ToolPolicy = ToolPolicy.Standard

  /** Provider-managed tools active in this mode — see [[BuiltInTool]].
    * Apps that want a "web research" mode flip on `BuiltInTool.WebSearch`;
    * a "creative" mode might enable `ImageGeneration`. The orchestrator
    * unions this set with `AgentParticipant.builtInTools` and passes the
    * result through `ConversationRequest.builtInTools`, so models with
    * native server-side support (Anthropic web search, OpenAI Responses
    * web search, Google Gemini grounding) exercise it directly. Models
    * without support silently drop the opt-in. Default empty. */
  def builtInTools: Set[BuiltInTool] = Set.empty

  /** Optional [[ProviderStrategyRecord]] pinned to this mode —
    * when the conversation enters this mode, agent dispatch loads
    * + materializes that strategy regardless of the conversation's
    * space-level assignment. `None` means "use whatever strategy
    * the conversation's space resolves to" (typical case).
    *
    * Apps configure mode-pinned strategies for situations where
    * the work shape itself dictates the model — e.g. a `CodingMode`
    * that always wants Claude, regardless of who's logged in. */
  def strategyId: Option[Id[ProviderStrategyRecord]] = None

  /** Stable `Id[Mode]` derived from [[name]]. Used by `Tool.modes`
    * to declare mode affinity in a persistable, query-friendly shape. */
  final lazy val id: Id[Mode] = Id(name)
}

object Mode extends PolyType[Mode]()(using scala.reflect.ClassTag(classOf[Mode]))
