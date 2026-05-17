package sigil.provider

import fabric.*
import fabric.define.{DefType, Definition}
import fabric.rw.*
import lightdb.id.Id
import scala.collection.immutable.VectorMap
import sigil.conversation.ActiveSkillSlot

/**
 * A behavioral mode for a conversation. Switching modes changes the
 * agent's instructions (`skill`), the tools it can see (`tools`), and
 * how `find_capability` scopes its discovery catalog.
 *
 * `Mode` is an open [[PolyType]] — Sigil ships only [[ConversationMode]]
 * by default. Apps define their own case objects (e.g. `WorkflowMode`,
 * `WebNavigationMode`, a coding mode) and register them via
 * `Sigil.modes` so the polymorphic serializer resolves them on read.
 *
 * The `name` field is the stable discriminator persisted in
 * `ModeChange` events AND the wire-level type tag for the polymorphic
 * RW. Keep it constant across renames — changing `name` breaks
 * historical records and any clients that pinned to the old wire value.
 */
trait Mode {
  /** Stable discriminator — persisted in `ModeChange` events AND used
    * as the wire-level `name` discriminator in the polymorphic
    * [[Mode]] RW (replaces the Scala class name). Must be unique across
    * every registered mode in a Sigil instance; duplicates resolve
    * last-write-wins per the underlying [[fabric.rw.PolyType.register]]
    * append-only registry. */
  def name: String

  /** One-line human-readable description; rendered into the system
    * prompt and into the `change_mode` tool's schema. */
  def description: String

  /** Curated keyword set boosting this mode's score in
    * [[sigil.Sigil.findModes]]. Pure metadata for discovery — never
    * shown to the user, never persisted on conversation events. Useful
    * when the mode's `name`/`description` doesn't include the natural
    * search terms (e.g. `WebResearchMode` should match "browse",
    * "google", "lookup"; a coding mode should match "function",
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
    * the work shape itself dictates the model — e.g. a coding mode
    * that always wants Claude, regardless of who's logged in. */
  def strategyId: Option[Id[ProviderStrategyRecord]] = None

  /** Override the agent's [[WorkType]] while this mode is current.
    * `None` (default) keeps the agent's declared workType. Modes
    * that intrinsically dictate the work shape declare it here so
    * provider routing follows naturally — e.g. `ScriptAuthoringMode`
    * pins `Some(CodingWork)` so the cheap-fast conversational chain
    * doesn't run when the agent is authoring runtime tools. */
  def workType: Option[WorkType] = None

  /** Stable `Id[Mode]` derived from [[name]]. Used by `Tool.modes`
    * to declare mode affinity in a persistable, query-friendly shape. */
  final lazy val id: Id[Mode] = Id(name)
}

/**
 * Companion for [[Mode]]. Custom polymorphic RW: the wire body uses
 * [[Mode.name]] as the discriminator (under the JSON key `"name"`),
 * not the Scala class name. This keeps the wire payload aligned with
 * the value persisted in `ModeChange` events and with the value
 * `Sigil.modeByName` resolves, so UIs reading the wire render the
 * right mode chip and a model parroting the wire value back stays
 * consistent with what was written.
 *
 * Subtypes are still registered through [[register]] (`Sigil` does this
 * in `polymorphicRegistrations` from its `modes` list). Each registered
 * RW is expected to be a [[fabric.rw.RW.static]] of a singleton `Mode`
 * value — we extract that singleton at registration time by invoking
 * `rw.write(fabric.obj())` (the static RW returns its captured value
 * regardless of input) and index by `name`. Duplicates resolve
 * last-write-wins, matching the existing append-only `PolyType`
 * registry shape.
 */
object Mode extends PolyType[Mode]()(using scala.reflect.ClassTag(classOf[Mode])) {

  /** Wire field name carrying the polymorphic discriminator. */
  private val DiscriminatorField: String = "name"

  /** Name -> instance registry, in registration order. Each
    * [[register]] call extracts the singleton from each `RW.static`
    * and appends to this map (last-write-wins on duplicate names). */
  @volatile private var instancesByName: Map[String, Mode] = Map.empty

  /** Register additional [[Mode]] subtypes. Each `RW` must be built
    * via [[fabric.rw.RW.static]] over a singleton `Mode` value —
    * non-static RWs (e.g. case-class derivations) raise at registration
    * time because we can't extract a canonical name-bearing instance
    * from them. Concrete `Mode` shapes ship in framework + apps as
    * `case object`s, so this constraint is non-binding in practice. */
  override def register(types: RW[? <: Mode]*): Unit = synchronized {
    val updates: Seq[(String, Mode)] = types.map { rw =>
      val instance =
        try rw.write(obj())
        catch {
          case t: Throwable =>
            throw new RuntimeException(
              s"Mode.register: failed to extract Mode instance from $rw — Mode subtype RWs must be built via RW.static(<singleton>)",
              t
            )
        }
      instance.name -> instance
    }
    super.register(types*)
    updates.foreach { case (name, m) => instancesByName = instancesByName.updated(name, m) }
  }

  /** Live snapshot of every registered mode keyed by [[Mode.name]].
    * Returned defensive copy. */
  def registered: Map[String, Mode] = instancesByName

  /** Custom polymorphic RW. Serialization writes a single
    * `{"name": "<mode.name>"}` payload (mode singletons carry no other
    * fields). Deserialization looks the discriminator up in the
    * name-indexed registry. The schema [[Definition]] mirrors the
    * registry as `DefType.Poly` keyed by name so codegen consumers
    * (the Dart generator) emit dispatchers keyed by the wire
    * discriminator. */
  implicit override val rw: RW[Mode] = new RW[Mode] {
    override def read(t: Mode): Json = obj(DiscriminatorField -> str(t.name))

    override def write(json: Json): Mode = {
      val nameValue = json(DiscriminatorField).asString
      instancesByName.getOrElse(
        nameValue,
        throw new RuntimeException(
          s"Mode discriminator '$nameValue' not registered — available: [${instancesByName.keys.toList.sorted.mkString(", ")}]"
        )
      )
    }

    override def definition: Definition = Definition(
      DefType.Poly(
        VectorMap.from(instancesByName.toList.map { case (n, _) =>
          n -> Definition(DefType.Obj(VectorMap.empty), className = Some(n))
        })
      ),
      className = Some(classOf[Mode].getName)
    )
  }
}
