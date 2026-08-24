package sigil

import fabric.rw.*
import lightdb.id.Id
import lightdb.lucene.LuceneStore
import lightdb.postgresql.PostgreSQLStoreManager
import lightdb.rocksdb.RocksDBSharedStore
import lightdb.sql.connect.{HikariConnectionManager, SQLConfig}
import lightdb.store.CollectionManager
import lightdb.store.split.SplitStoreManager
import lightdb.time.Timestamp
import lightdb.util.Nowish
import profig.Profig
import rapid.{Stream, Task, logger}
import sigil.conversation.{ActiveSkillSlot, ContextFrame, ContextKey, ContextMemory, ContextSummary, Conversation, EncodedContext, FrameBuilder, MemorySource, MemoryStatus, ParticipantProjection, ProgressContext, SkillSource, ToolCallState, Topic, TopicEntry, TopicShiftResult, TurnInput, TurnPlan, UpsertMemoryResult}
import sigil.SpaceId
import sigil.cache.ModelRegistry
import sigil.controller.OpenRouter
import sigil.embedding.{EmbeddingProvider, NoOpEmbeddingProvider}
import sigil.governor.{BudgetDirective, BudgetGovernor, CheckpointIntervention, GovernorContext}
import sigil.governor.{DegenerateGenerationGovernor, GovernorVote, OutcomeGovernor, PlainTextReplyGovernor,
  ProgressGovernor, TurnDecisionGovernor, TurnGovernor}
import sigil.transport.SignalTransport

import java.nio.file.Path
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import sigil.tool.consult.{ConsultTool, TopicClassifierTool}
import sigil.provider.{GenerationSettings, TokenUsage}
import sigil.db.{DefaultSigilDB, Model, SigilDB}
import sigil.dispatcher.{StopFlag, TriggerFilter}
import sigil.event.{AgentState, CapabilityResults, Event, EventsPage, Message, MessageRole, MessageVisibility, ModeChange, Stop, ToolInvoke, TopicChange, TopicChangeKind}
import sigil.role.Role
import sigil.orchestrator.{BudgetScope, Directive, Orchestrator}
import sigil.provider.{Complexity, ConversationMode, ConversationRequest, Mode, ProviderStrategy, ReasoningMode, ToolPolicy, WorkType}
import sigil.information.Information
import sigil.participant.{AgentParticipant, AgentParticipantId, DefaultAgentParticipant, Participant, ParticipantId}
import sigil.pipeline.{ContentExternalizationTransform, GeocodingEnrichmentEffect, InboundTransform, LocationCaptureTransform, MemoryCacheInvalidationEffect, MessageIndexingEffect, RedactLocationTransform, RespondOptionsSelectionFramingTransform, SettledEffect, SignalHub, TopicIndexCanonicalizingTransform, ViewerTransform, WorkerConversationAddressingTransform}
import sigil.render.{ContentRenderer, HtmlRenderer, MarkdownRenderer, PlainTextRenderer, SlackMrkdwnRenderer}
import sigil.provider.Provider
import sigil.provider.{ContextSection, ContextSections, InstructionTier, ModelProfile, PromptShape, Reliability, ResolvedReferences}
import sigil.service.Service
import sigil.signal.{AgentActivity, AgentStateDelta, CoreSignals, Delta, EventState, LocationDelta, Notice, ServiceLogSignal, ServiceStatusSignal, Signal, ToolDelta, TopicDelta}
import sigil.spatial.{Geocoder, NoOpGeocoder, Place}
import sigil.tool.Tool
import sigil.tool.fs.{FileSystemContext, LocalFileSystemContext}
import sigil.tool.core.{CoreTools, FindCapabilityTool}
import sigil.tool.model.ResponseContent
import sigil.tool.{ToolFinder, ToolInput}
import sigil.vector.{NoOpVectorIndex, VectorIndex, VectorPoint, VectorPointId, VectorSearchResult}

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference


/**
 * Boot and teardown cluster — phase-2 of the lifecycle. Owns
 * [[Sigil.instance]] (store open, upgrades, vector wiring, stale-Active
 * reconciliation, mode-skill validation, background fibers), the model
 * catalog load / refresh schedule, the maintenance-task fibers and the
 * expired-memory sweep they drive, the `withDB` accessor, and
 * [[Sigil.shutdown]] with its cancellation flag.
 *
 * Mixed into [[Sigil]]; the self-type reaches `buildDB`,
 * `polymorphicRegistrations`, the config knobs that time the fibers,
 * and the resources shutdown releases.
 */
trait LifecycleOps { this: Sigil =>

  /** True once [[instance]]'s task body has begun executing — used by
    * [[shutdown]] to skip DB-dispose when no instance was ever
    * constructed (e.g. codegen-only paths that ran
    * `polymorphicRegistrations` without opening the store). */
  private val instanceStarted: java.util.concurrent.atomic.AtomicBoolean =
    new java.util.concurrent.atomic.AtomicBoolean(false)

  /** Synchronous handle to the fully-constructed [[SigilInstance]],
    * set once [[instance]]'s task body completes. Lets hot-path
    * helpers (notably [[eventsFor]]'s batched-scope snapshot) reach
    * the live `DB` without re-running the `instance` task or
    * blocking on its singleton. `None` until the store is open. */
  private[sigil] val startedInstance: java.util.concurrent.atomic.AtomicReference[Option[SigilInstance]] =
    new java.util.concurrent.atomic.AtomicReference[Option[SigilInstance]](None)

  val instance: Task[SigilInstance] = Task.defer {
    for {
      _ <- polymorphicRegistrations
      _ <- logger.info("Sigil initializing...")
      _ = ContextSections.shedCascade(resolvedContextSections)
      _ <- Task(Profig.loadDefaults())
      _ = instanceStarted.set(true)
      config = Profig("sigil").as[Config]
      (directory, collectionStore) = config.postgres match {
        case Some(pg) =>
          val cm = HikariConnectionManager(SQLConfig(
            jdbcUrl = pg.jdbcUrl,
            driverClassName = Some("org.postgresql.Driver"),
            username = pg.username,
            password = pg.password,
            maximumPoolSize = pg.maximumPoolSize
          ))
          (None, PostgreSQLStoreManager(cm): CollectionManager)
        case None =>
          (Some(config.dbPath), SplitStoreManager(RocksDBSharedStore(config.dbPath), LuceneStore): CollectionManager)
      }
      db = buildDB(
        directory = directory,
        storeManager = collectionStore,
        appUpgrades = List(
          new sigil.upgrade.ContextFrameToolResultMigrationUpgrade,
          new sigil.upgrade.ToolOutputReconcileUpgrade,
          new sigil.tool.StaticToolSyncUpgrade(resolvedStaticTools),
          new sigil.skill.StaticSkillSyncUpgrade(staticSkills)
        )
      )
      _ <- db.init
      _ <- reconcileStaleActiveEvents(db)
      _ <- if (vectorWired) vectorIndex.ensureCollection(embeddingProvider.dimensions)
           else Task.unit
      _ <- loadAndRefreshModels(db)
      _ <- validateModeSkillSizes()
      _ <- startExpiredMemorySweep()
      _ <- startMaintenanceTasks()
      inst = SigilInstance(
               config = config,
               db = db
             )
      _ = startedInstance.set(Some(inst))
    } yield inst
  }.singleton

  /** Test-only hook to trigger boot-time reconciliation against the
    * already-opened DB without re-creating the Sigil instance. */
  protected[sigil] def runStaleActiveReconciliationTask: Task[Unit] =
    withDB(db => reconcileStaleActiveEvents(db))

  private def reconcileStaleActiveEvents(db: sigil.db.SigilDB): Task[Unit] =
    db.events.transaction { tx =>
      tx.list.flatMap { rows =>
        val stale = rows.iterator.filter(_.state == sigil.signal.EventState.Active).toList
        if (stale.isEmpty) Task.unit
        else {
          val reconciled: List[sigil.event.Event] = stale.map {
            case m: sigil.event.Message =>
              m.copy(
                state = sigil.signal.EventState.Complete,
                disposition = sigil.event.MessageDisposition.Failure(
                  recoverable = false,
                  errorContext = Some(sigil.event.ErrorContext(
                    classification = sigil.event.ErrorClassification.FrameworkBug,
                    exceptionClass = None,
                    message = "stale-from-previous-session: process exited before this Message settled",
                    suggestion = Some("the prior turn was interrupted; nothing to retry"),
                    frameworkBugLikelihood = 0.0
                  ))
                )
              )
            case a: sigil.event.AgentState =>
              a.copy(state = sigil.signal.EventState.Complete, activity = sigil.signal.AgentActivity.Idle)
            case other => other.withState(sigil.signal.EventState.Complete)
          }
          Task.sequence(reconciled.map(tx.upsert)).map { _ =>
            scribe.info(s"reconcileStaleActiveEvents: closed ${reconciled.size} stale Active event(s)")
          }
        }
      }
    }

  /** Validate that every registered Mode's bundled skill content (if
    * any) fits under [[modeSkillShareLimit]] × largest-model-context.
    * Modes share `SkillSource.Mode` slot; one per active mode. Apps
    * with intentionally large skills override [[modeSkillShareLimit]]
    * or skip the validation by overriding this method.
    *
    * Basis is the LARGEST registered model — complexity-routed setups
    * register a small local model for `Complexity.Low` traffic that
    * by design won't run the modes whose skills this validator gates.
    * The skills always render against a frontier model with ample
    * headroom; the validator should pessimise against the AGENT's
    * configured ceiling rather than the cost-tier floor. */
  protected def validateModeSkillSizes(): Task[Unit] = Task {
    sigil.conversation.CoreContextValidator.largestModelContext(this) match {
      case None => () // no models registered → can't validate
      case Some(model) =>
        val limit = (model.contextLength.toDouble * modeSkillShareLimit).toInt
        val violations = availableModes.flatMap(m => m.skill.toList.map(slot => m -> slot))
          .filter { case (_, slot) => sigil.tokenize.HeuristicTokenizer.count(slot.content) > limit }
        if (violations.nonEmpty) {
          val msg = violations.map { case (mode, slot) =>
            val tokens = sigil.tokenize.HeuristicTokenizer.count(slot.content)
            s"mode '${mode.name}' skill '${slot.name}' is ${tokens} tok (limit ${limit})"
          }.mkString("; ")
          throw new IllegalStateException(
            s"Mode skill content exceeds modeSkillShareLimit (${(modeSkillShareLimit * 100).toInt}%): $msg. " +
              s"Trim the skill content or override Sigil.modeSkillShareLimit."
          )
        }
    }
  }

  /**
   * Boot-time model-catalog load + refresh.
   *
   * Flow:
   *   1. Read the persisted `db.models` snapshot, seed the in-memory
   *      [[sigil.cache.ModelRegistry]] from it.
   *   2. Decide whether to block on an OpenRouter refresh:
   *        - never-refreshed (empty list): YES; failure fails boot.
   *        - stale (age > [[modelRefreshInterval]]): YES; failure
   *          warns and continues with the cached catalog.
   *        - fresh: NO; proceed.
   *   3. Schedule the next refresh at `stamp + modelRefreshInterval`
   *      (so the next call aligns with the stamp's age, not "now +
   *      interval"). Subsequent firings every interval.
   *
   * Skipped entirely when [[loadOpenRouterModels]] is `false` —
   * apps with non-OpenRouter catalogs (LlamaCpp-only, tests with
   * synthetic pre-registered models, custom-catalog deployments)
   * populate the registry themselves and don't pay for the network
   * round-trip.
   */
  private def loadAndRefreshModels(db: DB): Task[Unit] =
    if (!loadOpenRouterModels) Task.unit
    else for {
      stored      <- db.models.get()
      _           <- if (stored.list.nonEmpty) seedCatalogSnapshot(stored.list) else Task.unit
      isFresh     = stored.list.nonEmpty &&
                      (Timestamp().value - stored.refreshed.value) < modelRefreshInterval.toMillis
      _           <- if (isFresh) Task.unit else blockingRefresh(db, hadPriorCache = stored.list.nonEmpty)
      // Re-read after the (possibly-just-run) blocking refresh so the
      // schedule's first sleep aligns with the latest stamp.
      latest      <- db.models.get()
      _           <- scheduleNextRefresh(db, latest.refreshed)
    } yield ()

  /** Seed the registry's catalog slice from the persisted `db.models`
    * snapshot.
    *
    * The snapshot mirrors the aggregate catalog only — llama.cpp and
    * other backend-sourced models are deliberately never persisted —
    * so it restores that one source. A provider that already seeded
    * its own slice keeps it, whatever order boot ran in. */
  private[sigil] def seedCatalogSnapshot(models: List[Model]): Task[Unit] =
    cache.catalogSource.set(models).map { _ =>
      scribe.info(s"Seeded the model catalog from the persisted snapshot — registry slices: ${cache.sliceSummary}")
    }

  /** One-shot blocking refresh from OpenRouter. Delegates to the
    * boot-safe (sigil, db) overload of [[OpenRouter.refreshModels]] so
    * the boot fiber doesn't re-enter [[withDB]] — that would await the
    * in-flight `Sigil.instance.singleton` against itself and deadlock.
    * Post-boot callers use the public 1-arg overload
    * which resolves the db via `withDB` normally. */
  private def blockingRefresh(db: DB, hadPriorCache: Boolean): Task[Unit] =
    OpenRouter.refreshModels(this, db).handleError { e =>
      if (hadPriorCache)
        Task { scribe.warn(s"OpenRouter refresh failed; continuing with cached registry: ${e.getMessage}"); () }
      else
        Task.error(new RuntimeException(
          s"OpenRouter refresh failed AND no cached catalog exists — Sigil cannot start without a model registry. " +
            s"Either provide network access to OpenRouter on boot, override `loadOpenRouterModels = false` and register " +
            s"models manually via `sigil.cache.merge(...)`, or restore a `db.models` snapshot. Cause: ${e.getMessage}",
          e
        ))
    }

  /** Schedule the next refresh at `lastRefreshed + interval`, then
    * loop every interval after. Floors the initial delay at 1 minute
    * so a clock skew or a stamp-just-now case doesn't fire instantly. */
  private def scheduleNextRefresh(db: DB, lastRefreshed: Timestamp): Task[Unit] = Task {
    val elapsedMs = Timestamp().value - lastRefreshed.value
    val initialDelayMs = math.max(60_000L, modelRefreshInterval.toMillis - elapsedMs)
    val intervalMs = modelRefreshInterval.toMillis
    def loop(delayMs: Long): Task[Unit] =
      if (isShutdown) Task.unit
      else rapid.Task.sleep(scala.concurrent.duration.Duration(delayMs, scala.concurrent.duration.MILLISECONDS)).flatMap { _ =>
        if (isShutdown) Task.unit
        else blockingRefresh(db, hadPriorCache = true).flatMap(_ => loop(intervalMs))
      }
    loop(initialDelayMs).startUnit()
    ()
  }

  /**
   * Kick off the periodic expired-memory sweep. First sweep runs
   * immediately; subsequent ones every [[expiredMemorySweepInterval]].
   * Hard-deletes every [[ContextMemory]] whose `expiresAt` is set and
   * not in the future — the durable record AND the vector-index point
   * are removed (the data was already invisible to retrieval since
   * `StandardMemoryRetriever.isExpired` filters at every turn; the
   * sweep just reclaims the storage). Failures are logged and
   * swallowed.
   */
  private def startExpiredMemorySweep(): Task[Unit] = expiredMemorySweepInterval match {
    case None => Task.unit
    case Some(interval) =>
      def safeSweep: Task[Unit] = sweepExpiredMemories(Timestamp()).map { count =>
        if (count > 0) scribe.info(s"Expired-memory sweep removed $count record(s)")
      }.handleError { e =>
        Task { scribe.warn(s"Expired-memory sweep failed: ${e.getMessage}"); () }
      }
      def loop: Task[Unit] =
        if (isShutdown) Task.unit
        else safeSweep.flatMap(_ => Task.sleep(interval)).flatMap(_ => loop)
      Task { loop.startUnit(); () }
  }

  /**
   * Per-task fibers for every entry in [[maintenanceTasks]]. Each
   * task runs on its own cadence; failures are logged at WARN and
   * swallowed so a transient hiccup doesn't break the loop. Boots
   * after the DB is up but before [[instance]] resolves, so the first
   * tick of a `runImmediatelyOnStart = true` task fires once the
   * Sigil is fully ready.
   */
  private def startMaintenanceTasks(): Task[Unit] =
    rapid.Task.sequence(maintenanceTasks.map { task =>
      def safeRun: Task[Unit] = task.runOnce(this).handleError { e =>
        Task { scribe.warn(s"Maintenance task '${task.name}' failed: ${e.getMessage}"); () }
      }
      def loop: Task[Unit] =
        if (isShutdown) Task.unit
        else safeRun.flatMap(_ => Task.sleep(task.interval)).flatMap(_ => loop)
      val firstFire =
        if (task.runImmediatelyOnStart) loop
        else Task.sleep(task.interval).flatMap(_ => loop)
      Task { firstFire.startUnit(); () }
    }).map(_ => ())

  /**
   * One-shot sweep — deletes every memory with `expiresAt` set and
   * not in the future. Returns the count removed. Apps with retention
   * policies that need a different cadence override
   * [[expiredMemorySweepInterval]]; apps that need a custom sweep
   * shape (e.g. preserve archived versions) override this method.
   */
  def sweepExpiredMemories(now: Timestamp): Task[Int] =
    withDB(_.memories.transaction { tx =>
      // RangeLong on an Option[Long]-projected field naturally
      // excludes None values (only rows whose projected `Some(value)`
      // lands in the range match). `from = None` means "no lower
      // bound" so any expiresAt up to `now` is in scope.
      tx.query
        .filter(_ => lightdb.filter.Filter.RangeLong[ContextMemory](
          fieldName = ContextMemory.expiresAtValue.name,
          from = None,
          to = Some(now.value)
        ))
        .toList
        .flatMap { expired =>
          Task.sequence(expired.map(m => forgetMemoryById(m._id))).map(_ => expired.size)
        }
    })

  /** Hard-delete a memory by id. Removes the row from the store AND
    * the corresponding vector-index point (when wired). Used by the
    * expired-memory sweep; apps can call directly for ad-hoc deletes. */
  def forgetMemoryById(id: Id[ContextMemory]): Task[Boolean] =
    withDB(_.memories.transaction { tx =>
      tx.get(id).flatMap {
        case None    => Task.pure(false)
        case Some(_) => tx.delete(id).map(_ => true)
      }
    }).flatMap { removed =>
      if (!removed || !vectorWired) Task.pure(removed)
      else vectorIndex.delete(VectorPointId(id.value)).map(_ => removed).handleError { e =>
        Task { scribe.warn(s"Vector delete failed during forgetMemoryById(${id.value}): ${e.getMessage}"); removed }
      }
    }

  /** Resolve the [[DB]] and run `f` against it. Backed by
    * `Sigil.instance.flatMap` so callers don't have to think about
    * initialization timing — `withDB` waits if the instance hasn't
    * fully booted yet.
    *
    * **Don't call from inside `Sigil.instance`'s init for-comp.** The
    * `instance` task is `.singleton`-memoised; `withDB` re-entering
    * during init awaits the in-flight resolution against itself and
    * deadlocks silently. Boot-path code receives the
    * `db` as a parameter — pass it through directly. See
    * [[OpenRouter.refreshModels]]'s `(sigil, db)` overload for the
    * canonical pattern. */
  def withDB[Return](f: DB => Task[Return]): Task[Return] = instance.flatMap(sigil => f(sigil.db))

  // -- shutdown --

  /**
   * Releases shared resources so the JVM can exit cleanly. Disposes
   * the [[sigil.db.SigilDB]] (which closes RocksDB / Lucene / Postgres
   * connection pool depending on storage), and signals the model-
   * refresh background fiber to stop on its next iteration.
   *
   * CLI / one-shot consumers should call this before returning from
   * `main`. Long-running servers don't need to call it during normal
   * operation — the resources live for the process lifetime.
   *
   * After shutdown, calls into [[withDB]] / `instance` are not
   * supported. Idempotent — repeated calls return immediately.
   */
  def shutdown: Task[Unit] = Task.defer {
    shutdownRequested.set(true)
    // Run user-overridable [[onShutdown]] first so module-owned
    // resources (Metals subprocesses, browser sessions, MCP
    // connections) get a clean teardown signal BEFORE the hub
    // closes — those resources may publish a final Notice / Event
    // on the way out and need the hub to deliver it. Then close
    // the SignalHub so every active `sigil.signals` /
    // `sigil.signalsFor(viewer)` subscriber's stream completes
    // naturally — their fibers exit without needing app-side
    // running-flag bookkeeping. Finally dispose the DB *only if*
    // the instance was ever constructed; codegen / introspection
    // paths that ran `polymorphicRegistrations` without opening
    // the store shouldn't have shutdown force the DB open. All
    // stages are best-effort: failures are logged but don't block
    // subsequent teardown.
    onShutdown.handleError { t =>
      Task { scribe.warn(s"Sigil shutdown: onShutdown failed: ${t.getMessage}"); () }
    }.flatMap { _ =>
      // Land the accumulated retrieval-access bumps before the store
      // closes — otherwise a clean shutdown loses the interval's
      // reinforcement signal the same way a kill would.
      if (!instanceStarted.get()) Task.unit
      else flushMemoryAccesses.unit.handleError { t =>
        Task { scribe.warn(s"Sigil shutdown: flushMemoryAccesses failed: ${t.getMessage}"); () }
      }
    }.flatMap { _ =>
      Task { memoryRetrievalCache.clear(); hub.close() }
    }.flatMap { _ =>
      if (!instanceStarted.get()) Task.unit
      else instance.flatMap { sigil =>
        sigil.db.dispose.handleError { t =>
          Task { scribe.warn(s"Sigil shutdown: db.dispose failed: ${t.getMessage}"); () }
        }.map(_ => startedInstance.set(None))
      }
    }
  }

  /**
   * Hook for module-mixed traits and apps to release subprocess /
   * connection / fiber resources during [[shutdown]]. Runs BEFORE
   * the SignalHub closes and BEFORE DB dispose, so implementations
   * can still publish a final Notice / Event on the way out.
   *
   * Module traits (e.g. `MetalsSigil` killing spawned Metals
   * subprocesses) override this and chain `super.onShutdown` so a
   * Sigil mixing in N modules tears each down in declaration
   * order. Default is a no-op.
   *
   * Failures are logged but don't abort the rest of the shutdown
   * pipeline — half-released resources are better than a hung
   * teardown.
   */
  protected def onShutdown: Task[Unit] = Task.unit

  /** Cancellation flag observed by background fibers (model refresh,
    * MCP reaper, etc.). Set by [[shutdown]]. */
  private val shutdownRequested: java.util.concurrent.atomic.AtomicBoolean =
    new java.util.concurrent.atomic.AtomicBoolean(false)

  /** Test hook for background fibers — `true` once [[shutdown]] has
    * been called. Apps don't usually consult this directly. */
  def isShutdown: Boolean = shutdownRequested.get()
}