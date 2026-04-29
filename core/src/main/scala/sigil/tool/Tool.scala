package sigil.tool

import fabric.rw.*
import lightdb.doc.{JsonConversion, RecordDocument, RecordDocumentModel}
import lightdb.id.Id
import lightdb.time.Timestamp
import sigil.{GlobalSpace, PolyType, Sigil, SpaceId, TurnContext}
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

object Tool extends RecordDocumentModel[Tool] with JsonConversion[Tool] with PolyType[Tool] {
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
