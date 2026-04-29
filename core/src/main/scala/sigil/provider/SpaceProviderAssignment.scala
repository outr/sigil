package sigil.provider

import fabric.rw.*
import lightdb.doc.{JsonConversion, RecordDocument, RecordDocumentModel}
import lightdb.id.Id
import lightdb.time.Timestamp
import sigil.SpaceId

/**
 * Maps a [[SpaceId]] to its currently-assigned
 * [[ProviderStrategyRecord]] — the "many strategies stored, one
 * active per space" relationship.
 *
 * Record id is derived from the space's `value` so lookup is keyed
 * directly: `db.providerAssignments.transaction(_.get(Id(space.value)))`.
 * Apps call `Sigil.assignProviderStrategy(space, strategyId)` to
 * upsert; `Sigil.unassignProviderStrategy(space)` to delete.
 *
 * No-assignment is a valid state — apps without per-space strategy
 * config simply have no assignment record, and the framework falls
 * back to the agent's pinned `modelId`.
 */
case class SpaceProviderAssignment(space: SpaceId,
                                   strategyId: Id[ProviderStrategyRecord],
                                   created: Timestamp = Timestamp(),
                                   modified: Timestamp = Timestamp(),
                                   _id: Id[SpaceProviderAssignment])
  extends RecordDocument[SpaceProviderAssignment]

object SpaceProviderAssignment extends RecordDocumentModel[SpaceProviderAssignment] with JsonConversion[SpaceProviderAssignment] {
  implicit override def rw: RW[SpaceProviderAssignment] = RW.gen

  /** Id derived from the space's `value` — one assignment per space. */
  def idFor(space: SpaceId): Id[SpaceProviderAssignment] = Id(space.value)

  override def id(value: String): Id[SpaceProviderAssignment] = Id(value)

  def apply(space: SpaceId, strategyId: Id[ProviderStrategyRecord]): SpaceProviderAssignment =
    SpaceProviderAssignment(space, strategyId, Timestamp(), Timestamp(), idFor(space))

  val spaceKey: I[String] = field.index("spaceKey", _.space.value)
}
