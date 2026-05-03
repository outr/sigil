package sigil.skill

import fabric.rw.*
import lightdb.doc.{JsonConversion, RecordDocument, RecordDocumentModel}
import lightdb.id.Id
import lightdb.time.Timestamp
import fabric.rw.PolyType
import sigil.{GlobalSpace, SpaceId}
import sigil.participant.ParticipantId
import sigil.provider.Mode

/**
 * A persisted system-prompt overlay an agent can activate to specialize
 * its behavior for a focused task. Stored in [[sigil.db.SigilDB.skills]]
 * so static (app-defined) skills and dynamic (user-created) skills share
 * one collection, one query path, and one polymorphic RW — same pattern
 * as [[sigil.tool.Tool]].
 *
 * Two authoring shapes:
 *   - **Static singleton**: `case object MyCodingSkill extends Skill { … }`,
 *     registered via `Sigil.staticSkills`. Upserted on startup by
 *     `StaticSkillSyncUpgrade`; orphan static skills (createdBy = None
 *     whose name isn't in the current set) are pruned automatically.
 *   - **Dynamic record**: `case class UserSkill(...) extends Skill derives RW`
 *     constructed at runtime (e.g. via a "save skill" tool the user
 *     dictates) and persisted via `Sigil.createSkill`. User-created
 *     records (createdBy.nonEmpty) survive every restart.
 *
 * `modes` declares which conversation modes this skill is available in.
 * `find_capability` filters skill matches to those whose `modes` set
 * contains the request's current mode — so a coding-mode skill never
 * surfaces during a chat turn. Empty `modes` = visible in every mode.
 *
 * `space` scopes the skill to a tenant / project / user (single
 * assignment, like [[sigil.tool.Tool]] / [[sigil.conversation.ContextMemory]]).
 * To surface the same content under multiple spaces, copy the record.
 *
 * Activation: `find_capability` returns the skill as a
 * [[sigil.tool.discovery.CapabilityMatch]] with status
 * `RequiresSetup("activate_skill(\"name\")")`. The agent calls
 * `activate_skill` to load the skill into its
 * [[sigil.conversation.ParticipantProjection.activeSkills]] under
 * `SkillSource.Discovery`.
 */
trait Skill extends RecordDocument[Skill] {
  // ---- abstract ----

  def name: String

  /** What the skill does — surfaced in `find_capability` matches. */
  def description: String

  /** The system-prompt overlay activated under
    * [[sigil.conversation.SkillSource.Discovery]] when the agent calls
    * `activate_skill`. Multi-line markdown is fine; the framework
    * concatenates active skills into the rendered system prompt. */
  def content: String

  // ---- defaults ----

  /** Conversation modes this skill is available in. Empty = visible in
    * every mode (rare; most skills are mode-specific). */
  def modes: Set[Id[Mode]] = Set.empty

  /** The single [[SpaceId]] this skill is visible under. Defaults to
    * [[GlobalSpace]] — visible to every caller. */
  def space: SpaceId = GlobalSpace

  /** Curated keywords boosting BM25 discovery score. */
  def keywords: Set[String] = Set.empty

  /** The participant that authored the skill. `None` for static
    * (app-shipped) skills; set for user-created records so
    * `StaticSkillSyncUpgrade` knows not to prune them. */
  def createdBy: Option[ParticipantId] = None

  def _id: Id[Skill] = Id(name)
  def created: Timestamp = Skill.Epoch
  def modified: Timestamp = Skill.Epoch
}

object Skill extends PolyType[Skill]()(using scala.reflect.ClassTag(classOf[Skill])) with RecordDocumentModel[Skill] with JsonConversion[Skill] {
  /** Sentinel epoch for static skill timestamps. Dynamic skills set their own. */
  val Epoch: Timestamp = Timestamp(0L)

  implicit override val rw: RW[Skill] = polyRW

  val skillName: I[String]              = field.index(_.name)
  val modeIds: I[Set[String]]           = field.index(_.modes.map(_.value))
  val spaceId: I[String]                = field.index(_.space.value)
  val keywordIndex: I[Set[String]]      = field.index(_.keywords)
  val createdByIndex: I[Option[String]] = field.index(_.createdBy.map(_.value))

  /** Tokenized full-text index over name + description + content +
    * keywords. Backs `find_capability`'s BM25-scored search via
    * [[sigil.skill.DbSkillFinder]] — same shape as
    * [[sigil.tool.Tool.searchText]]. */
  val searchText: lightdb.field.Field.Tokenized[Skill] =
    field.tokenized("searchText", (s: Skill) =>
      s"${s.name} ${s.description} ${s.content} ${s.keywords.mkString(" ")}"
    )
}
