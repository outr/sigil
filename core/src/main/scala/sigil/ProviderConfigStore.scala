package sigil

import lightdb.id.Id
import rapid.Task
import sigil.participant.ParticipantId

/**
 * Provider-config / strategy CRUD cluster — persistence and
 * authorization for [[sigil.provider.ProviderConfig]],
 * [[sigil.provider.ProviderStrategyRecord]], and per-space strategy
 * assignments.
 *
 * Mixed into [[Sigil]]; the methods remain public members of `Sigil`.
 * The self-type reaches the rest of the framework's state (`withDB`,
 * `accessibleSpaces`, `materializeStrategy`).
 */
trait ProviderConfigStore { this: Sigil =>

  /** Persist or update a [[sigil.provider.ProviderConfig]] record. */
  def saveProviderConfig(config: sigil.provider.ProviderConfig): Task[sigil.provider.ProviderConfig] =
    withDB(_.providerConfigs.transaction(_.upsert(
      config.copy(modified = lightdb.time.Timestamp())
    )))

  /** Read a [[sigil.provider.ProviderConfig]] by id. Authz: caller's
    * `accessibleSpaces` must include the record's space. */
  def getProviderConfig(id: Id[sigil.provider.ProviderConfig],
                        chain: List[ParticipantId]): Task[Option[sigil.provider.ProviderConfig]] =
    withDB(_.providerConfigs.transaction(_.get(id))).flatMap {
      case None => Task.pure(None)
      case Some(c) =>
        accessibleSpaces(chain).map { spaces =>
          if (spaces.contains(c.space)) Some(c) else None
        }
    }

  /** List every [[sigil.provider.ProviderConfig]] in `space` that
    * the caller's chain authorizes. */
  def listProviderConfigs(space: SpaceId,
                          chain: List[ParticipantId]): Task[List[sigil.provider.ProviderConfig]] =
    accessibleSpaces(chain).flatMap { spaces =>
      if (!spaces.contains(space)) Task.pure(Nil)
      else withDB(_.providerConfigs.transaction(_.list)).map(_.toList.filter(_.space == space))
    }

  /** Delete a [[sigil.provider.ProviderConfig]] by id. Authz check
    * mirrors `getProviderConfig`. */
  def deleteProviderConfig(id: Id[sigil.provider.ProviderConfig],
                           chain: List[ParticipantId]): Task[Unit] =
    getProviderConfig(id, chain).flatMap {
      case None    => Task.unit
      case Some(_) => withDB(_.providerConfigs.transaction(_.delete(id))).unit
    }

  /** Persist or update a [[sigil.provider.ProviderStrategyRecord]]. */
  def saveProviderStrategy(record: sigil.provider.ProviderStrategyRecord): Task[sigil.provider.ProviderStrategyRecord] =
    withDB(_.providerStrategies.transaction(_.upsert(
      record.copy(modified = lightdb.time.Timestamp())
    )))

  /** Read a [[sigil.provider.ProviderStrategyRecord]] by id with
    * `accessibleSpaces` authz. */
  def getProviderStrategy(id: Id[sigil.provider.ProviderStrategyRecord],
                          chain: List[ParticipantId]): Task[Option[sigil.provider.ProviderStrategyRecord]] =
    withDB(_.providerStrategies.transaction(_.get(id))).flatMap {
      case None => Task.pure(None)
      case Some(r) =>
        accessibleSpaces(chain).map { spaces =>
          if (spaces.contains(r.space)) Some(r) else None
        }
    }

  /** List every [[sigil.provider.ProviderStrategyRecord]] visible
    * to the caller in `space`. The "visibility scope" — independent
    * from which one is currently `assigned` to the space. */
  def listProviderStrategies(space: SpaceId,
                             chain: List[ParticipantId]): Task[List[sigil.provider.ProviderStrategyRecord]] =
    accessibleSpaces(chain).flatMap { spaces =>
      if (!spaces.contains(space)) Task.pure(Nil)
      else withDB(_.providerStrategies.transaction(_.list)).map(_.toList.filter(_.space == space))
    }

  /** Delete a [[sigil.provider.ProviderStrategyRecord]] by id with
    * authz. Also unassigns it from any space currently using it
    * (cascading cleanup). */
  def deleteProviderStrategy(id: Id[sigil.provider.ProviderStrategyRecord],
                             chain: List[ParticipantId]): Task[Unit] =
    getProviderStrategy(id, chain).flatMap {
      case None    => Task.unit
      case Some(_) =>
        for {
          // Cascade: any space whose assignment points at this record loses its assignment.
          //
          assigns <- withDB(_.providerAssignments.transaction(_.list))
          orphans  = assigns.toList.filter(_.strategyId == id)
          _       <- withDB(_.providerAssignments.transaction { tx =>
                       Task.sequence(orphans.map(o => tx.delete(o._id))).unit
                     })
          _       <- withDB(_.providerStrategies.transaction(_.delete(id))).unit
        } yield ()
    }

  /** Assign a strategy to a space — replaces any existing
    * assignment. Caller's chain must authorize the space. */
  def assignProviderStrategy(space: SpaceId,
                             strategyId: Id[sigil.provider.ProviderStrategyRecord],
                             chain: List[ParticipantId]): Task[Unit] =
    accessibleSpaces(chain).flatMap { spaces =>
      if (!spaces.contains(space)) Task.unit
      else withDB(_.providerAssignments.transaction(_.upsert(
        sigil.provider.SpaceProviderAssignment(space, strategyId)
      ))).unit
    }

  /** Remove a space's strategy assignment. The strategy record itself
    * is unaffected. Caller's chain must authorize the space. */
  def unassignProviderStrategy(space: SpaceId,
                               chain: List[ParticipantId]): Task[Unit] =
    accessibleSpaces(chain).flatMap { spaces =>
      if (!spaces.contains(space)) Task.unit
      else withDB(_.providerAssignments.transaction(_.delete(
        sigil.provider.SpaceProviderAssignment.idFor(space)
      ))).unit
    }

  /** Read the assignment record for a space (or `None` when no
    * strategy is currently assigned). No authz check — the
    * presence/absence of an assignment is benign metadata. */
  def assignedProviderStrategy(space: SpaceId): Task[Option[Id[sigil.provider.ProviderStrategyRecord]]] =
    withDB(_.providerAssignments.transaction(_.get(
      sigil.provider.SpaceProviderAssignment.idFor(space)
    ))).map(_.map(_.strategyId))

  /** Materialize the strategy currently assigned to `space` into a
    * live [[sigil.provider.ProviderStrategy]] instance. Returns
    * `None` if no assignment exists or the assigned record can't
    * be loaded — agent dispatch falls back to the agent's pinned
    * `modelId` in that case.
    *
    * The materialization is straightforward today (defaults +
    * routes → `ProviderStrategy.routed`); apps with custom strategy
    * semantics override to return their own `ProviderStrategy`
    * implementation regardless of the persisted record. */
  def resolveProviderStrategy(space: SpaceId): Task[Option[sigil.provider.ProviderStrategy]] =
    assignedProviderStrategy(space).flatMap {
      case None => Task.pure(None)
      case Some(strategyId) =>
        withDB(_.providerStrategies.transaction(_.get(strategyId))).map(_.map(materializeStrategy))
    }
}
