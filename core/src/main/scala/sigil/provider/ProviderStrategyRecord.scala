package sigil.provider

import fabric.rw.*
import lightdb.doc.{JsonConversion, RecordDocument, RecordDocumentModel}
import lightdb.id.Id
import lightdb.time.Timestamp
import rapid.Unique
import sigil.SpaceId

/**
 * Persisted form of a [[ProviderStrategy]]. Apps create / list /
 * delete these records via `Sigil.saveProviderStrategy` etc.; the
 * framework materializes them into live `ProviderStrategy` instances
 * via `Sigil.resolveProviderStrategy` at agent-dispatch time.
 *
 *   - **`space`** — visibility scope. Multiple records can share a
 *     space; a viewer with access to that space sees them via
 *     `listProviderStrategies(space)`. Independent from the
 *     "currently assigned" relationship — see
 *     [[SpaceProviderAssignment]].
 *   - **`label`** — human-readable name shown in
 *     `list_provider_strategies` output and the `switch_model`
 *     disambiguation UI.
 *   - **`defaultCandidates`** — fallback chain consulted when no
 *     `routes` entry matches the work type. Order matters; first
 *     entry is preferred.
 *   - **`routeCandidates`** — per-`WorkType` chains, keyed by
 *     `WorkType.value`. Missing keys fall through to
 *     `defaultCandidates`.
 *
 * Empty records (no defaults, no routes) materialize to a no-op
 * strategy; in practice every record has at least one default
 * candidate.
 */
case class ProviderStrategyRecord(space: SpaceId,
                                  label: String,
                                  defaultCandidates: List[ModelCandidate] = Nil,
                                  routeCandidates: Map[String, List[ModelCandidate]] = Map.empty,
                                  metadata: Map[String, String] = Map.empty,
                                  created: Timestamp = Timestamp(),
                                  modified: Timestamp = Timestamp(),
                                  _id: Id[ProviderStrategyRecord] = ProviderStrategyRecord.id())
  extends RecordDocument[ProviderStrategyRecord]

object ProviderStrategyRecord extends RecordDocumentModel[ProviderStrategyRecord] with JsonConversion[ProviderStrategyRecord] {
  implicit override def rw: RW[ProviderStrategyRecord] = RW.gen

  override def id(value: String = Unique()): Id[ProviderStrategyRecord] = Id(value)

  val spaceKey: I[String] = field.index("spaceKey", _.space.value)
  val createdAt: I[Long] = field.index("createdAt", _.created.value)
}
