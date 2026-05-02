package sigil.tool

import fabric.rw.*
import lightdb.doc.{JsonConversion, RecordDocument, RecordDocumentModel}
import lightdb.id.Id
import lightdb.time.Timestamp
import sigil.{GlobalSpace, Sigil, SpaceId, TurnContext}
import sigil.event.Event
import sigil.participant.ParticipantId
import sigil.provider.{ConversationMode, Mode}

/**
 * A capability available to agents at runtime. Persisted in
 * [[sigil.db.SigilDB.tools]] so static (app-defined) tools and dynamic
 * (user-created) tools share one collection, one query path, and one
 * polymorphic RW.
 *
 * Two authoring shapes:
 *   - **Static singleton**: typically authored via [[TypedTool]] for
 *     ergonomics; persisted via `RW.static`.
 *   - **Dynamic record**: `case class ScriptTool(...) extends Tool derives RW`
 *     constructed at runtime by app flows and persisted via
 *     `Sigil.createTool`.
 *
 * `inputRW` declares the [[ToolInput]] subclass this tool consumes.
 * The provider stack parses LLM-supplied tool-call arguments through
 * that RW directly — no raw JSON crosses the dispatch boundary.
 */
trait Tool extends RecordDocument[Tool] {
  // ---- abstract ----

  def name: ToolName
  def description: String
  def inputRW: RW[? <: ToolInput]
  def execute(input: ToolInput, context: TurnContext): rapid.Stream[Event]

  // ---- defaults ----

  /** Categorical discriminator for client-side filtering — see
    * [[ToolKind]]. Defaults to [[BuiltinKind]]; subtypes from opt-in
    * modules override (e.g. `ScriptTool.kind = ScriptKind`,
    * `McpTool.kind = McpKind`). Apps building "manage your tools"
    * UIs use [[sigil.signal.RequestToolList]] with a `kinds` filter
    * to scope which records the user sees. */
  def kind: ToolKind = BuiltinKind

  def modes: Set[Id[Mode]] = Set(ConversationMode.id)

  /** The single [[SpaceId]] this tool is visible under. Defaults to
    * [[GlobalSpace]] — visible to every caller. Tools scoped to a
    * tenant / user / project override with their own space. There is
    * no multi-space tool: copy the record to surface the same
    * capability under a different space. */
  def space: SpaceId = GlobalSpace
  def keywords: Set[String] = Set.empty
  def examples: List[ToolExample] = Nil
  def createdBy: Option[ParticipantId] = None
  def _id: Id[Tool] = Id(name.value)
  def created: Timestamp = Tool.Epoch
  def modified: Timestamp = Tool.Epoch

  /** The schema's input definition. Defaults to `inputRW.definition`;
    * tools that need a dynamic schema (e.g. an enum populated from
    * runtime config) override this. */
  def inputDefinition: fabric.define.Definition = inputRW.definition

  /** How long this tool's `ToolResults` frames should remain in the
    * curated turn input.
    *
    *   - `None` (default) — keep forever; the result is durable and
    *     stays in the model's context as the conversation evolves.
    *   - `Some(0)` — the result is ephemeral; the curator may elide
    *     the call/result pair from the next turn's prompt because
    *     the result has been folded into a more compact representation
    *     (a participant projection, a `System` frame, the system
    *     prompt's "Suggested tools" / "Current mode" sections, etc.).
    *     Used by `find_capability` and `change_mode` so their
    *     verbose results don't accumulate in context.
    *   - `Some(n)` for `n > 0` — reserved for future "keep for n more
    *     agent turns" semantics. The standard curator currently
    *     treats any positive value the same as `None`; apps wanting
    *     turn-count-aware TTL extend the curator.
    *
    * The TTL is a declaration of intent — the curator's policy
    * decides exactly when to elide. [[StandardContextCurator]] honors
    * `Some(0)` by default. */
  def resultTtl: Option[Int] = None

  /** The description the LLM sees, given runtime context (active
    * mode + the live `Sigil`). Default returns the static
    * [[description]]; tools whose documentation depends on runtime
    * state override.
    *
    * Examples of overrides:
    *   - `change_mode` enumerates the available modes the agent
    *     can switch to (since they're app-registered).
    *   - A workflow tool could enumerate the workflows visible to
    *     the caller.
    *   - A lookup tool could list the catalog's known records.
    *
    * Providers call this when building the LLM's tool list —
    * descriptions are recomputed each turn, so apps don't need to
    * worry about caching staleness. The static [[description]] is
    * still used as the cached schema's description (for
    * `find_capability` listings, etc.) so consumers that only need
    * a bag-of-tools view still see something useful. */
  def descriptionFor(mode: Mode, sigil: Sigil): String = description

  /** Render-ready schema — providers turn this into the LLM's tool list. */
  lazy val schema: ToolSchema = ToolSchema(
    id = Id[ToolSchema](_id.value),
    name = name,
    description = description,
    input = inputDefinition,
    examples = examples
  )
}

object Tool extends PolyType[Tool]()(using scala.reflect.ClassTag(classOf[Tool])) with RecordDocumentModel[Tool] with JsonConversion[Tool] {
  /** Sentinel epoch for static tool timestamps. Dynamic tools set their own. */
  val Epoch: Timestamp = Timestamp(0L)

  // Expose PolyType's RW as the rw RecordDocumentModel needs.
  implicit override val rw: RW[Tool] = polyRW

  val toolName: I[String]          = field.index(_.name.value)
  val modeIds: I[Set[String]]      = field.index(_.modes.map(_.value))
  val spaceId: I[String]           = field.index(_.space.value)
  val keywordIndex: I[Set[String]] = field.index(_.keywords)
  val createdByIndex: I[Option[String]] = field.index(_.createdBy.map(_.value))
}
