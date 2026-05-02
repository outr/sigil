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
import sigil.conversation.{ActiveSkillSlot, ContextKey, ContextMemory, ContextSummary, Conversation, ConversationView, FrameBuilder, MemoryStatus, ParticipantProjection, SkillSource, Topic, TopicEntry, TopicShiftResult, TurnInput, UpsertMemoryResult}
import sigil.SpaceId
import sigil.cache.ModelRegistry
import sigil.controller.OpenRouter
import sigil.embedding.{EmbeddingProvider, NoOpEmbeddingProvider}
import sigil.transport.SignalTransport

import java.nio.file.Path
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import sigil.tool.consult.{ConsultTool, TopicClassifierTool}
import sigil.provider.GenerationSettings
import sigil.db.{DefaultSigilDB, Model, SigilDB}
import sigil.dispatcher.{StopFlag, TriggerFilter}
import sigil.event.{AgentState, Event, Message, MessageVisibility, ModeChange, Stop, ToolInvoke, TopicChange, TopicChangeKind}
import sigil.role.Role
import sigil.orchestrator.Orchestrator
import sigil.provider.{ConversationMode, ConversationRequest, Mode, ProviderStrategy, ToolPolicy}
import sigil.information.Information
import sigil.participant.{AgentParticipant, AgentParticipantId, DefaultAgentParticipant, Participant, ParticipantId}
import sigil.pipeline.{ContentExternalizationTransform, GeocodingEnrichmentEffect, InboundTransform, LocationCaptureTransform, MessageIndexingEffect, RedactLocationTransform, SettledEffect, SignalHub, ViewerTransform}
import sigil.provider.Provider
import sigil.signal.{AgentActivity, AgentStateDelta, CoreSignals, Delta, EventState, LocationDelta, Notice, Signal, ToolDelta}
import sigil.spatial.{Geocoder, NoOpGeocoder, Place}
import sigil.tool.Tool
import sigil.tool.core.{CoreTools, FindCapabilityTool}
import sigil.tool.model.ResponseContent
import sigil.tool.{ToolFinder, ToolInput}
import sigil.vector.{NoOpVectorIndex, VectorIndex, VectorPoint, VectorPointId, VectorSearchResult}

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

trait Sigil {

  /**
   * The concrete [[SigilDB]] type this Sigil uses. Defaults to
   * [[sigil.db.SigilDB]] (the framework's vanilla shape, satisfied by
   * [[sigil.db.DefaultSigilDB]]). Apps that pull in extension modules
   * (e.g. `sigil-secrets`, `sigil-mcp`) refine this to a class that
   * mixes in the modules' collection traits:
   *
   * {{{
   *   class MyAppDB(...) extends SigilDB(...) with SecretsCollections
   *   class MyAppSigil extends SecretsSigil {
   *     type DB = MyAppDB
   *     override protected def buildDB(d, sm, u) = new MyAppDB(d, sm, u)
   *   }
   * }}}
   *
   * Apps using only the framework's standard collections leave both
   * the type and `buildDB` defaulted — zero boilerplate for the
   * vanilla shape.
   *
   * `withDB` returns the refined type, so module helpers and tools
   * see `db.secrets`, `db.mcpServers`, etc. without casts.
   */
  type DB <: sigil.db.SigilDB

  /**
   * Construct the concrete [[DB]]. Defaults to
   * [[sigil.db.DefaultSigilDB]] (vanilla collections only) cast to
   * `DB` — works for apps whose `type DB = SigilDB` (or
   * `DefaultSigilDB`). Apps using extension modules
   * (`SecretsCollections`, `McpCollections`, …) override and supply
   * a subclass that mixes in the modules' collection traits.
   *
   * Vanilla shape: `type DB = SigilDB` (one line) and let the default
   * `buildDB` do the construction.
   */
  protected def buildDB(directory: Option[java.nio.file.Path],
                        storeManager: lightdb.store.CollectionManager,
                        appUpgrades: List[lightdb.upgrade.DatabaseUpgrade]): DB =
    new sigil.db.DefaultSigilDB(directory, storeManager, appUpgrades).asInstanceOf[DB]

  /**
   * App-specific [[sigil.event.Event]] subtypes — durable, persisted to
   * [[sigil.db.SigilDB.events]]. The framework's [[CoreSignals.events]] are
   * registered automatically; this list extends the discriminator with
   * additional types.
   *
   * Default empty — apps add subtypes only when they ship custom Events.
   */
  protected def eventRegistrations: List[RW[? <: Event]] = Nil

  /**
   * App-specific [[sigil.signal.Delta]] subtypes — transient updates that
   * mutate an existing target Event. The framework's [[CoreSignals.deltas]]
   * are registered automatically; this list extends the discriminator.
   *
   * Default empty — apps add subtypes only when they ship custom Deltas.
   */
  protected def deltaRegistrations: List[RW[? <: Delta]] = Nil

  /**
   * App-specific [[sigil.signal.Notice]] subtypes — transient one-shot pulses
   * for client/server messaging that don't fit Event or Delta semantics. The
   * framework's [[CoreSignals.notices]] are registered automatically; this
   * list extends the discriminator.
   *
   * Default empty — apps add subtypes only when they ship custom Notices.
   */
  protected def noticeRegistrations: List[RW[? <: Notice]] = Nil

  /**
   * Catch-all hook for custom [[Signal]] subtypes that aren't Events,
   * Deltas, or Notices. Almost always you want one of the typed hooks
   * above; this exists for the rare case of a fourth Signal kind that
   * doesn't belong to any of those categories.
   *
   * Default empty.
   */
  protected def signalRegistrations: List[RW[? <: Signal]] = Nil

  /**
   * App-specific [[sigil.provider.WorkType]] subtypes. The framework
   * ships six baseline categories
   * ([[sigil.provider.ConversationWork]], [[sigil.provider.CodingWork]],
   * [[sigil.provider.AnalysisWork]], [[sigil.provider.ClassificationWork]],
   * [[sigil.provider.CreativeWork]], [[sigil.provider.SummarizationWork]])
   * registered automatically; apps add their own subtypes here so
   * [[sigil.provider.ProviderStrategy]] routes recognize them.
   *
   * Returned values — the framework prepends the baseline and folds
   * each through `RW.static(...)` for registration. Same shape as
   * [[modes]]; staying inference-friendly keeps the Spice Dart codegen
   * happy (it skips `RW.static[T](...)` calls with explicit type
   * ascriptions when traversing the polymorphic registry).
   */
  protected def workTypeRegistrations: List[sigil.provider.WorkType] = Nil

  /**
   * App-defined [[sigil.viewer.ViewerStatePayload]] subtypes — the
   * concrete UI-state shapes apps want persisted per-viewer.
   *
   * Returns a list of fabric `RW[? <: ViewerStatePayload]`. Use
   * `summon[RW[MyPayload]]` for case-class payloads — the
   * macro-derived RW carries each field's schema so the Spice Dart
   * codegen can emit a real Dart class with all fields wired up.
   * For case-object singletons, `RW.static(MySingleton)` is fine.
   *
   * Same shape as [[eventRegistrations]] / [[noticeRegistrations]] —
   * a registration shape that returns values + folds through
   * `RW.static` (the prior shape) silently drops case-class field
   * schema, because `RW.static(instance)` is a singleton-shaped RW.
   *
   * Apps that don't use [[sigil.signal.RequestViewerState]] /
   * [[sigil.signal.UpdateViewerState]] leave the default `Nil` —
   * the primitive is opt-in. The framework ships no concrete
   * subtype; "what state to persist" is a 100% app decision.
   */
  protected def viewerStatePayloadRegistrations: List[RW[? <: sigil.viewer.ViewerStatePayload]] = Nil

  /** Every Event RW the framework knows about — `CoreSignals.events ++ eventRegistrations`. */
  final def allEventRWs: List[RW[? <: Event]] = CoreSignals.events ++ eventRegistrations

  /** Every Delta RW the framework knows about — `CoreSignals.deltas ++ deltaRegistrations`. */
  final def allDeltaRWs: List[RW[? <: Delta]] = CoreSignals.deltas ++ deltaRegistrations

  /** Every Notice RW the framework knows about — `CoreSignals.notices ++ noticeRegistrations`. */
  final def allNoticeRWs: List[RW[? <: Notice]] = CoreSignals.notices ++ noticeRegistrations

  /**
   * Simple-class-name set of every registered Event subtype — what wire
   * routers / Dart codegen / spice's `durableSubtypes` knob need to
   * distinguish "persist + replay this subtype" from "transient pulse".
   *
   * Names match the wire discriminator that fabric writes for each subtype
   * (`Product.productPrefix` — i.e. the simple class name). Apps that add
   * custom Events via `eventRegistrations` see them surface here
   * automatically.
   */
  final def eventSubtypeNames: Set[String] =
    allEventRWs.flatMap(_.definition.className).map(simpleClassName).toSet

  /** Simple-class-name set of every registered Delta subtype. */
  final def deltaSubtypeNames: Set[String] =
    allDeltaRWs.flatMap(_.definition.className).map(simpleClassName).toSet

  /** Simple-class-name set of every registered Notice subtype. */
  final def noticeSubtypeNames: Set[String] =
    allNoticeRWs.flatMap(_.definition.className).map(simpleClassName).toSet

  private def simpleClassName(fullName: String): String = {
    val lastDot = fullName.lastIndexOf('.')
    val lastDollar = fullName.lastIndexOf('$')
    val start = math.max(lastDot, lastDollar) + 1
    fullName.substring(start)
  }

  /**
   * App-specific ParticipantId subtypes. Apps register their own
   * `ParticipantId` implementations here for polymorphic serialization.
   *
   * Default empty — apps that define participants override.
   */
  protected def participantIds: List[RW[? <: ParticipantId]] = Nil

  /**
   * When `true`, tools that would normally cause external side effects (send
   * a message, write to a shared resource, charge a card) should return a
   * representative test response instead. The `TurnContext` passed to
   * `Tool.execute` forwards this flag through `context.sigil.testMode` so
   * tools can check it directly.
   *
   * Default `false` — production. Tests override to `true`.
   */
  def testMode: Boolean = false

  // -- tool catalog --

  /**
   * Static tool singletons synced into [[sigil.db.SigilDB.tools]] on
   * every startup by [[sigil.tool.StaticToolSyncUpgrade]] and registered
   * into the polymorphic `Tool` RW via `RW.static`.
   *
   * Defaults to [[sigil.tool.core.CoreTools.all]] so the framework
   * essentials (`respond`, `no_response`, `stop`, `find_capability`)
   * are always resolvable by name. Apps with multiple
   * [[sigil.provider.Mode]]s add `ChangeModeTool` themselves; it's
   * shipped in core but not auto-registered, since single-mode apps
   * don't need it. Apps add their
   * own static tools by overriding and concatenating:
   * {{{
   *   override def staticTools: List[Tool] = super.staticTools ++ List(MyTool, OtherTool)
   * }}}
   */
  def staticTools: List[sigil.tool.Tool] = sigil.tool.core.CoreTools.all.toList

  /**
   * App-provided `Tool` subtypes that support runtime instance
   * creation (e.g. `ScriptTool`, `WorkflowTool` — case classes whose
   * instances are persisted via `createTool`). Each entry is the RW of
   * a `Tool` subclass so the polymorphic RW can round-trip records.
   */
  def toolRegistrations: List[RW[? <: sigil.tool.Tool]] = Nil

  /**
   * App-provided [[sigil.tool.ToolInput]] RWs. Registered into the
   * `ToolInput` poly at init so providers can deserialize tool-call
   * arguments. `staticTools`' input RWs are auto-derived; this list
   * adds inputs for runtime-created tools (e.g. `ScriptInput` even if
   * no static `ScriptTool` is registered).
   */
  def toolInputRegistrations: List[RW[? <: sigil.tool.ToolInput]] = Nil

  /**
   * Capability-discovery finder. Default queries [[sigil.db.SigilDB.tools]]
   * via [[sigil.tool.DbToolFinder]] — apps override only when they
   * need a custom finder (marketplace union, in-memory test catalog,
   * etc.).
   */
  def findTools: sigil.tool.ToolFinder = defaultFindTools

  /**
   * Unified discovery across every category of capability the
   * framework surfaces (tools, modes, skills). Bug #66.
   *
   * Default composition:
   *   - Calls [[findTools]] to gather matching tools, wraps each as a
   *     `CapabilityMatch(_, _, Tool, _, Ready)`.
   *   - Calls [[findModes]] to gather matching modes (excluding the
   *     currently-active mode — switching to the mode you're already
   *     in is a no-op), wraps each with a `RequiresSetup(change_mode("…"))`
   *     hint so the agent has the actionable next call inline.
   *   - Sorts the combined list by `score` descending and returns it.
   *
   * Mode-gated tools (per `ScriptAuthoringMode`'s pattern) are
   * correctly hidden from `findTools` because their `modes` set
   * doesn't include the current mode, but the matching mode itself
   * surfaces here — giving the agent the entry point. This is the
   * fix for #66's "discover modes alongside tools" gap.
   *
   * Apps override either by providing custom finder implementations
   * or by overriding this method directly to merge additional
   * sources (marketplace catalog, MCP registry, agent roster, …).
   */
  def findCapabilities(request: sigil.tool.DiscoveryRequest): rapid.Task[List[sigil.tool.discovery.CapabilityMatch]] = {
    import sigil.tool.discovery.{CapabilityMatch, CapabilityStatus, CapabilityType}
    for {
      tools <- findTools(request)
      modes <- findModes(request)
    } yield {
      val toolMatches = tools.zipWithIndex.map { case (t, i) =>
        // Tool ranking comes from the finder; we approximate a score
        // from the order it returned (highest first, decreasing).
        CapabilityMatch(
          name = t.name.value,
          description = t.description,
          capabilityType = CapabilityType.Tool,
          score = (tools.size - i).toDouble,
          status = CapabilityStatus.Ready
        )
      }
      val modeMatches = modes.map { case (m, score) =>
        CapabilityMatch(
          name = m.name,
          description = m.description,
          capabilityType = CapabilityType.Mode,
          score = score,
          status = CapabilityStatus.RequiresSetup(s"""change_mode("${m.name}")""")
        )
      }
      (toolMatches ++ modeMatches).sortBy(-_.score)
    }
  }

  /**
   * Score-and-filter the registered modes against the
   * [[sigil.tool.DiscoveryRequest]]'s keyword query. Default: lexical
   * match against `name + description + skill.content` (case-
   * insensitive); excludes the currently-active mode (no-op switch);
   * returns each match paired with a relevance score.
   *
   * Apps override for app-specific gating (e.g. tenant-scoped modes,
   * per-chain mode policies) or smarter scoring (embedding-backed,
   * weighted by recency, etc.). The framework default is the
   * cheapest correct shape.
   */
  def findModes(request: sigil.tool.DiscoveryRequest): rapid.Task[List[(sigil.provider.Mode, Double)]] = rapid.Task {
    val needles = request.keywords.toLowerCase.split("\\s+").filter(_.nonEmpty).toList
    if (needles.isEmpty) Nil
    else availableModes.iterator
      .filter(m => m.name != request.mode.name)
      .map { m =>
        val haystack = (
          m.name + " " +
          m.description + " " +
          m.skill.map(_.content).getOrElse("")
        ).toLowerCase
        // Score: 5 per exact-word match, 2 per substring match.
        // Cheap; mirrors the spirit of `DbToolFinder`'s lexical scoring.
        val score = needles.foldLeft(0.0) { (acc, kw) =>
          val words = haystack.split("\\W+").toSet
          val exact = if (words.contains(kw)) 5.0 else 0.0
          val sub   = if (haystack.contains(kw)) 2.0 else 0.0
          acc + math.max(exact, sub)
        }
        m -> score
      }
      .filter(_._2 > 0.0)
      .toList
      .sortBy(-_._2)
  }

  /** The set of [[SpaceId]]s the caller chain is authorized to see —
    * used to filter `find_capability` results. Default empty
    * (fail-closed). Apps override with their own policy. */
  def accessibleSpaces(chain: List[ParticipantId]): Task[Set[SpaceId]] =
    Task.pure(Set.empty)

  /** Persist a user-created tool. Typical call site: an app's agent
    * flow that dynamically generates a `ScriptTool(...)` with the
    * caller's `SpaceId`, then writes it via this helper. Returns the
    * stored tool. */
  def createTool(tool: sigil.tool.Tool): Task[sigil.tool.Tool] =
    withDB(_.tools.transaction(_.upsert(tool)))

  // -- storage --

  /** Backend for binary content (screenshots, generated images,
    * uploaded files). Default: [[sigil.storage.LocalFileStorageProvider]]
    * rooted under `<sigil.storagePath ?? dbPath/storage>`. Apps
    * override for S3 / multi-backend by returning their own
    * [[sigil.storage.StorageProvider]] (typically
    * [[sigil.storage.S3StorageProvider]]).
    *
    * The framework always proxies bytes through its HTTP layer
    * ([[sigil.storage.http.StorageRouteFilter]]); the backend's
    * native URL is never exposed to consumers, regardless of which
    * provider is wired. */
  def storageProvider: sigil.storage.StorageProvider = defaultStorageProvider

  private final lazy val defaultStorageProvider: sigil.storage.StorageProvider = {
    val configured = Profig("sigil.storagePath").asOr[String]("")
    val base =
      if (configured.nonEmpty) java.nio.file.Path.of(configured)
      else java.nio.file.Path.of(Profig("sigil.dbPath").asOr[String]("db/sigil"), "storage")
    new sigil.storage.LocalFileStorageProvider(base)
  }

  /** Persist bytes under the given [[SpaceId]]. Records a
    * [[sigil.storage.StoredFile]] in `SigilDB.storedFiles` and writes
    * the bytes via [[storageProvider]]. Returns the persisted record
    * — call [[storageUrl]] to get a URL the UI can fetch.
    *
    * The provider's `path` is derived as `<space.value>/<id>` so
    * backends that support hierarchical listing keep tenant
    * directories separated. */
  def storeBytes(space: SpaceId,
                 data: Array[Byte],
                 contentType: String,
                 metadata: Map[String, String] = Map.empty): Task[sigil.storage.StoredFile] = {
    val record = sigil.storage.StoredFile(
      space = space,
      path = "",
      contentType = contentType,
      size = data.length.toLong,
      metadata = metadata
    )
    val derivedPath = s"${space.value}/${record._id.value}"
    val populated = record.copy(path = derivedPath)
    storageProvider.upload(derivedPath, data, contentType).flatMap { _ =>
      withDB(_.storedFiles.transaction(_.insert(populated))).map(_ => populated)
    }
  }

  /** Read bytes by id with authz. Returns `None` if the file doesn't
    * exist OR the caller's `accessibleSpaces` doesn't include the
    * file's space. Mirroring `find_capability`'s fail-closed
    * default — if the app hasn't authorized the chain, lookups
    * silently miss. */
  def fetchStoredFile(id: Id[sigil.storage.StoredFile],
                      chain: List[ParticipantId]): Task[Option[(sigil.storage.StoredFile, Array[Byte])]] =
    withDB(_.storedFiles.transaction(_.get(id))).flatMap {
      case None => Task.pure(None)
      case Some(file) =>
        accessibleSpaces(chain).flatMap { spaces =>
          if (!spaces.contains(file.space)) Task.pure(None)
          else storageProvider.download(file.path).map(_.map(bytes => (file, bytes)))
        }
    }

  /** Eagerly delete: remove the record from `SigilDB.storedFiles` and
    * the bytes from the backend in the same task. Authz: caller's
    * `accessibleSpaces` must include the file's space. Apps that
    * want soft-delete override [[afterDelete]]. */
  def deleteStoredFile(id: Id[sigil.storage.StoredFile],
                       chain: List[ParticipantId]): Task[Unit] =
    withDB(_.storedFiles.transaction(_.get(id))).flatMap {
      case None => Task.unit
      case Some(file) =>
        accessibleSpaces(chain).flatMap { spaces =>
          if (!spaces.contains(file.space)) Task.unit
          else for {
            _ <- storageProvider.delete(file.path)
            _ <- withDB(_.storedFiles.transaction(_.delete(id))).unit
            _ <- afterDelete(file)
          } yield ()
        }
    }

  /** Hook invoked after a [[StoredFile]] record + its bytes have been
    * deleted. Default: no-op. Apps override for soft-delete bookkeeping
    * (move to a tombstone collection) or audit logging. */
  protected def afterDelete(file: sigil.storage.StoredFile): Task[Unit] = Task.unit

  /** The URL a UI fetches a stored file from. Default returns
    * `sigil://storage/<id>` — the framework's
    * [[sigil.storage.http.StorageRouteFilter]] resolves that scheme
    * back through `storageProvider.download`. Apps that want fully
    * qualified URLs (CDN edge, signed URLs) override this hook. */
  def storageUrl(file: sigil.storage.StoredFile): spice.net.URL =
    spice.net.URL.get(s"sigil://storage/${file._id.value}",
      tldValidation = spice.net.TLDValidation.Off).getOrElse(
      throw new RuntimeException(s"Failed to construct storage URL for ${file._id.value}"))

  // -- providers (configs + strategies + assignments) --

  /**
   * Resolve a provider API key for storage, encryption, or external
   * call. Default returns `None` — apps mixing in
   * [[sigil.secrets.SecretsSigil]] override to consult the
   * `secretStore` (the secret-id is whatever
   * [[sigil.provider.ProviderConfig.apiKeySecretId]] holds).
   *
   * The framework does not store plaintext keys anywhere — apps
   * choose their own resolution path (env var, secret store, KMS).
   */
  def resolveApiKey(secretId: String): Task[Option[String]] = Task.pure(None)

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
          assigns <- withDB(_.providerAssignments.transaction(_.list))
          orphans  = assigns.toList.filter(_.strategyId == id)
          _       <- Task.sequence(orphans.map(o =>
                       withDB(_.providerAssignments.transaction(_.delete(o._id))).unit))
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

  /** Default record → strategy materializer. Override to swap in a
    * custom [[sigil.provider.ProviderStrategy]] (round-robin,
    * cost-aware routing, etc.) using the persisted record as a
    * config knob. */
  protected def materializeStrategy(record: sigil.provider.ProviderStrategyRecord): sigil.provider.ProviderStrategy = {
    // routeCandidates is keyed by `WorkType.value` strings; resolve
    // each through the registered WorkType polytype names. Unregistered
    // values fall through to a synthetic `WorkType` with that string.
    val routes: Map[sigil.provider.WorkType, List[sigil.provider.ModelCandidate]] =
      record.routeCandidates.flatMap { case (key, list) =>
        // Try framework-shipped subtypes first; fall back to a one-off
        // anonymous WorkType so dispatch can still match if the app's
        // strategy uses values the framework doesn't know about.
        val wt: sigil.provider.WorkType = key.toLowerCase match {
          case "conversation"   => sigil.provider.ConversationWork
          case "coding"         => sigil.provider.CodingWork
          case "analysis"       => sigil.provider.AnalysisWork
          case "classification" => sigil.provider.ClassificationWork
          case "creative"       => sigil.provider.CreativeWork
          case "summarization"  => sigil.provider.SummarizationWork
          case other            => new sigil.provider.WorkType { override val value: String = other }
        }
        Map(wt -> list)
      }
    sigil.provider.ProviderStrategy.routed(record.defaultCandidates, routes)
  }

  private final lazy val defaultFindTools: sigil.tool.ToolFinder = {
    val staticInputs = staticTools.map(_.inputRW).distinctBy(_.definition.className)
    val allInputs = (staticInputs ++ toolInputRegistrations).distinctBy(_.definition.className)
    sigil.tool.DbToolFinder(this, allInputs)
  }

  // -- context curation --

  /**
   * Per-turn curator: given the current [[ConversationView]] plus the
   * target model and participant chain, produce the [[TurnInput]] the
   * provider will render. Policy lives here — pick which
   * memories/summaries/information to surface, apply app-specific
   * overlays, add extra context, run budget-based compression, etc.
   *
   * `modelId` and `chain` are forwarded so implementations that use
   * [[sigil.conversation.compression.StandardContextCurator]] (or
   * anything else LLM-driven) can invoke
   * [[sigil.tool.consult.ConsultTool.invoke]] with the same provider
   * credentials and chain the turn itself runs under.
   *
   * Default: [[sigil.conversation.compression.StandardContextCurator]]
   * with all-NoOp components plus the optimizer's pair-stripping
   * (driven by [[sigil.tool.Tool.resultTtl]]) — runs the cheap
   * cleanup pass and the budget guard so a single conversation can't
   * blow the model's context window with accumulated `find_capability`
   * / `change_mode` results. Apps that want no curation override to
   * `Task.pure(TurnInput(view))` explicitly.
   */
  def curate(view: ConversationView,
             modelId: Id[Model],
             chain: List[ParticipantId]): Task[TurnInput] =
    sigil.conversation.compression.StandardContextCurator(this).curate(view, modelId, chain)

  // -- information lookup --

  /**
   * Resolve the full content of an [[Information]] catalog entry. Apps
   * that don't use the Information catalog return `Task.pure(None)`
   * explicitly.
   */
  def getInformation(id: Id[Information]): Task[Option[Information]] = Task.pure(None)

  /**
   * Persist an [[Information]] record so it can be resolved later via
   * [[getInformation]]. Apps that enable block-extraction on compression
   * (see [[sigil.conversation.compression.StandardBlockExtractor]]) MUST
   * implement this — references emitted by the extractor resolve through
   * [[getInformation]] reading whatever this writes.
   *
   * Apps that don't use the Information catalog return `Task.unit`
   * explicitly.
   */
  def putInformation(information: Information): Task[Unit] = Task.unit

  // -- memory --

  /**
   * App-specific [[SpaceId]] subtypes registered into the polymorphic
   * discriminator so [[ContextMemory.spaceId]] and [[Tool.space]]
   * values round-trip through fabric RW. The framework's
   * [[GlobalSpace]] is registered automatically; apps add their own
   * concrete spaces (ProjectSpace, UserSpace, per-conversation
   * sessions, etc.) here.
   */
  protected def spaceIds: List[RW[? <: SpaceId]] = Nil

  /**
   * App-defined [[sigil.tool.ToolKind]] subtypes. The framework
   * auto-registers [[sigil.tool.BuiltinKind]]; opt-in modules ship
   * their own (`ScriptKind` in `sigil-script`, `McpKind` in
   * `sigil-mcp`); apps that introduce custom tool families
   * (`BrowserScriptKind`, etc.) register them here so the wire shape
   * for [[sigil.signal.RequestToolList]] / [[sigil.signal.ToolListSnapshot]]
   * round-trips correctly.
   */
  protected def toolKindRegistrations: List[RW[? <: sigil.tool.ToolKind]] = Nil

  /**
   * Search memories across the given spaces. Default queries
   * [[SigilDB.memories]] by indexed `spaceId`. Apps override for relevance
   * ranking, recency weighting, embedding search, caching, etc.
   *
   * Typically called from `curate` when assembling a turn's
   * `TurnInput.memories`: the curator picks which returned
   * records to include (by id) based on its policy.
   */
  // -- modes --

  /**
   * App-specific [[Mode]] case objects. Sigil registers these into the
   * polymorphic `Mode` discriminator (via `RW.static`) AND indexes them
   * by `name` for `modeByName` lookup at `change_mode` call time.
   *
   * Sigil ships [[ConversationMode]] and prepends it automatically —
   * apps only list their own modes. Example:
   * {{{
   *   override protected def modes: List[Mode] = List(CodingMode, WorkflowMode)
   * }}}
   */
  protected def modes: List[Mode] = Nil

  /** All modes available in this Sigil, in declaration order with
    * [[ConversationMode]] first, deduplicated. Public so the provider's
    * system-prompt rendering can advertise the full mode catalog (the
    * `change_mode` tool depends on the model knowing what modes exist
    * to switch to). */
  final lazy val availableModes: List[Mode] = (ConversationMode :: modes).distinct

  /** All modes available in this Sigil, keyed by stable name. Used by
    * `change_mode` to resolve a name-based tool argument into a real
    * instance. */
  private final lazy val modesByName: Map[String, Mode] =
    availableModes.map(m => m.name -> m).toMap

  /** Look up a registered [[Mode]] by its stable `name`. Returns `None`
    * for unknown names (e.g. an LLM produced a typo in its
    * `change_mode` call). */
  final def modeByName(name: String): Option[Mode] = modesByName.get(name)

  /**
   * Compose the effective tool name list for an agent's turn, given
   * the active [[sigil.role.Role]]'s policy, the current
   * [[Mode]]'s policy, and the participant's one-turn suggested tools
   * from `find_capability`.
   *
   * Role and Mode each contribute a [[ToolPolicy]]; the two are
   * folded in order (behavior first, then mode) over an internal
   * state. Framework essentials (`respond`, `respond_*`,
   * `no_response`, `stop`) are included by default. `find_capability`
   * is included unless either contributor is [[ToolPolicy.None]].
   * [[ToolPolicy.PureDiscovery]] strips the respond family +
   * `no_response` — `stop` plus `find_capability` plus the agent's
   * baseline remain. `change_mode` is NOT auto-included — apps with
   * multiple `Mode`s register `ChangeModeTool` via their own
   * `staticTools` and add it to the agent's `toolNames`. The
   * agent's own `toolNames` baseline is included unless either
   * contributor is `None` or `Exclusive` (both strip baseline).
   * `Active` / `Exclusive` extras are unioned across both
   * contributors; `Discoverable` / `Scoped` don't change the roster.
   *
   * Apps override for exotic composition (e.g. per-agent tool gating).
   */
  def effectiveToolNames(agent: AgentParticipant,
                         mode: Mode,
                         suggested: List[sigil.tool.ToolName]): List[sigil.tool.ToolName] = {
    import sigil.tool.core.{
      ChangeModeTool, FindCapabilityTool, NoResponseTool, RespondTool,
      RespondFailureTool, RespondFieldTool, RespondOptionsTool, StopTool
    }
    val fullEssentials = List(
      RespondTool, RespondOptionsTool, RespondFieldTool, RespondFailureTool,
      NoResponseTool, StopTool
    ).map(_.schema.name)
    val pureDiscoveryEssentials = List(StopTool.schema.name)

    case class PolicyState(extras: List[sigil.tool.ToolName],
                           includesFindCapability: Boolean,
                           includesBaseline: Boolean,
                           pureDiscovery: Boolean)
    val initial = PolicyState(Nil, includesFindCapability = true, includesBaseline = true, pureDiscovery = false)

    def apply(s: PolicyState, p: ToolPolicy): PolicyState = p match {
      case ToolPolicy.Standard         => s
      case ToolPolicy.None             => s.copy(includesFindCapability = false, includesBaseline = false)
      case ToolPolicy.PureDiscovery    => s.copy(pureDiscovery = true)
      case ToolPolicy.Active(names)    => s.copy(extras = s.extras ++ names)
      case ToolPolicy.Discoverable(_)  => s
      case ToolPolicy.Exclusive(names) => s.copy(includesBaseline = false, extras = s.extras ++ names)
      case ToolPolicy.Scoped(_)        => s
    }

    val state = apply(apply(initial, agent.tools), mode.tools)
    val essentials     = if (state.pureDiscovery) pureDiscoveryEssentials else fullEssentials
    val findCapability = if (state.includesFindCapability) List(FindCapabilityTool.schema.name) else Nil
    val baseline       = if (state.includesBaseline) agent.toolNames else Nil
    val merged         = (essentials ++ findCapability ++ baseline ++ state.extras ++ suggested).distinct
    val deduped =
      if (state.pureDiscovery) {
        val stripped = Set(
          RespondTool, RespondOptionsTool, RespondFieldTool, RespondFailureTool, NoResponseTool
        ).map(_.schema.name)
        merged.filterNot(stripped.contains)
      } else merged
    // Tool position bias is real for smaller models — they tend to pick the
    // first appropriate-looking tool. Put discovery + action tools first so
    // a "do X" request can land on `find_capability` / `change_mode` instead
    // of being captured by the always-applicable `respond` family. Response
    // tools render last so they're available for chat without dominating
    // when an action tool is the right call.
    val priority: Map[sigil.tool.ToolName, Int] = Map(
      ChangeModeTool.schema.name        -> 0,
      FindCapabilityTool.schema.name    -> 1,
      StopTool.schema.name              -> 100,
      RespondTool.schema.name           -> 101,
      RespondOptionsTool.schema.name    -> 102,
      RespondFieldTool.schema.name      -> 103,
      RespondFailureTool.schema.name    -> 104,
      NoResponseTool.schema.name        -> 105
    ).withDefaultValue(50)
    deduped.zipWithIndex.sortBy { case (name, idx) => (priority(name), idx) }.map(_._1)
  }

  /**
   * Per-turn dispatch hook. Invoked once per turn by
   * [[sigil.participant.AgentParticipant.process]] (which is final).
   * The supplied `context` already carries the agent's roles' merged
   * projection — apps that override only need to specialize on
   * `participant.id` (or pattern-match on the agent's role list) for
   * custom turn shapes.
   *
   * Default: delegates to [[defaultProcess]] which runs the standard
   * one-round-trip LLM cycle with the agent's [[ToolPolicy]] folded
   * with the current Mode's policy into the effective roster.
   */
  def process(participant: Participant,
              context: TurnContext,
              triggers: Stream[Event]): Stream[Signal] =
    defaultProcess(participant, context, triggers)

  /**
   * Standard one-round-trip LLM cycle. The agent's [[ToolPolicy]] is
   * folded with the current Mode's policy via [[effectiveToolNames]];
   * the agent's roles flow through the existing `aggregatedSkills` →
   * `renderSystem` pipeline as a single merged context.
   *
   * Steps:
   *   1. Resolve the live [[Provider]] via `providerFor(modelId, chain)`.
   *   2. Resolve each name in the effective tool roster to a live
   *      [[Tool]] via `findTools.byName`. Names that don't resolve
   *      are dropped.
   *   3. Build a [[ConversationRequest]] and run it; translate the
   *      provider's stream into [[Signal]]s via
   *      [[Orchestrator.process]].
   *   4. The first time the orchestrator emits a [[Message]],
   *      prepend an [[AgentStateDelta]] transitioning
   *      `activity = Typing` (targeting `context.currentAgentStateId`).
   *
   * Non-AgentParticipant participants emit nothing — the standard
   * path is LLM-driven. Apps that need different behavior for custom
   * Participant subtypes override [[process]].
   */
  protected def defaultProcess(participant: Participant,
                               context: TurnContext,
                               triggers: Stream[Event]): Stream[Signal] = participant match {
    case agent: AgentParticipant => runAgentTurn(agent, context)
    case _                       => Stream.empty
  }

  private def runAgentTurn(agent: AgentParticipant,
                           context: TurnContext): Stream[Signal] = {
    val effectiveChain = context.chain.filterNot(_ == agent.id) :+ agent.id
    val suggested      = context.conversationView.projectionFor(agent.id).suggestedTools
    val effectiveNames = effectiveToolNames(agent, context.conversation.currentMode, suggested).distinct

    // Strategy resolution: Mode override beats space-level
    // assignment beats agent's pinned modelId. The strategy returns
    // ordered candidates for the agent's `workType`; the first
    // available candidate's `modelId` is what this turn calls. Apps
    // wanting cooldown-aware fallback across multiple turns can
    // override `runAgentTurn` (or override `resolveProviderStrategy`
    // to return a custom strategy that itself encapsulates retry).
    val strategyTask: Task[Option[ProviderStrategy]] =
      context.conversation.currentMode.strategyId match {
        case Some(modeStrategyId) =>
          withDB(_.providerStrategies.transaction(_.get(modeStrategyId)))
            .map(_.map(materializeStrategy))
        case None =>
          resolveProviderStrategy(context.conversation.space)
      }

    val resolved: Task[(Provider, Vector[Tool], Id[Model], GenerationSettings, List[sigil.role.Role])] =
      for {
        strategyOpt <- strategyTask
        // Pick the first available candidate for the agent's work
        // type. Empty candidate list (e.g. record had no defaults)
        // falls through to `agent.modelId`.
        chosen       = strategyOpt
                         .flatMap(_.availableCandidates(agent.workType).headOption)
        modelId      = chosen.map(_.modelId).getOrElse(agent.modelId)
        // Per-candidate `settings` overlays the agent's
        // generationSettings. The framework keeps the agent's settings
        // as the base — the candidate's settings take precedence on
        // any field they specify (currently a wholesale replace; if
        // we want field-by-field merge later that lives here).
        genSettings  = chosen.map(_.settings).getOrElse(agent.generationSettings)
        p           <- providerFor(modelId, effectiveChain)
        t           <- Task.sequence(effectiveNames.map(n => findTools.byName(n))).map(_.flatten.toVector)
        // Resolve the agent's roles for this turn. Static agents return
        // their declared `roles` field; DB-backed agents (e.g. Voidcraft
        // personas) consult persistence here. Empty result is treated as
        // a programmer error.
        rolesResolved <- agent.resolveRoles(context).map { rs =>
          require(rs.nonEmpty,
            s"AgentParticipant.resolveRoles must return a non-empty list (id=${agent.id.value})")
          rs
        }
      } yield (p, t, modelId, genSettings, rolesResolved)

    Stream.force(resolved.map { case (provider, tools, modelId, genSettings, rolesResolved) =>
      val request = ConversationRequest(
        conversationId = context.conversation.id,
        modelId = modelId,
        instructions = agent.instructions,
        turnInput = context.turnInput,
        currentMode = context.conversation.currentMode,
        currentTopic = context.conversation.currentTopic,
        previousTopics = context.conversation.previousTopics,
        generationSettings = genSettings,
        tools = tools,
        builtInTools = agent.builtInTools ++ context.conversation.currentMode.builtInTools,
        chain = effectiveChain,
        roles = rolesResolved
      )

      val typingEmitted = new java.util.concurrent.atomic.AtomicBoolean(false)
      Orchestrator.process(this, provider, request, context.conversation).flatMap { sig =>
        val prefix: List[Signal] = sig match {
          case _: Message if typingEmitted.compareAndSet(false, true) =>
            context.currentAgentStateId.toList.map { agentStateId =>
              AgentStateDelta(
                target = agentStateId,
                conversationId = context.conversation.id,
                activity = Some(AgentActivity.Typing)
              )
            }
          case _ => Nil
        }
        Stream.emits(prefix :+ sig)
      }
    })
  }


  /**
   * The [[SpaceId]] into which a
   * [[sigil.conversation.compression.MemoryContextCompressor]] should
   * write facts extracted during compression of this conversation.
   *
   * Apps that don't want memory extraction return
   * `Task.pure(None)` — the compressor collapses to summary-only.
   * Apps that do want it return a concrete space (per-conversation,
   * per-user, or a global compression-facts space).
   */
  def compressionMemorySpace(conversationId: Id[Conversation]): Task[Option[SpaceId]] = Task.pure(None)

  /**
   * Hook point for per-turn memory extraction. Invoked by the
   * [[sigil.orchestrator.Orchestrator]] after each agent turn's
   * `Done` event on a background fiber — failures are logged but do
   * not affect the response stream.
   *
   * Default:
   * [[sigil.conversation.compression.extract.StandardMemoryExtractor]]
   * wired to [[compressionMemorySpace]] for the target write-space.
   * The framework's default `compressionMemorySpace` returns `None`,
   * which makes `StandardMemoryExtractor` a no-op — apps opt in to
   * per-turn extraction by overriding `compressionMemorySpace` to
   * return a concrete [[SpaceId]]. Apps that want a different
   * extractor entirely (or no extraction even when the space is
   * set) override this.
   */
  def memoryExtractor: sigil.conversation.compression.extract.MemoryExtractor =
    sigil.conversation.compression.extract.StandardMemoryExtractor(
      spaceIdFor = compressionMemorySpace
    )

  /**
   * The default [[SpaceId]] for agent-written memories (e.g.
   * `RememberTool` invocations) when the agent doesn't supply one
   * explicitly. Apps that want per-user / per-conversation /
   * per-project scoping return the appropriate concrete subtype; apps
   * that haven't wired memory yet return `Task.pure(None)` (the
   * memory tools fail with a helpful error in that case).
   */
  def defaultMemorySpace(conversationId: Id[Conversation]): Task[Option[SpaceId]] =
    Task.pure(None)

  /**
   * The default [[SpaceId]] set used by recall-style searches
   * (e.g. `RecallTool`) when the agent doesn't supply a filter. Apps
   * typically return the caller's user/space combination.
   */
  def defaultRecallSpaces(conversationId: Id[Conversation]): Task[Set[SpaceId]] =
    defaultMemorySpace(conversationId).map(_.toSet)

  def findMemories(spaces: Set[SpaceId]): Task[List[ContextMemory]] =
    if (spaces.isEmpty) Task.pure(Nil)
    else withDB(_.memories.transaction { tx =>
      import lightdb.filter.*
      tx.query
        .filter(m => spaces.map(s => m.spaceIdValue === s.value).reduce(_ || _))
        .toList
    })

  // -- geospatial capture & enrichment --

  /**
   * Opt-in capture hook consulted by the default
   * [[LocationCaptureTransform]]. Returns the `Place` to attach to a
   * non-agent-authored [[Message]] whose `location` is empty; returns
   * `None` to skip. Default no-op.
   *
   * Apps that want geotagging override and consult their own
   * per-participant opt-in registry + device-location source. Apps
   * that populate `Message.location` explicitly at the client bypass
   * this path — the transform leaves present Places untouched.
   */
  def locationFor(participantId: ParticipantId,
                  conversationId: Id[Conversation]): Task[Option[Place]] =
    Task.pure(None)

  /**
   * Resolve a `Place` from a participant chain — the location
   * relevant to a memory that was authored mid-turn. Walks `chain`
   * looking for the first non-`AgentParticipantId`, then consults
   * [[locationFor]] on it.
   *
   * Rationale: an agent often authors a memory (`save_memory` etc.),
   * but agents have no physical location. The user whose request
   * triggered the chain is the one whose location should be recorded.
   * `chain.head` is the originating participant — typically that
   * user — so the default walk picks them naturally; if the chain
   * has only agent participants (cron-like flows), the result is
   * `None`.
   *
   * Apps with custom chain shapes (multiple humans, nested
   * delegation) override.
   */
  def locationForChain(chain: List[sigil.participant.ParticipantId],
                       conversationId: Id[Conversation]): Task[Option[Place]] = {
    val user = chain.find {
      case _: sigil.participant.AgentParticipantId => false
      case _                                       => true
    }
    user match {
      case Some(p) => locationFor(p, conversationId)
      case None    => Task.pure(None)
    }
  }

  // -- inbound pipeline --

  /**
   * Pre-persist transforms applied in order by [[publish]] before a
   * signal hits [[SigilDB.apply]]. Defaults to
   * `[LocationCaptureTransform, ContentExternalizationTransform]`.
   * Apps override to add, remove, or reorder — see
   * `sigil.pipeline.InboundTransform`.
   *
   * `ContentExternalizationTransform` rewrites oversized
   * [[sigil.tool.model.ResponseContent]] blocks into
   * `StoredFileReference` pointers before persist (see
   * [[inlineContentThreshold]] / [[externalizationSpace]]) — keeps
   * the event store lean on long agent responses. Apps that don't
   * want it drop it from this list or set
   * `inlineContentThreshold = Long.MaxValue`.
   */
  def inboundTransforms: List[InboundTransform] =
    List(LocationCaptureTransform, ContentExternalizationTransform)

  /**
   * Bytes — content blocks larger than this get pushed to the
   * configured [[storageProvider]] and replaced with a
   * [[sigil.tool.model.ResponseContent.StoredFileReference]] before
   * the Message persists. Default 8 KB. Set to `Long.MaxValue` to
   * disable externalization entirely.
   */
  def inlineContentThreshold: Long = 8L * 1024L

  /**
   * Resolve the [[SpaceId]] under which an externalized content
   * block lands. Default [[GlobalSpace]] — apps that scope storage
   * per-conversation / per-tenant override (e.g.
   * `Task.pure(MyConversationSpace(message.conversationId.value))`).
   *
   * The resolver receives the source [[sigil.event.Message]] so
   * apps can derive scope from `participantId` (per-user),
   * `conversationId` (per-conversation), or message metadata. */
  def externalizationSpace(message: sigil.event.Message): Task[SpaceId] =
    Task.pure(GlobalSpace)

  /**
   * Post-persist side effects triggered by every signal that reaches
   * [[publish]]. Defaults to `[MessageIndexingEffect,
   * GeocodingEnrichmentEffect]` — vector indexing of settled
   * Messages and fire-and-forget geocoding of bare GPS points. Apps
   * override to add, remove, or reorder. Each effect returns
   * `Task[Unit]`; the framework awaits each in declaration order.
   */
  def settledEffects: List[SettledEffect] =
    List(MessageIndexingEffect, GeocodingEnrichmentEffect)

  /**
   * Reverse-geocoding service used to enrich user-authored Messages
   * whose `location` carries only a raw point. When `geocoder` is
   * [[NoOpGeocoder]] (the default), enrichment is skipped entirely —
   * no cache lookup, no background task, no log. This is a
   * first-class configuration: apps wanting GPS tagging without
   * Place lookups keep the default.
   *
   * Apps that want enrichment typically wire
   * [[sigil.spatial.CachingGeocoder]] around a concrete geocoder
   * (Google Places or similar) so repeated GPS samples in the same
   * physical boundary hit the cache instead of the external API.
   */
  def geocoder: Geocoder = NoOpGeocoder

  // -- outbound / per-viewer pipeline --

  /**
   * Per-subscriber transforms applied by
   * [[applyViewerTransforms]] (and by the per-viewer stream helper
   * `signalsFor`) to every signal heading to a specific viewer.
   * Defaults to `[RedactLocationTransform]` — sender-private
   * `Message.location` is stripped for non-senders. Apps override to
   * add/remove/reorder — see `sigil.pipeline.ViewerTransform`.
   */
  def viewerTransforms: List[ViewerTransform] = List(RedactLocationTransform)

  /**
   * Fold a signal through [[viewerTransforms]] in declaration order,
   * returning the version a specific viewer should see. Apps that
   * consume [[signals]] directly and fan out to subscribers call this
   * per (signal, viewer) pair to apply redaction/filtering.
   */
  final def applyViewerTransforms(signal: Signal, viewer: ParticipantId): Signal =
    viewerTransforms.foldLeft(signal)((s, t) => t.apply(s, viewer, this))

  /**
   * Hard scope predicate — does `viewer` get to see `signal` at all?
   * Default consults [[Event.visibility]] when the signal is an
   * [[Event]] (Deltas always pass through; client logic must ignore
   * deltas whose target event was filtered). Apps override for
   * custom scope rules (per-tenant, per-permission-grant, etc.).
   *
   * Resolution policy for the default `MessageVisibility` cases:
   *   - `All` — pass.
   *   - `Agents` — pass iff `viewer.isInstanceOf[AgentParticipantId]`.
   *   - `Users` — pass iff viewer is NOT an `AgentParticipantId`.
   *   - `Participants(ids)` — pass iff `ids.contains(viewer)`.
   *
   * Called twice per signal: once in `signalsFor(viewer)` for wire
   * delivery, once on each [[sigil.conversation.ContextFrame]]'s
   * source visibility in `buildContext` for prompt-building.
   */
  def canSee(signal: Signal, viewer: ParticipantId): Boolean = signal match {
    case e: Event => visibilityAllows(e.visibility, viewer)
    case _        => true
  }

  /**
   * Same predicate, applied directly to a denormalized
   * [[MessageVisibility]] (e.g. on a [[sigil.conversation.ContextFrame]]).
   * Apps that override [[canSee]] should usually override this too if
   * their custom logic depends on the signal payload — the default
   * delegates straight to the visibility tag.
   */
  def visibilityAllows(visibility: MessageVisibility, viewer: ParticipantId): Boolean =
    visibility match {
      case MessageVisibility.All               => true
      case MessageVisibility.Agents            => viewer.isInstanceOf[AgentParticipantId]
      case MessageVisibility.Users             => !viewer.isInstanceOf[AgentParticipantId]
      case MessageVisibility.Participants(ids) => ids.contains(viewer)
    }

  // -- broadcast stream --

  /** Multicast dispatcher populated by [[publish]]. One per Sigil
    * instance; initialized lazily so initialization order is safe. */
  private final lazy val hub: SignalHub = new SignalHub()

  /**
   * Broadcast-level stream of every signal that has completed its
   * publish pipeline (transforms applied, persisted, projections
   * updated, settled effects fired). Each call returns a new
   * subscriber — slow subscribers drop oldest on overflow and don't
   * block peers.
   *
   * Signals are emitted unchanged — viewer-dependent transforms are
   * NOT applied. Subscribers that need per-viewer redaction should
   * consume [[signalsFor]] instead.
   *
   * The hub is not a replay log; late subscribers see only signals
   * emitted after they subscribe. Durable history lives in the events
   * store.
   */
  final def signals: Stream[Signal] = hub.subscribe

  /** Per-viewer broadcast subscription that ALSO receives signals
    * emitted via [[publishTo]] targeted at this viewer. Used internally
    * by [[signalsFor]] and by transports that want full per-viewer
    * delivery (broadcasts + targeted Notices). */
  private[sigil] final def signalsViewerScoped(viewer: ParticipantId): Stream[Signal] =
    hub.subscribeFor(viewer)

  /**
   * Per-viewer stream derived from [[signals]]. Each signal is first
   * tested against [[canSee]] (drops `MessageVisibility.Agents`
   * messages for non-agent viewers, etc.); survivors are folded
   * through [[viewerTransforms]] for redaction. Wire transports
   * subscribe to one of these per connected client — the returned
   * stream is already filtered and redacted, so the app does not
   * have to call `canSee` / `applyViewerTransforms` itself.
   *
   * Deltas always pass `canSee`; client UIs must ignore deltas
   * whose target event was filtered out by visibility.
   */
  final def signalsFor(viewer: ParticipantId): Stream[Signal] =
    signalsViewerScoped(viewer).flatMap { s =>
      if (canSee(s, viewer)) Stream.emit(applyViewerTransforms(s, viewer))
      else Stream.empty
    }

  // -- embeddings & vector search --

  /**
   * The [[EmbeddingProvider]] used to vectorize persisted text for
   * semantic retrieval. Apps that don't use embeddings return
   * [[NoOpEmbeddingProvider]] — Sigil skips auto-indexing and semantic
   * search falls back to the Lucene path. Apps that do use
   * embeddings wire a concrete provider (e.g.
   * [[sigil.embedding.OpenAICompatibleEmbeddingProvider]]) and must
   * pair it with a non-NoOp [[vectorIndex]].
   */
  def embeddingProvider: EmbeddingProvider = NoOpEmbeddingProvider

  /**
   * Backing vector store for semantic search. Apps that don't use
   * vector search return [[NoOpVectorIndex]] (upserts dropped, searches
   * empty). Apps that do typically wire
   * [[sigil.vector.QdrantVectorIndex]] in production or
   * [[sigil.vector.InMemoryVectorIndex]] in tests.
   */
  def vectorIndex: VectorIndex = NoOpVectorIndex

  /** `true` when both [[embeddingProvider]] and [[vectorIndex]] are
    * non-NoOp — the flag the framework checks before auto-embedding on
    * persist or attempting vector-backed search. */
  protected final def vectorWired: Boolean =
    embeddingProvider.dimensions > 0 && (vectorIndex ne NoOpVectorIndex)

  /**
   * Pluggable text-to-speech / speech-to-text / image-generation
   * provider. Default [[sigil.media.NoOpMediaProvider]] raises
   * `UnsupportedMediaOperation` from every method; apps that need
   * media wire a concrete implementation (e.g. Sage's
   * `sage.media.ElevenLabsTts` for voice, `sage.media.OpenAIImageGen`
   * for image gen). Voidcraft's `SpeechService` and `ImageGenService`
   * become thin call-throughs to whatever this provides.
   */
  def mediaProvider: sigil.media.MediaProvider = sigil.media.NoOpMediaProvider

  // -- broadcasting --


  /**
   * An [[spice.http.client.intercept.Interceptor]] chained into every
   * provider's HTTP client — captures request / response pairs for
   * diagnostics. The built-in
   * [[sigil.provider.debug.JsonLinesInterceptor]] writes JSON lines
   * to a file so the full back-and-forth can be walked post-hoc.
   * Apps that don't want wire logging return
   * [[spice.http.client.intercept.Interceptor.empty]] explicitly.
   */
  def wireInterceptor: spice.http.client.intercept.Interceptor = spice.http.client.intercept.Interceptor.empty

  // -- participants (registration for polymorphic RW) --

  /**
   * App-specific [[Participant]] subtypes registered into the polymorphic
   * discriminator so [[sigil.conversation.Conversation.participants]] can
   * round-trip them through fabric RW. Framework subtypes
   * ([[DefaultAgentParticipant]]) are registered automatically; this list
   * extends the poly with app-specific agent types (Planner, Critic, etc.).
   */
  protected def participants: List[RW[? <: Participant]] = Nil

  // -- provider resolution --

  /**
   * Resolve a live [[Provider]] for the given model, scoped to the
   * participant chain. The chain lets the app pick credentials
   * (API keys, OAuth tokens, billing accounts) tied to the originating
   * participant — typically `chain.head` is the user whose key pays for
   * the call.
   *
   * Called by [[AgentParticipant.defaultProcess]] at every turn; no
   * caching in the framework. Apps cache if they care.
   */
  def providerFor(modelId: Id[Model], chain: List[ParticipantId]): Task[Provider]

  // -- framework dispatch (entry point) --

  /**
   * Inject a [[Signal]] into the framework. The single pipeline every
   * signal passes through, on the way in from outside or back out from
   * an agent's own turn. In order:
   *
   *   1. Apply [[inboundTransforms]] (e.g. `LocationCaptureTransform`).
   *   2. Persist via `SigilDB.apply` (insert Event / apply Delta).
   *   3. Update materialized projections on [[Conversation]]
   *      (`currentMode`, `currentTopicId`) for Mode/Topic changes.
   *   4. Append a frame to the conversation's [[ConversationView]] when
   *      an event settles Complete (via `FrameBuilder`).
   *   5. Resolve and apply the Mode-source skill slot on `ModeChange`.
   *   6. Dispatch control signals — a [[Stop]] event updates the
   *      matching agent's [[sigil.dispatcher.StopFlag]] so the agent's
   *      next iteration check (or in-flight `takeWhile`) exits.
   *   7. Emit to the [[SignalHub]] for subscribers of [[signals]] /
   *      [[signalsFor]].
   *   8. Fan out to participants whose [[TriggerFilter]] matches.
   *   9. Run [[settledEffects]] (e.g. vector indexing, geocoding).
   *
   * Apps don't override this — it's the framework's pipeline.
   */
  final def publish(signal: Signal): Task[Unit] = signal match {
    case n: sigil.signal.Notice =>
      // Notices are transient pulses — no persist, no projection
      // updates, no fan-out. Inbound transforms still run (apps may
      // want to redact / annotate). Then broadcast through the hub
      // and dispatch to handleNotice.
      applyInboundTransforms(n).flatMap { resolved =>
        Task { hub.emit(resolved); () }
      }
    case _ =>
      applyInboundTransforms(signal).flatMap { resolved =>
        for {
          _ <- withDB(_.apply(resolved))
          _ <- updateConversationProjection(resolved)
          _ <- updateView(resolved)
          _ <- maybeApplyModeSkill(resolved)
          _ <- applyStop(resolved)
          _ <- Task { hub.emit(resolved); () }
          _ <- resolved match {
                 case e: Event => fanOut(e)
                 case _: sigil.signal.Delta => Task.unit
                 case _: sigil.signal.Notice => Task.unit  // unreachable here, exhaustive
               }
          _ <- applySettledEffects(resolved)
        } yield ()
      }
  }

  /**
   * Single-target broadcast — deliver `signal` only to subscribers
   * registered with [[signalsFor]] at the given viewer. Used primarily
   * for [[sigil.signal.Notice]] replies / snapshots that should reach
   * one specific connected client (e.g. a `ConversationListSnapshot`
   * answering that viewer's `RequestConversationList`).
   *
   * Like [[publish]], inbound transforms run first. The signal is NOT
   * persisted or projected — `publishTo` is for ephemeral targeted
   * delivery, not durable state changes. (For state changes, publish
   * an Event; the framework's pipeline handles persist + per-viewer
   * fan-out automatically.)
   */
  final def publishTo(viewer: ParticipantId, signal: Signal): Task[Unit] =
    applyInboundTransforms(signal).flatMap { resolved =>
      Task { hub.emitTo(viewer, resolved); () }
    }

  /**
   * Inbound-Notice dispatch hook. Called by [[sigil.transport.SessionBridge]]
   * (and any other Notice-aware ingress) for each Notice that arrives
   * from a client over the wire. Apps and modules override to handle
   * their own Notice subtypes; the default chain handles the
   * framework-level Notices (`RequestConversationList`,
   * `SwitchConversation`, …) and the secrets module's request/reply
   * Notices when loaded.
   *
   * The chain pattern: subclass implementations match their own
   * Notice subtypes and call `super.handleNotice(notice, fromViewer)`
   * for the default arm so framework-level dispatch still runs.
   */
  def handleNotice(notice: sigil.signal.Notice, fromViewer: ParticipantId): Task[Unit] =
    notice match {
      case _: sigil.signal.RequestConversationList =>
        listConversations(fromViewer).flatMap { conversations =>
          publishTo(fromViewer, sigil.signal.ConversationListSnapshot(conversations))
        }
      case sigil.signal.SwitchConversation(convId, limit) =>
        for {
          view   <- withDB(_.views.transaction(_.get(ConversationView.idFor(convId))))
          all    <- withDB { db =>
                      db.events.transaction(_.list.map(_.filter(_.conversationId == convId)))
                    }
          _      <- view match {
                      case Some(v) =>
                        // Sort ascending by timestamp, then take the trailing
                        // `limit`. `hasMore` is true when the underlying set
                        // had older events past the window.
                        val sorted = all.sortBy(_.timestamp.value)
                        val cap = math.max(0, limit)
                        val window = if (sorted.length <= cap) sorted else sorted.drop(sorted.length - cap)
                        val hasMore = sorted.length > cap
                        publishTo(fromViewer, sigil.signal.ConversationSnapshot(convId, v, window.toVector, hasMore))
                      case None =>
                        Task.unit
                    }
        } yield ()

      case sigil.signal.RequestConversationHistory(convId, beforeMs, limit) =>
        withDB { db =>
          db.events.transaction(_.list.map(_.filter(e =>
            e.conversationId == convId && e.timestamp.value < beforeMs
          )))
        }.flatMap { older =>
          val sorted = older.sortBy(_.timestamp.value)
          val cap = math.max(0, limit)
          // Take the trailing `cap` events of everything older than the
          // cursor — that's the page closest to the cursor. `hasMore` =
          // true when even older events exist past the page.
          val window = if (sorted.length <= cap) sorted else sorted.drop(sorted.length - cap)
          val hasMore = sorted.length > cap
          publishTo(fromViewer, sigil.signal.ConversationHistorySnapshot(convId, window.toVector, hasMore))
        }

      // -- viewer-state vocabulary --

      case sigil.signal.RequestViewerState(scope) =>
        val recordId = sigil.viewer.ViewerState.idFor(fromViewer, scope)
        withDB(_.viewerStates.transaction(_.get(recordId))).flatMap { existing =>
          publishTo(fromViewer, sigil.signal.ViewerStateSnapshot(scope, existing.map(_.payload)))
        }

      case sigil.signal.UpdateViewerState(scope, payload) =>
        val record = sigil.viewer.ViewerState(
          participantId = fromViewer,
          scope = scope,
          payload = payload,
          modified = lightdb.time.Timestamp(),
          _id = sigil.viewer.ViewerState.idFor(fromViewer, scope)
        )
        for {
          _ <- withDB(_.viewerStates.transaction(_.upsert(record)))
          // Broadcast to every live session for this viewer so other
          // tabs / devices converge. `publishTo` fans out via the
          // hub's per-viewer queue.
          _ <- publishTo(fromViewer, sigil.signal.ViewerStateSnapshot(scope, Some(payload)))
        } yield ()

      case sigil.signal.DeleteViewerState(scope) =>
        val recordId = sigil.viewer.ViewerState.idFor(fromViewer, scope)
        for {
          _ <- withDB(_.viewerStates.transaction(_.delete(recordId).map(_ => ())))
                  .handleError(_ => Task.unit)
          _ <- publishTo(fromViewer, sigil.signal.ViewerStateSnapshot(scope, None))
        } yield ()

      case sigil.signal.UpdateViewerStateDelta(scope, patch) =>
        val recordId = sigil.viewer.ViewerState.idFor(fromViewer, scope)
        val payloadRW = summon[fabric.rw.RW[sigil.viewer.ViewerStatePayload]]
        withDB(_.viewerStates.transaction(_.get(recordId))).flatMap { existing =>
          val mergedPayload: sigil.viewer.ViewerStatePayload = existing match {
            case None =>
              // First delta for this scope acts like a full upsert
              // — the patch IS the initial state.
              patch
            case Some(prior) =>
              // Deep-merge the patch's non-null JSON fields onto
              // the current payload's JSON via fabric's object
              // merge, then decode back through the polytype RW.
              // Stripping nulls FIRST is what makes Option-typed
              // patches express "untouched fields stay" — fabric's
              // case-class RW emits `None` as JSON `null`, and the
              // default merge would otherwise overlay those nulls
              // onto the prior. Apps that need to clear a field to
              // None pass the full state via [[UpdateViewerState]]
              // instead.
              val priorJson = payloadRW.read(prior.payload)
              val patchJson = stripNulls(payloadRW.read(patch))
              val merged    = priorJson.merge(patchJson)
              payloadRW.write(merged)
          }
          val record = sigil.viewer.ViewerState(
            participantId = fromViewer,
            scope         = scope,
            payload       = mergedPayload,
            modified      = lightdb.time.Timestamp(),
            _id           = recordId
          )
          for {
            _ <- withDB(_.viewerStates.transaction(_.upsert(record)))
            // Broadcast the delta — peers apply the same patch onto
            // their existing local state. The originating session
            // already has its merged copy; the delta is for the
            // viewer's other tabs / devices.
            _ <- publishTo(fromViewer, sigil.signal.ViewerStateDelta(scope, patch))
          } yield ()
        }

      // -- stored-file vocabulary (BUGS.md #19 part 4) --

      case sigil.signal.RequestStoredFileList(spaces) =>
        listStoredFiles(fromViewer, spaces).flatMap { summaries =>
          publishTo(fromViewer, sigil.signal.StoredFileListSnapshot(summaries))
        }

      case sigil.signal.RequestStoredFile(fileId) =>
        fetchStoredFile(fileId, List(fromViewer)).flatMap {
          case None => Task.unit
          case Some((file, bytes)) =>
            val payload = sigil.signal.StoredFileContent(
              file = sigil.signal.StoredFileSummary.fromStoredFile(file),
              base64Data = java.util.Base64.getEncoder.encodeToString(bytes)
            )
            publishTo(fromViewer, payload)
        }

      case sigil.signal.SaveStoredFile(_, contentType, base64Data, _, _) =>
        // Default: the framework can't pick a SpaceId on the agent's
        // behalf without app context, so we resolve through
        // `externalizationSpaceForViewer(fromViewer)` (defaults to
        // GlobalSpace). Apps that want per-conversation tenancy
        // override that hook OR override `handleNotice` to take the
        // conversationId on the SaveStoredFile into account.
        externalizationSpaceForViewer(fromViewer).flatMap { space =>
          val bytes = java.util.Base64.getDecoder.decode(base64Data)
          storeBytes(space, bytes, contentType).flatMap { stored =>
            publishTo(fromViewer, sigil.signal.StoredFileCreated(
              sigil.signal.StoredFileSummary.fromStoredFile(stored)
            ))
          }
        }

      // -- tool listing vocabulary (BUGS.md #38) --

      case sigil.signal.RequestToolList(spaces, kinds) =>
        listTools(fromViewer, spaces, kinds).flatMap { summaries =>
          publishTo(fromViewer, sigil.signal.ToolListSnapshot(summaries))
        }

      case _ => Task.unit
    }

  /** Resolve the [[SpaceId]] used when a viewer pushes a
    * [[sigil.signal.SaveStoredFile]] without conversation scope.
    * Default [[GlobalSpace]] — apps tune for per-user / per-tenant. */
  def externalizationSpaceForViewer(viewer: ParticipantId): Task[SpaceId] =
    Task.pure(GlobalSpace)

  /** Resolve the list of [[sigil.signal.StoredFileSummary]] visible
    * to a viewer, optionally filtered to a subset of spaces. Default
    * walks `SigilDB.storedFiles` and filters by
    * `accessibleSpaces(List(viewer))`. */
  def listStoredFiles(viewer: ParticipantId,
                      spaces: Option[Set[SpaceId]] = None): Task[List[sigil.signal.StoredFileSummary]] =
    accessibleSpaces(List(viewer)).flatMap { authorized =>
      val effective = spaces.fold(authorized)(_.intersect(authorized))
      withDB(_.storedFiles.transaction(_.list)).map(_.toList.collect {
        case file if effective.contains(file.space) =>
          sigil.signal.StoredFileSummary.fromStoredFile(file)
      })
    }

  /** Resolve the list of [[sigil.signal.ToolSummary]] visible to a
    * viewer, optionally narrowed to a subset of spaces and/or
    * [[sigil.tool.ToolKind]] values. Default walks `SigilDB.tools`,
    * filters by `accessibleSpaces(List(viewer))` (a tool's
    * `space` must be in the intersection of the viewer's authorized
    * spaces with the request's `spaces` filter), and applies the
    * `kinds` filter if supplied.
    *
    * Apps with massive tool catalogs override for indexed lookup or
    * partition-aware paging — the wire shape stays
    * [[sigil.signal.ToolListSnapshot]] either way. */
  def listTools(viewer: ParticipantId,
                spaces: Option[Set[SpaceId]] = None,
                kinds: Option[Set[sigil.tool.ToolKind]] = None): Task[List[sigil.signal.ToolSummary]] =
    accessibleSpaces(List(viewer)).flatMap { authorized =>
      val effective = spaces.fold(authorized)(_.intersect(authorized))
      val kindFilter: sigil.tool.Tool => Boolean =
        kinds.fold((_: sigil.tool.Tool) => true)(set => t => set.contains(t.kind))
      withDB(_.tools.transaction(_.list)).map(_.toList.collect {
        case tool if effective.contains(tool.space) && kindFilter(tool) =>
          sigil.signal.ToolSummary.fromTool(tool)
      })
    }

  /** Fold the signal through [[inboundTransforms]] in declaration order.
    * Each transform sees the output of the previous one. */
  private final def applyInboundTransforms(signal: Signal): Task[Signal] =
    inboundTransforms.foldLeft(Task.pure(signal)) { (acc, transform) =>
      acc.flatMap(s => transform.apply(s, this))
    }

  /** Run each [[SettledEffect]] in declaration order, awaiting each
    * before the next. Effects that want fire-and-forget semantics
    * spawn their own fiber inside the returned Task. */
  private final def applySettledEffects(signal: Signal): Task[Unit] =
    settledEffects.foldLeft(Task.unit) { (acc, effect) =>
      acc.flatMap(_ => effect.apply(signal, this))
    }


  // -- stop-flag registry --

  /** Active per-claim [[StopFlag]]s, keyed by the `AgentState._id` that
    * owns the claim. Populated when `tryFire` wins a claim and removed
    * when `releaseClaim` completes (successfully or via error). */
  private final val stopFlags: ConcurrentHashMap[Id[Event], StopFlag] = new ConcurrentHashMap()

  /** On a [[Stop]] event, set the matching flag(s): one specific agent if
    * `targetParticipantId` is set, else every agent in the conversation.
    * Also logs the stop (with `reason`, if supplied) so operators can see
    * where stops originate — otherwise `Stop.reason` would be metadata
    * that only shows up if someone trawls the event log. */
  private final def applyStop(signal: Signal): Task[Unit] = signal match {
    case s: Stop => Task {
      val target = s.targetParticipantId.map(_.value).getOrElse("*")
      val why = s.reason.map(r => s" reason=\"$r\"").getOrElse("")
      scribe.info(
        s"Stop received: conversation=${s.conversationId.value} target=$target " +
          s"force=${s.force} by=${s.participantId.value}$why"
      )
      import scala.jdk.CollectionConverters.*
      stopFlags.entrySet().iterator().asScala.foreach { entry =>
        val lockId = entry.getKey
        val flag = entry.getValue
        // Lock id encodes `agentlock:<agentId>:<convId>`; cheapest match is
        // on the id suffix for conversation + participant.
        val matchesConv = lockId.value.endsWith(s":${s.conversationId.value}")
        val matchesTarget = s.targetParticipantId match {
          case None     => true
          case Some(id) => lockId.value == s"agentlock:${id.value}:${s.conversationId.value}"
        }
        if (matchesConv && matchesTarget) {
          if (s.force) flag.force.set(true) else flag.graceful.set(true)
        }
      }
    }
    case _ => Task.unit
  }

  /** If this signal settles a [[ModeChange]] to `Complete`, resolve the
    * Mode-source [[ActiveSkillSlot]] (via [[modeSkill]]) and write it into
    * the acting participant's projection on the view. */
  private final def maybeApplyModeSkill(signal: Signal): Task[Unit] = signal match {
    case mc: ModeChange if mc.state == EventState.Complete => applyModeSkill(mc)
    case d: sigil.signal.Delta =>
      withDB(_.events.transaction(_.get(d.target.asInstanceOf[Id[Event]]))).flatMap {
        case Some(mc: ModeChange) if mc.state == EventState.Complete => applyModeSkill(mc)
        case _ => Task.unit
      }
    case _ => Task.unit
  }

  private final def applyModeSkill(mc: ModeChange): Task[Unit] =
    mc.mode.skill match {
      case Some(slot) =>
        updateProjection(mc.conversationId, mc.participantId)(
          proj => proj.copy(activeSkills = proj.activeSkills + (SkillSource.Mode -> slot))
        )
      case None =>
        // No skill on this mode — clear any stale Mode-source slot.
        updateProjection(mc.conversationId, mc.participantId)(
          proj => proj.copy(activeSkills = proj.activeSkills - SkillSource.Mode)
        )
    }

  /**
   * Maintain the per-conversation [[ConversationView]] as events/deltas
   * flow through `publish`. A frame lands in the view exactly once per
   * source event, the moment the event reaches `EventState.Complete`:
   *
   *   - Atomic Complete events (e.g. a user-typed Message, a
   *     `find_capability` ToolResults) — the Event branch appends
   *     directly.
   *   - Events that start Active and settle later via a Delta (streaming
   *     Message, in-flight ToolInvoke) — the Delta branch re-reads the
   *     target post-apply. If it's now Complete, append.
   *
   * Idempotency: `appendToViewIfNew` guards against double-appends by
   * checking whether a frame with the same `sourceEventId` already exists.
   * Deltas that don't complete their target, or events that are still
   * Active, fall through as no-ops.
   */
  private final def updateView(signal: Signal): Task[Unit] = signal match {
    case e: Event if e.state == EventState.Complete =>
      appendToViewIfNew(e)
    case d: sigil.signal.Delta =>
      withDB(_.events.transaction(_.get(d.target.asInstanceOf[Id[Event]]))).flatMap {
        case Some(target) if target.state == EventState.Complete => appendToViewIfNew(target)
        case _ => Task.unit
      }
    case _ => Task.unit
  }

  /** Append `event`'s frame(s) and participant-projection updates to the
    * conversation's view, creating the view if it doesn't yet exist.
    * Idempotent — if a frame for `event._id` already exists in the view
    * the modify returns unchanged.
    *
    * Honors `Conversation.clearedAt`: events whose timestamp is at or
    * before the most-recent clear watermark don't append to the view
    * (they live in `db.events` for audit but stay out of the
    * UI-facing projection). New events emitted after the clear flow
    * through normally — their timestamps are strictly greater than
    * the watermark by construction. */
  private final def appendToViewIfNew(event: Event): Task[Unit] =
    withDB(_.conversations.transaction(_.get(event.conversationId))).flatMap { convOpt =>
      val watermark = convOpt.flatMap(_.clearedAt).map(_.value).getOrElse(0L)
      if (event.timestamp.value <= watermark) Task.unit
      else withDB(_.views.transaction(_.modify(ConversationView.idFor(event.conversationId)) {
        case Some(view) if view.frames.exists(_.sourceEventId == event._id) =>
          Task.pure(Some(view))
        case Some(view) =>
          val nextFrames = FrameBuilder.appendFor(view.frames, event)
          val nextProjections = FrameBuilder.updateProjections(view.participantProjections, event)
          Task.pure(Some(view.copy(
            frames = nextFrames,
            participantProjections = nextProjections,
            modified = Timestamp(Nowish())
          )))
        case None =>
          val seeded = ConversationView(
            conversationId = event.conversationId,
            frames = FrameBuilder.appendFor(Vector.empty, event),
            participantProjections = FrameBuilder.updateProjections(Map.empty, event),
            _id = ConversationView.idFor(event.conversationId)
          )
          Task.pure(Some(seeded))
      })).unit
    }

  // -- view / summary helpers --

  /** Fetch the [[ConversationView]] for a conversation, returning an empty
    * seed (no frames, no projections) if one hasn't been materialized yet.
    * Empty views are NOT persisted — the view only lands on disk once a
    * Complete event exists to anchor it. */
  def viewFor(conversationId: Id[Conversation]): Task[ConversationView] =
    withDB(_.views.transaction(_.get(ConversationView.idFor(conversationId)))).map {
      case Some(view) => view
      case None => ConversationView(
        conversationId = conversationId,
        _id = ConversationView.idFor(conversationId)
      )
    }

  /** Drop the existing view (if any) and rebuild it by folding every
    * Complete event for the conversation through [[FrameBuilder]]. Useful
    * for recovery, schema migrations, and tests.
    *
    * Returns the newly-materialized view. */
  def rebuildView(conversationId: Id[Conversation]): Task[ConversationView] =
    withDB(_.conversations.transaction(_.get(conversationId))).flatMap { convOpt =>
      val clearedAt = convOpt.flatMap(_.clearedAt).map(_.value).getOrElse(0L)
      withDB(_.events.transaction(_.list)).flatMap { all =>
        val events = all
          .filter(_.conversationId == conversationId)
          // Watermark filter — events emitted at-or-before the
          // last `clearConversation` call don't appear in the
          // projection. The events themselves remain in
          // `db.events` for audit / recovery; the watermark just
          // hides them from the UI-facing view. New events
          // (timestamps strictly greater than `clearedAt`) flow
          // through unchanged.
          .filter(_.timestamp.value > clearedAt)
          .sortBy(_.timestamp.value)
          .toVector
        val complete = events.filter(_.state == EventState.Complete)
        val frames = FrameBuilder.build(complete)
        val projections = complete
          .foldLeft(Map.empty[ParticipantId, ParticipantProjection])(FrameBuilder.updateProjections)
        val rebuilt = ConversationView(
          conversationId = conversationId,
          frames = frames,
          participantProjections = projections,
          _id = ConversationView.idFor(conversationId)
        )
        for {
          _ <- withDB(_.views.transaction(_.upsert(rebuilt)))
          // Re-apply Mode-source skill for the most recent Complete ModeChange.
          withSkill <- {
            val latestMode = complete.reverseIterator.collectFirst { case mc: ModeChange => mc }
            latestMode.fold(Task.pure(rebuilt)) { mc =>
              applyModeSkill(mc).flatMap(_ => viewFor(conversationId))
            }
          }
        } yield withSkill
      }
    }

  /** Update a participant's [[ParticipantProjection]] on the conversation's
    * view. If the view doesn't exist yet, an empty one is seeded so the
    * projection has a durable home. Use this from curators, tools, or any
    * app code that needs to mutate per-participant projection state. */
  def updateProjection(conversationId: Id[Conversation], participantId: ParticipantId)
                      (f: ParticipantProjection => ParticipantProjection): Task[Unit] =
    withDB(_.views.transaction(_.modify(ConversationView.idFor(conversationId)) {
      case Some(view) =>
        Task.pure(Some(view
          .updateParticipant(participantId)(f)
          .copy(modified = Timestamp(Nowish()))))
      case None =>
        val seeded = ConversationView(
          conversationId = conversationId,
          participantProjections = Map(participantId -> f(ParticipantProjection())),
          _id = ConversationView.idFor(conversationId)
        )
        Task.pure(Some(seeded))
    })).unit

  /** Convenience: set (or replace) a skill slot for a participant. Discovery
    * and User sources are driven through here by tools that want to activate
    * a skill; Mode-source slots are maintained by the framework via
    * [[modeSkill]] on `ModeChange`. */
  def activateSkill(conversationId: Id[Conversation],
                    participantId: ParticipantId,
                    source: SkillSource,
                    slot: ActiveSkillSlot): Task[Unit] =
    updateProjection(conversationId, participantId)(
      proj => proj.copy(activeSkills = proj.activeSkills + (source -> slot))
    )

  /** Convenience: clear a skill slot for a participant (if present). */
  def clearSkill(conversationId: Id[Conversation],
                 participantId: ParticipantId,
                 source: SkillSource): Task[Unit] =
    updateProjection(conversationId, participantId)(
      proj => proj.copy(activeSkills = proj.activeSkills - source)
    )

  /** Convenience: set a single key/value on a participant's
    * `extraContext`. Same key replaces. */
  def setParticipantContext(conversationId: Id[Conversation],
                            participantId: ParticipantId,
                            key: ContextKey,
                            value: String): Task[Unit] =
    updateProjection(conversationId, participantId)(
      proj => proj.copy(extraContext = proj.extraContext + (key -> value))
    )

  /** Convenience: remove a key from a participant's `extraContext`. */
  def clearParticipantContext(conversationId: Id[Conversation],
                              participantId: ParticipantId,
                              key: ContextKey): Task[Unit] =
    updateProjection(conversationId, participantId)(
      proj => proj.copy(extraContext = proj.extraContext - key)
    )

  /** Convenience: publish a [[Stop]] event for the conversation. Lets
    * UI layers (stop button) and programmatic callers issue stops
    * without reconstructing the event by hand. For LLM-initiated stops
    * use [[sigil.tool.core.StopTool]] instead. */
  def stop(conversationId: Id[Conversation],
           requestedBy: ParticipantId,
           targetParticipantId: Option[ParticipantId] = None,
           force: Boolean = false,
           reason: Option[String] = None): Task[Unit] =
    withDB(_.conversations.transaction(_.get(conversationId))).flatMap {
      case None => Task.unit
      case Some(conv) =>
        publish(Stop(
          participantId = requestedBy,
          conversationId = conversationId,
          topicId = conv.currentTopicId,
          targetParticipantId = targetParticipantId,
          force = force,
          reason = reason
        ))
    }

  /** Persist a new [[ContextSummary]] and return the stored record. The
    * caller (curator or app-specific summarizer) owns the generation
    * policy; this helper just writes.
    *
    * When vector search is wired ([[vectorWired]]), the summary's text
    * is embedded and upserted into [[vectorIndex]] with payload
    * `kind=summary` so `searchConversationEvents` can surface it. */
  def persistSummary(summary: ContextSummary): Task[ContextSummary] =
    withDB(_.summaries.transaction(_.upsert(summary))).flatMap { stored =>
      indexSummary(stored).map(_ => stored)
    }

  /** Persist a new [[ContextMemory]] and return the stored record.
    * When vector search is wired, auto-embeds `memory.fact` and
    * upserts into [[vectorIndex]] with payload
    * `kind=memory, spaceId=…`. */
  def persistMemory(memory: ContextMemory): Task[ContextMemory] =
    withDB(_.memories.transaction(_.upsert(memory))).flatMap { stored =>
      indexMemory(stored).map(_ => stored)
    }

  /**
   * Upsert a keyed memory with versioning semantics:
   *   - If no prior memory exists at `(spaceId, key)` → insert with
   *     `validFrom = now`, return the new record.
   *   - If the prior memory's `fact` matches → refresh metadata
   *     (label, summary, tags, memoryType, modified) in place, keep
   *     same `_id`. Returns the refreshed record.
   *   - If the prior memory's `fact` differs → archive the prior
   *     (`validUntil = now`, `supersededBy = new._id`) and insert the
   *     new memory with `supersedes = prior._id`, `validFrom = now`.
   *     Returns the new record.
   *
   * Empty `key` is rejected — un-keyed memories must use
   * [[persistMemory]] (the single-shot path; no versioning).
   */
  def upsertMemoryByKey(memory: ContextMemory): Task[UpsertMemoryResult] = {
    if (memory.key.isEmpty)
      Task.error(new IllegalArgumentException("upsertMemoryByKey requires a non-empty key; use persistMemory for un-keyed inserts"))
    else withDB { db =>
      db.memories.transaction { tx =>
        import lightdb.filter.*
        tx.query
          .filter(m => (m.spaceIdValue === memory.spaceId.value) && (m.key === memory.key))
          .toList
          .flatMap { sameKey =>
            sameKey.find(_.validUntil.isEmpty) match {
              case None =>
                val fresh = memory.copy(validFrom = Some(Timestamp()), modified = Timestamp())
                tx.upsert(fresh).map(_ => UpsertMemoryResult.Stored(fresh))
              case Some(prior) if prior.fact == memory.fact =>
                val refreshed = prior.copy(
                  label = memory.label,
                  summary = memory.summary,
                  tags = memory.tags,
                  memoryType = memory.memoryType,
                  confidence = memory.confidence,
                  pinned = memory.pinned,
                  extraContext = memory.extraContext,
                  modified = Timestamp()
                )
                tx.upsert(refreshed).map(_ => UpsertMemoryResult.Refreshed(refreshed))
              case Some(prior) =>
                val now = Timestamp()
                val fresh = memory.copy(
                  supersedes = Some(prior._id),
                  validFrom = Some(now),
                  modified = now
                )
                val archived = prior.copy(
                  validUntil = Some(now),
                  supersededBy = Some(fresh._id),
                  modified = now
                )
                tx.upsert(fresh).flatMap(_ => tx.upsert(archived)).map(_ => UpsertMemoryResult.Versioned(fresh, archived))
            }
          }
      }
    }.flatMap { result =>
      indexMemory(result.memory).map(_ => result)
    }
  }

  /**
   * Convenience overload: persist a memory and auto-fill `createdBy`
   * + `location` from the active chain. `createdBy` resolves to the
   * immediate caller (`chain.last` — typically the agent that
   * authored the memory); `location` resolves via [[locationForChain]]
   * (walks chain for the user, consults [[locationFor]]). Either
   * field is preserved when the caller already set it.
   *
   * Apps that have a `TurnContext` should prefer this over the bare
   * [[persistMemory]] — every memory created from inside an agent
   * turn benefits from auto-attribution + auto-location.
   */
  def persistMemoryFor(memory: ContextMemory,
                       chain: List[sigil.participant.ParticipantId],
                       conversationId: Id[Conversation]): Task[ContextMemory] =
    enrich(memory, chain, conversationId).flatMap(persistMemory)

  /** Convenience overload of [[upsertMemoryByKey]] with the same
    * `createdBy` + `location` auto-fill behavior as [[persistMemoryFor]]. */
  def upsertMemoryByKeyFor(memory: ContextMemory,
                           chain: List[sigil.participant.ParticipantId],
                           conversationId: Id[Conversation]): Task[UpsertMemoryResult] =
    enrich(memory, chain, conversationId).flatMap(upsertMemoryByKey)

  /** Internal helper — fold chain-derived `createdBy` + `location`
    * onto a memory without overwriting fields the caller already set. */
  private def enrich(memory: ContextMemory,
                     chain: List[sigil.participant.ParticipantId],
                     conversationId: Id[Conversation]): Task[ContextMemory] = {
    val withCreator =
      if (memory.createdBy.isDefined) memory
      else chain.lastOption match {
        case Some(p) => memory.copy(createdBy = Some(p))
        case None    => memory
      }
    val withConv =
      if (withCreator.conversationId.isDefined) withCreator
      else withCreator.copy(conversationId = Some(conversationId))
    if (withConv.location.isDefined) Task.pure(withConv)
    else locationForChain(chain, conversationId).map {
      case Some(place) => withConv.copy(location = Some(place))
      case None        => withConv
    }
  }

  /** All versions of a keyed memory in `spaceId`, chronologically
    * (oldest first by `created`). */
  def memoryHistory(key: String, spaceId: SpaceId): Task[List[ContextMemory]] =
    if (key.isEmpty) Task.pure(Nil)
    else withDB(_.memories.transaction { tx =>
      import lightdb.filter.*
      tx.query
        .filter(m => (m.spaceIdValue === spaceId.value) && (m.key === key))
        .toList
        .map(_.sortBy(_.created.value))
    })

  /** Pending (awaiting approval) memories in the given spaces. */
  def listPendingMemories(spaces: Set[SpaceId]): Task[List[ContextMemory]] =
    if (spaces.isEmpty) Task.pure(Nil)
    else withDB(_.memories.transaction { tx =>
      import lightdb.filter.*
      tx.query
        .filter(m => (m.statusName === MemoryStatus.Pending.toString) && spaces.map(s => m.spaceIdValue === s.value).reduce(_ || _))
        .toList
    })

  /** Transition a memory from `Pending` → `Approved`. Returns the
    * updated record, or `None` if the id isn't found. No-op if the
    * memory is already approved. */
  def approveMemory(id: Id[ContextMemory]): Task[Option[ContextMemory]] =
    withDB(_.memories.transaction { tx =>
      tx.get(id).flatMap {
        case None => Task.pure(None)
        case Some(m) if m.status == MemoryStatus.Approved => Task.pure(Some(m))
        case Some(m) =>
          val updated = m.copy(status = MemoryStatus.Approved, modified = Timestamp())
          tx.upsert(updated).map(_ => Some(updated))
      }
    })

  /** Transition a memory to `Rejected` (kept on disk for lineage, but
    * hidden from retrievers). Use [[forgetMemory]] for hard delete. */
  def rejectMemory(id: Id[ContextMemory]): Task[Option[ContextMemory]] =
    withDB(_.memories.transaction { tx =>
      tx.get(id).flatMap {
        case None => Task.pure(None)
        case Some(m) =>
          val updated = m.copy(status = MemoryStatus.Rejected, modified = Timestamp())
          tx.upsert(updated).map(_ => Some(updated))
      }
    })

  /** Hard-delete every version of a keyed memory in `spaceId`. Returns
    * the number of records removed. Also removes corresponding points
    * from the vector index so semantic search doesn't return stale
    * hits. */
  def forgetMemory(key: String, spaceId: SpaceId): Task[Int] =
    if (key.isEmpty) Task.pure(0)
    else memoryHistory(key, spaceId).flatMap { versions =>
      withDB(_.memories.transaction { tx =>
        Task.sequence(versions.map(v => tx.delete(v._id))).map(_ => versions.size)
      }).flatMap { removed =>
        if (!vectorWired) Task.pure(removed)
        else Task
          .sequence(versions.map(v => vectorIndex.delete(VectorPointId(v._id.value))))
          .map(_ => removed)
          .handleError { e =>
            Task(scribe.warn(s"Vector delete failed during forgetMemory(key=$key): ${e.getMessage}"))
              .map(_ => removed)
          }
      }
    }

  /** Bump `accessCount` and `lastAccessedAt` on a memory. Called by
    * retrieval paths (RecallTool, MemoryRetriever) so apps can
    * implement LRU-based retention without Sigil needing its own
    * pruner. */
  def recordMemoryAccess(id: Id[ContextMemory]): Task[Unit] =
    withDB(_.memories.transaction { tx =>
      tx.get(id).flatMap {
        case None => Task.unit
        case Some(m) =>
          val updated = m.copy(
            accessCount = m.accessCount + 1,
            lastAccessedAt = Timestamp()
          )
          tx.upsert(updated).unit
      }
    })

  /** Load all summaries for a conversation, oldest-first. */
  def summariesFor(conversationId: Id[Conversation]): Task[List[ContextSummary]] =
    withDB(_.summaries.transaction { tx =>
      import lightdb.filter.*
      tx.query
        .filter(_.conversationId === conversationId)
        .toList
        .map(_.sortBy(_.created.value))
    })

  // -- vector-indexing internals --

  private final def indexSummary(s: ContextSummary): Task[Unit] =
    if (!vectorWired || s.text.isEmpty) Task.unit
    else embeddingProvider.embed(s.text).flatMap { vec =>
      vectorIndex.upsert(VectorPoint(
        id = VectorPointId(s._id.value),
        vector = vec,
        payload = Map(
          "kind" -> "summary",
          "conversationId" -> s.conversationId.value,
          "summaryId" -> s._id.value
        )
      ))
    }.handleError { e =>
      Task(scribe.warn(s"Vector index failed for summary ${s._id.value}: ${e.getMessage}"))
    }

  private final def indexMemory(m: ContextMemory): Task[Unit] =
    if (!vectorWired || m.fact.isEmpty) Task.unit
    else embeddingProvider.embed(m.fact).flatMap { vec =>
      vectorIndex.upsert(VectorPoint(
        id = VectorPointId(m._id.value),
        vector = vec,
        payload = Map(
          "kind" -> "memory",
          "memoryId" -> m._id.value,
          "spaceId" -> m.spaceId.value,
          sigil.vector.HybridSearch.TextKey -> m.fact
        )
      ))
    }.handleError { e =>
      Task(scribe.warn(s"Vector index failed for memory ${m._id.value}: ${e.getMessage}"))
    }

  // -- search APIs --

  /**
   * Semantic search across persisted [[ContextMemory]] records,
   * restricted to the given spaces. When vector search is wired, embed
   * the query, hit the vector index with a `kind=memory` filter, then
   * hydrate ids via [[SigilDB.memories]]. When not wired, fall back to
   * the existing space-scoped listing (relevance-unordered — callers
   * that care should override this method).
   */
  def searchMemories(query: String,
                     spaces: Set[SpaceId],
                     limit: Int = 10): Task[List[ContextMemory]] =
    if (!vectorWired) findMemories(spaces).map(_.take(limit))
    else embeddingProvider.embed(query).flatMap { vec =>
      vectorIndex.search(vec, limit = limit, filter = Map("kind" -> "memory")).flatMap { hits =>
        val ids = hits.flatMap(_.payload.get("memoryId")).map(Id[ContextMemory](_))
        withDB { db =>
          db.memories.transaction { tx =>
            Task.sequence(ids.map(id => tx.get(id))).map { loaded =>
              val filtered = loaded.flatten.filter(m => spaces.isEmpty || spaces.contains(m.spaceId))
              filtered
            }
          }
        }
      }
    }

  /**
   * Semantic (or Lucene-fallback) search across persisted events in a
   * conversation. Used by the `search_conversation` tool and by app
   * UIs. `topicId` restricts to a single topic when supplied.
   */
  def searchConversationEvents(conversationId: Id[Conversation],
                               query: String,
                               topicId: Option[Id[Topic]] = None,
                               limit: Int = 10): Task[List[Event]] =
    if (!vectorWired) searchEventsLucene(conversationId, query, topicId, limit)
    else embeddingProvider.embed(query).flatMap { vec =>
      val baseFilter = Map("kind" -> "message", "conversationId" -> conversationId.value)
      val filter = topicId.map(t => baseFilter + ("topicId" -> t.value)).getOrElse(baseFilter)
      vectorIndex.search(vec, limit = limit, filter = filter).flatMap { hits =>
        val ids = hits.flatMap(_.payload.get("eventId")).map(Id[Event](_))
        withDB { db =>
          db.events.transaction { tx =>
            Task.sequence(ids.map(id => tx.get(id))).map(_.flatten)
          }
        }
      }
    }

  /** Fallback substring search over conversation events when vector
    * search isn't wired. In-memory scan — fine for the default fallback
    * path; apps that need relevance ranking or large corpora should
    * wire a vector index. */
  private final def searchEventsLucene(conversationId: Id[Conversation],
                                       query: String,
                                       topicId: Option[Id[Topic]],
                                       limit: Int): Task[List[Event]] =
    withDB(_.events.transaction(_.list)).map { all =>
      val needle = query.toLowerCase
      all.filter { e =>
        e.conversationId == conversationId &&
          topicId.forall(e.topicId == _) &&
          eventSearchText(e).toLowerCase.contains(needle)
      }.take(limit)
    }

  /** Best-effort text representation of an event for Lucene-fallback
    * substring search. Apps that add custom event subtypes override
    * this hook to contribute their own searchable text. */
  protected def eventSearchText(event: Event): String = event match {
    case m: Message => m.content.collect { case ResponseContent.Text(t) => t }.mkString("\n")
    case tc: TopicChange => s"${tc.newLabel}"
    case other => other.toString
  }

  /** Maintain materialized projections on the [[Conversation]] record:
    *   - `currentMode` tracks the latest [[ModeChange]]
    *   - `topics` (the navigation stack) tracks the latest [[TopicChange]]:
    *     - `Switch` either pushes a new entry (if the topic isn't on the
    *       stack) or truncates the stack back to that entry (if it is —
    *       the natural "return to prior topic" flow)
    *     - `Rename` mutates the active entry's label + summary in place
    *
    * Fires only on the SETTLE (an Event already at `Complete`, or a
    * `Delta` that transitions its target to `Complete`), never on the
    * initial Active pulse — so these projection fields are written
    * exactly once per transition even though each change flows through
    * `publish` twice (event + state delta). */
  private final def updateConversationProjection(signal: Signal): Task[Unit] = {
    val settled: Task[Option[Event]] = signal match {
      case e: Event if e.state == EventState.Complete => Task.pure(Some(e))
      case d: sigil.signal.Delta =>
        withDB(_.events.transaction(_.get(d.target.asInstanceOf[Id[Event]])))
          .map(_.filter(_.state == EventState.Complete))
      case _ => Task.pure(None)
    }
    settled.flatMap {
      case Some(mc: ModeChange) =>
        withDB(_.conversations.transaction(_.modify(mc.conversationId) {
          case Some(conv) if conv.currentMode != mc.mode =>
            Task.pure(Some(conv.copy(currentMode = mc.mode, modified = Timestamp(Nowish()))))
          case Some(conv) => Task.pure(Some(conv))
          case None       => Task.pure(None)
        })).unit
      case Some(tc: TopicChange) =>
        applyTopicChangeToStack(tc)
      case _ => Task.unit
    }
  }

  /** Update Conversation.topics in response to a settled TopicChange.
    *
    * For Switch: the change carries the post-transition topicId; we
    * either push a fresh entry or truncate the stack back to a matching
    * entry already present.
    *
    * For Rename: walk the stack and update label + summary on every
    * entry whose id matches the renamed topic (typically just the active
    * one). The Topic record itself is updated separately by the
    * orchestrator before publishing.
    */
  private final def applyTopicChangeToStack(tc: TopicChange): Task[Unit] =
    withDB(_.conversations.transaction(_.modify(tc.conversationId) {
      case None => Task.pure(None)
      case Some(conv) =>
        tc.kind match {
          case TopicChangeKind.Switch(_) =>
            val existingIdx = conv.topics.indexWhere(_.id == tc.topicId)
            val nextStack: List[TopicEntry] =
              if (existingIdx >= 0) {
                // Truncate back to that entry — return to prior topic.
                conv.topics.take(existingIdx + 1)
              } else {
                // New topic — load the Topic record and push as a new entry.
                // Fall back to a stub if the record can't be resolved.
                conv.topics :+ TopicEntry(
                  id = tc.topicId,
                  label = tc.newLabel,
                  summary = ""  // populated below from the Topic record
                )
              }
            // If we appended a stub, fetch the Topic record to fill summary.
            val withSummary: Task[List[TopicEntry]] =
              if (existingIdx >= 0) Task.pure(nextStack)
              else withDB(_.topics.transaction(_.get(tc.topicId))).map {
                case Some(t) => nextStack.init :+ TopicEntry(t._id, t.label, t.summary)
                case None    => nextStack
              }
            withSummary.map { stack =>
              if (stack == conv.topics) Some(conv)
              else Some(conv.copy(topics = stack, modified = Timestamp(Nowish())))
            }
          case TopicChangeKind.Rename(_) =>
            // Refresh the entry whose id matches by reading the (already-renamed)
            // Topic record. Walk the stack and replace any matches.
            withDB(_.topics.transaction(_.get(tc.topicId))).map {
              case None => Some(conv)
              case Some(t) =>
                val updatedStack = conv.topics.map { e =>
                  if (e.id == tc.topicId) TopicEntry(t._id, t.label, t.summary) else e
                }
                if (updatedStack == conv.topics) Some(conv)
                else Some(conv.copy(topics = updatedStack, modified = Timestamp(Nowish())))
            }
        }
    })).unit

  // -- topic classification --

  /**
   * The framework's two-step topic resolver. Given the conversation's
   * current topic, its prior topics, and a proposed (label, summary)
   * from a respond call, ask the model to classify the relationship via
   * a focused [[TopicClassifierTool]] call (no conversation history,
   * just the inputs).
   *
   * Returns:
   *   - `NoChange` — same subject as Current; nothing to relabel.
   *   - `Refine`   — same subject as Current; adopt the sharper label.
   *   - `Return(prior)` — same subject as one of the priors; truncate
   *     the stack back to that entry.
   *   - `New`      — a brand new subject; push a fresh entry.
   *
   * If the classifier call fails (provider error, no tool call), falls
   * back to `NoChange` — the safe default that preserves state.
   */
  def classifyTopicShift(modelId: Id[Model],
                         chain: List[ParticipantId],
                         current: TopicEntry,
                         priors: List[TopicEntry],
                         proposedLabel: String,
                         proposedSummary: String,
                         userMessage: String): Task[TopicShiftResult] = {
    val priorsBlock =
      if (priors.isEmpty) "  (none)"
      else priors.map(p => s"  - \"${p.label}\" — ${p.summary}").mkString("\n")
    val systemPrompt =
      """You categorize how a proposed topic relates to a conversation's existing topics.
        |Pick exactly one value from the enum:
        |  - "NoChange" — proposed is the same subject as Current; nothing new to label.
        |  - "Refine"   — same subject as Current, but proposed is a sharper / more specific label.
        |  - <prior label> — same subject as one of the prior topics. The user is returning.
        |  - "New"      — genuinely different from Current and all priors.""".stripMargin
    val userPrompt =
      s"""User just said: ${quote(userMessage)}
         |
         |Current topic:
         |  - "${current.label}" — ${current.summary}
         |
         |Previous topics:
         |$priorsBlock
         |
         |Proposed topic for this turn:
         |  - "$proposedLabel" — $proposedSummary
         |
         |Pick exactly one value from the enum.""".stripMargin
    val tool = new TopicClassifierTool(priors.map(_.label))
    // Sampling settings are baseline `temperature = 0.0` (deterministic
    // classification) — but only when the model supports it. GPT-5 +
    // reasoning-only families (o1, o3, …) hard-reject `temperature`,
    // so consult [[supportsParameter]] before including it. The
    // provider layer also filters as a safety net; gating here too
    // means the framework doesn't emit a parameter it knows the
    // model will reject.
    val classifierSettings = {
      val base = GenerationSettings(maxOutputTokens = Some(50))
      if (supportsParameter(modelId, "temperature")) base.copy(temperature = Some(0.0))
      else base
    }
    ConsultTool.invoke[sigil.tool.consult.TopicClassifierInput](
      sigil = this,
      modelId = modelId,
      chain = chain,
      systemPrompt = systemPrompt,
      userPrompt = userPrompt,
      tool = tool,
      generationSettings = classifierSettings
    ).map {
      case None => TopicShiftResult.NoChange
      case Some(input) => input.kind match {
        case "NoChange" => TopicShiftResult.NoChange
        case "Refine"   => TopicShiftResult.Refine
        case "New"      => TopicShiftResult.New
        case other      =>
          priors.find(_.label == other)
            .map(TopicShiftResult.Return(_))
            .getOrElse(TopicShiftResult.NoChange)
      }
    }.handleError { e =>
      Task {
        scribe.warn(s"classifyTopicShift failed (${e.getClass.getSimpleName}: ${e.getMessage}) — falling back to NoChange")
        TopicShiftResult.NoChange
      }
    }
  }

  private def quote(s: String): String = "\"" + s.replace("\"", "\\\"") + "\""

  // -- conversation helpers --

  /**
   * Create a new [[Conversation]] seeded with an initial [[Topic]]. Both
   * records are persisted so the conversation's `currentTopicId` resolves
   * from the moment it's written. Returns the stored conversation.
   *
   * Apps should route new-conversation creation through here (rather than
   * constructing `Conversation` directly) so the Topic invariant is never
   * violated. `label` defaults to [[Topic.DefaultLabel]] — the LLM is
   * expected to rename it on its first `respond` call once the subject
   * becomes clear.
   */
  def newConversation(createdBy: ParticipantId,
                      label: String = Topic.DefaultLabel,
                      summary: String = Topic.DefaultSummary,
                      participants: List[Participant] = Nil,
                      currentMode: Mode = ConversationMode,
                      conversationId: Id[Conversation] = Conversation.id()): Task[Conversation] = {
    val topic = Topic(
      conversationId = conversationId,
      label = label,
      summary = summary,
      createdBy = createdBy
    )
    val conversation = Conversation(
      topics = List(TopicEntry(topic._id, topic.label, topic.summary)),
      participants = participants,
      currentMode = currentMode,
      _id = conversationId
    )
    for {
      _      <- withDB(_.topics.transaction(_.upsert(topic)))
      stored <- withDB(_.conversations.transaction(_.upsert(conversation)))
      // Broadcast the lifecycle Notice so live viewers' UI panels can
      // pick up the new conversation without polling.
      _      <- publish(sigil.signal.ConversationCreated(stored._id, createdBy))
      // Fire greetings in-line per agent. fireGreeting is a no-op for agents
      // without greet-eligible behaviors, so the cost for non-greeting setups
      // is just the participants.collect walk.
      _      <- Task.sequence(stored.participants.collect {
                  case agent: AgentParticipant => fireGreeting(agent, stored)
                })
    } yield stored
  }

  /**
   * Resolve the [[Conversation]] for `conversationId`, creating it (via
   * [[newConversation]]) with the supplied defaults if no row exists.
   * Returns the resulting Conversation either way.
   *
   * Idempotent — calling this on every wire-connect is the canonical
   * pattern for chat-shaped consumers that want lazy-create-on-first-
   * contact semantics. The participant list, label, summary, and mode
   * are only used on the create path; pre-existing conversations are
   * returned unchanged regardless of what is passed.
   *
   * Greet-on-join behavior matches [[newConversation]] — when the row
   * is being created, agent participants flagged with `greetsOnJoin`
   * fire their greeting; on the get path no greeting fires (the agent
   * already greeted on the original create).
   */
  def getOrCreateConversation(conversationId: Id[Conversation],
                              createdBy: ParticipantId,
                              label: String = Topic.DefaultLabel,
                              summary: String = Topic.DefaultSummary,
                              participants: List[Participant] = Nil,
                              currentMode: Mode = ConversationMode): Task[Conversation] =
    withDB(_.conversations.transaction(_.get(conversationId))).flatMap {
      case Some(c) => Task.pure(c)
      case None =>
        newConversation(
          createdBy = createdBy,
          label = label,
          summary = summary,
          participants = participants,
          currentMode = currentMode,
          conversationId = conversationId
        )
    }

  /**
   * Resolve the current [[Topic]] record for a conversation. Returns
   * `None` only if the conversation's `currentTopicId` refers to a
   * missing Topic record (a data-integrity failure — the invariant is
   * that `newConversation` always persists one).
   */
  def currentTopic(conversation: Conversation): Task[Option[Topic]] =
    withDB(_.topics.transaction(_.get(conversation.currentTopicId)))

  /**
   * Add a [[Participant]] to an existing conversation. Persists the
   * appended participant list, then — if the new participant is an
   * [[AgentParticipant]] — fires its greet-eligible behaviors via
   * [[fireGreeting]] so a late-joining agent has the same opportunity
   * to introduce itself as one that was present at conversation
   * creation.
   *
   * Idempotent: if the participant is already in the conversation,
   * returns the unmodified conversation and skips the greeting.
   *
   * Fails with [[ConversationNotFoundException]] when the conversation
   * id doesn't resolve.
   */
  def addParticipant(conversationId: Id[Conversation],
                     participant: Participant): Task[Conversation] =
    withDB(_.conversations.transaction(_.get(conversationId))).flatMap {
      case None =>
        Task.error(new ConversationNotFoundException(conversationId))
      case Some(conv) if conv.participants.exists(_.id == participant.id) =>
        Task.pure(conv)
      case Some(conv) =>
        val updated = conv.copy(participants = conv.participants :+ participant)
        for {
          stored <- withDB(_.conversations.transaction(_.upsert(updated)))
          _      <- publish(sigil.signal.ParticipantAdded(conversationId, participant))
          _      <- participant match {
                      case agent: AgentParticipant => fireGreeting(agent, stored)
                      case _                       => Task.unit
                    }
        } yield stored
    }

  /**
   * Remove a participant from a conversation. Persists the trimmed list
   * and broadcasts a [[sigil.signal.ParticipantRemoved]] Notice so
   * live viewers can drop the participant from member lists / sidebar
   * UI.
   *
   * Idempotent: if the participant isn't in the conversation, returns
   * the unchanged conversation and emits no Notice. Fails with
   * [[ConversationNotFoundException]] when the conversation id doesn't
   * resolve.
   */
  def removeParticipant(conversationId: Id[Conversation],
                        participantId: ParticipantId): Task[Conversation] =
    withDB(_.conversations.transaction(_.get(conversationId))).flatMap {
      case None =>
        Task.error(new ConversationNotFoundException(conversationId))
      case Some(conv) if !conv.participants.exists(_.id == participantId) =>
        Task.pure(conv)
      case Some(conv) =>
        val updated = conv.copy(participants = conv.participants.filterNot(_.id == participantId))
        for {
          stored <- withDB(_.conversations.transaction(_.upsert(updated)))
          _      <- publish(sigil.signal.ParticipantRemoved(conversationId, participantId))
        } yield stored
    }

  /**
   * Replace a participant's record in a conversation — used to push
   * display-info changes (`displayName`, `avatarUrl`, app-specific
   * fields on a [[Participant]] subtype) out to live viewers without
   * requiring a remove + re-add.
   *
   * The replacement is keyed on `participant.id`; if no current
   * participant matches the id, this is a no-op and emits no Notice.
   * Otherwise the new record replaces the old in the conversation's
   * `participants` list and a [[sigil.signal.ParticipantUpdated]]
   * Notice is broadcast.
   *
   * Fails with [[ConversationNotFoundException]] when the conversation
   * id doesn't resolve.
   */
  def updateParticipant(conversationId: Id[Conversation],
                        participant: Participant): Task[Conversation] =
    withDB(_.conversations.transaction(_.get(conversationId))).flatMap {
      case None =>
        Task.error(new ConversationNotFoundException(conversationId))
      case Some(conv) if !conv.participants.exists(_.id == participant.id) =>
        Task.pure(conv)
      case Some(conv) =>
        val updated = conv.copy(participants = conv.participants.map { p =>
          if (p.id == participant.id) participant else p
        })
        for {
          stored <- withDB(_.conversations.transaction(_.upsert(updated)))
          _      <- publish(sigil.signal.ParticipantUpdated(conversationId, participant))
        } yield stored
    }

  /**
   * Resolve the conversations a viewer can see. Currently the
   * underlying `SigilDB.conversations` is unscoped — every
   * conversation is visible to every viewer. Apps that need
   * per-viewer / per-space scoping override this hook.
   *
   * Used by the framework's default [[handleNotice]] arm for
   * [[sigil.signal.RequestConversationList]] — the snapshot that
   * goes back to the client is built from this list.
   */
  protected def listConversations(viewer: ParticipantId): Task[List[Conversation]] =
    withDB(_.conversations.transaction(_.list))

  /**
   * Filesystem path the conversation is working against — the
   * "project root" / "workspace." Returns `None` when no workspace
   * is configured for the conversation; tools that consult this
   * (filesystem tools rooting relative paths, `MetalsSigil`'s
   * subprocess routing, future BSP / build-server integrations)
   * fall back to their default behavior.
   *
   * Default: `Task.pure(None)`. Apps with a workspace concept
   * (Sage's per-conversation project, Voidcraft's project record)
   * override this once and every framework feature that wants to
   * know "where is this conversation working?" gets a consistent
   * answer.
   *
   * Module traits that need workspace info default their own hook
   * onto this one — e.g. `MetalsSigil.metalsWorkspace` returns
   * `workspaceFor(conversationId)` by default. Apps overriding
   * `workspaceFor` automatically light up Metals routing, FS
   * rooting, and any future workspace-aware feature.
   */
  def workspaceFor(conversationId: Id[Conversation]): Task[Option[java.nio.file.Path]] =
    Task.pure(None)

  /**
   * Hard-delete a conversation and every record that references it —
   * the conversation row itself, every Event, the ConversationView
   * projection, and every Topic. The deletion is best-effort and
   * non-transactional across stores; failures partway through leave
   * the DB in a partially-cleaned state and re-raise.
   *
   * After deletion, any in-flight agent loop targeting this
   * conversation will release its claim on the next iteration's
   * `withDB(_.conversations.get(...))` lookup (returns `None`,
   * `runAgentLoop` releases cleanly).
   */
  def deleteConversation(conversationId: Id[Conversation]): Task[Unit] =
    for {
      // Broadcast the lifecycle Notice BEFORE the cascade so live
      // viewers see the pulse while the SignalHub is still wired
      // (and before the conversation's records are wiped).
      _ <- publish(sigil.signal.ConversationDeleted(conversationId))
      _ <- withDB(_.conversations.transaction(_.delete(conversationId)))
      _ <- withDB { db =>
             db.events.transaction { tx =>
               tx.list.flatMap { all =>
                 val targets = all.filter(_.conversationId == conversationId)
                 Task.sequence(targets.map(e => tx.delete(e._id))).unit
               }
             }
           }
      _ <- withDB(_.views.transaction(_.delete(ConversationView.idFor(conversationId))))
      _ <- withDB { db =>
             db.topics.transaction { tx =>
               tx.query.filter(_.conversationId === conversationId).toList.flatMap { topics =>
                 Task.sequence(topics.map(t => tx.delete(t._id))).unit
               }
             }
           }
    } yield ()

  /**
   * Clear a conversation's visible history without deleting the
   * conversation. Sets a `clearedAt` watermark on the
   * [[Conversation]] record; subsequent view rebuilds and
   * frame-append paths filter out events emitted at-or-before that
   * watermark. The events themselves stay in [[sigil.db.SigilDB.events]]
   * for audit — this is a soft clear, not a hard delete.
   *
   * After clearing:
   *   - [[ConversationView.frames]] is empty.
   *   - Per-participant projections (suggested tools, recent tools)
   *     reset to defaults.
   *   - New events added after the clear flow through normally.
   *   - The agent's curator sees only post-clear events; no
   *     pre-clear context leaks into the model's prompt.
   *
   * Broadcasts a [[sigil.signal.ConversationCleared]] Notice so
   * live viewers can reset their UI. Apps that need a hard purge
   * (events removed from `db.events`) implement that on top of this
   * — typically by tailing the Notice stream and running a delete
   * pass against the events store.
   */
  def clearConversation(conversationId: Id[Conversation],
                        clearedBy: ParticipantId): Task[Unit] = {
    val now = Timestamp(Nowish())
    withDB(_.conversations.transaction(_.modify(conversationId) {
      case Some(conv) => Task.pure(Some(conv.copy(clearedAt = Some(now), modified = now)))
      case None       => Task.pure(None)
    })).flatMap {
      case None => Task.unit  // no conversation to clear — silent no-op
      case Some(_) =>
        for {
          // Reset the projection to empty; new events appended
          // after this point flow through `appendToViewIfNew`,
          // which will see the watermark and treat them as
          // post-clear.
          _ <- withDB(_.views.transaction(_.upsert(ConversationView(
                 conversationId = conversationId,
                 frames = Vector.empty,
                 participantProjections = Map.empty,
                 _id = ConversationView.idFor(conversationId)
               ))))
          _ <- publish(sigil.signal.ConversationCleared(
                 conversationId = conversationId,
                 clearedAt      = now,
                 clearedBy      = clearedBy
               ))
        } yield ()
    }
  }

  /** Recursively drop fields whose value is JSON `null`. Used to
    * pre-process [[sigil.signal.UpdateViewerStateDelta]] patches —
    * fabric's case-class RW emits `None` as `null`, and the default
    * merge would otherwise overlay those nulls onto the prior
    * payload, defeating the "untouched fields stay" intent.
    * Non-object JSON values pass through unchanged. */
  private final def stripNulls(json: fabric.Json): fabric.Json = json match {
    case obj: fabric.Obj =>
      val kept = obj.value.iterator.collect {
        case (k, v) if v != fabric.Null => (k, stripNulls(v))
      }.toMap
      fabric.Obj(kept)
    case other => other
  }

  /**
   * Push a [[sigil.signal.ViewerStateSnapshot]] for every persisted
   * scope the viewer owns. Used by apps from their connection /
   * authentication-completion handler to give a freshly-connected
   * (or freshly-authenticated) session its full state up front,
   * without the client having to know which scopes exist or send
   * one [[sigil.signal.RequestViewerState]] per scope.
   *
   * Targeted at the viewer's connected sessions via
   * [[publishTo]] — broadcast subscribers (audit, debug taps) don't
   * receive the snapshots. Each snapshot is a separate Notice;
   * order matches whatever `db.viewerStates.list` returns (no
   * ordering guarantee, but consumers don't depend on order
   * because each snapshot is keyed by scope).
   *
   * Safe to call multiple times — emits a fresh snapshot per call.
   * Apps wiring this from a "viewer became authenticated" handler
   * call it at the moment the viewer transitions to its
   * authenticated id; pre-auth viewers see only their own (System)
   * state.
   */
  def publishViewerStatesTo(viewer: ParticipantId): Task[Unit] =
    withDB(_.viewerStates.transaction(_.list)).flatMap { all =>
      val mine = all.filter(_.participantId == viewer)
      Task.sequence(mine.toList.map { record =>
        publishTo(viewer, sigil.signal.ViewerStateSnapshot(record.scope, Some(record.payload)))
      }).unit
    }

  private final def fanOut(event: Event): Task[Unit] =
    withDB(_.conversations.transaction(_.get(event.conversationId))).flatMap {
      case None       => Task.unit
      case Some(conv) =>
        val tasks: List[Task[Unit]] = conv.participants.collect {
          case agent: AgentParticipant if TriggerFilter.isTriggerFor(agent, event) =>
            tryFire(agent, conv)
        }
        Task.sequence(tasks).unit
    }

  /**
   * Atomically claim `AgentState(Active)` for `(agent, conv)`. If we win the
   * claim, broadcast the new AgentState and start the agent's self-loop on
   * a background fiber. If someone else already owns the lock, no-op.
   *
   * The lock IS the AgentState record, identified by a stable id derived
   * from `(agentId, conversationId)`. Each turn upserts the same id; the
   * `AtomicReference` captures whether OUR `f` was the one that returned a
   * fresh `Active` (the only way to tell with `tx.modify` semantics).
   */
  private final def tryFire(agent: AgentParticipant, conv: Conversation, greeting: Boolean = false): Task[Unit] = {
    val lockId = agentStateLockId(agent.id, conv._id)
    val claimedRef = new AtomicReference[Option[AgentState]](None)
    withDB(_.events.transaction(_.modify(lockId) {
      case Some(s: AgentState) if s.state == EventState.Active =>
        Task.pure(Some(s))  // someone else owns it; observe and bail
      case _ =>
        val claim = AgentState(
          agentId = agent.id,
          participantId = agent.id,
          conversationId = conv._id,
          topicId = conv.currentTopicId,
          activity = AgentActivity.Thinking,
          state = EventState.Active,
          timestamp = Timestamp(Nowish()),
          _id = lockId
        )
        claimedRef.set(Some(claim))
        Task.pure(Some(claim))
    })).flatMap { _ =>
      claimedRef.get() match {
        case Some(claim) =>
          // We won the claim. Register a StopFlag for this claim so any
          // Stop events published against this agent can interrupt.
          stopFlags.put(claim._id, new StopFlag)
          // Broadcast manually (modify already persisted), then fire the
          // agent on its own fiber.
          Task {
            hub.emit(claim)
            runAgent(agent, conv, claim, greeting = greeting).startUnit()
            ()
          }
        case None => Task.unit
      }
    }
  }

  /**
   * Fire a one-shot greeting turn for `agent` in `conv`. Runs the agent's
   * standard merged dispatch through the lock-claim → loop machinery —
   * but with an empty trigger stream so the agent's roles' descriptions /
   * skills drive what the greeting says.
   *
   * No-op when `agent.greetsOnJoin == false`. Called automatically by
   * [[newConversation]] (fresh conversation case) and
   * [[addParticipant]] (late-join case); apps can also call it
   * directly to greet on demand.
   */
  def fireGreeting(agent: AgentParticipant, conv: Conversation): Task[Unit] =
    if (agent.greetsOnJoin) tryFire(agent, conv, greeting = true)
    else Task.unit

  /**
   * Self-loop while holding the AgentState(Active) claim:
   *
   *   - process triggers for the current iteration
   *   - check DB for any new triggers that arrived during processing
   *   - if any, loop without releasing the claim
   *   - if none, transition to Idle/Complete and release
   */
  /** Hard cap on dispatcher self-loop iterations within a single AgentState
    * claim. Prevents an LLM that keeps calling non-terminal tools (e.g. only
    * `change_mode`, never `respond`) from looping forever. Reaching the cap
    * raises [[AgentRunawayException]] in the runAgent fiber after releasing
    * the AgentState claim — it's a real failure, not a normal exit. Apps
    * can override. */
  protected def maxAgentIterations: Int = 10

  private final def runAgent(agent: AgentParticipant,
                             conv: Conversation,
                             claimed: AgentState,
                             greeting: Boolean = false): Task[Unit] =
    runAgentLoop(
      agent,
      conv._id,
      claimed,
      iteration = 1,
      sinceTimestamp = claimed.timestamp,
      greeting = greeting,
      // Tracks "did the agent ever produce a user-visible terminal
      // signal across this loop's iterations?" The synthesized
      // placeholder Message in the no-more-triggers branch only fires
      // when this stays false. Bug #46 — without it, an agent that
      // chains tool calls without ever calling `respond` /
      // `no_response` / etc. ends the conversation in silence.
      userVisibleSeen = new java.util.concurrent.atomic.AtomicBoolean(false)
    )

  /**
   * `sinceTimestamp` advances per iteration — each loop hands the next one
   * its own start-time, so events consumed by the previous iteration
   * (ModeChange from iter 1, ToolResults from iter 2, etc.) don't re-appear
   * as "new triggers" on every subsequent check and cause spurious loops.
   *
   * The very first iteration uses `claim.timestamp` as its starting point
   * so external triggers that landed between claim-time and iteration-1
   * start are still visible.
   */
  private final def runAgentLoop(agent: AgentParticipant,
                                 convId: Id[Conversation],
                                 claimed: AgentState,
                                 iteration: Int,
                                 sinceTimestamp: Timestamp,
                                 greeting: Boolean = false,
                                 userVisibleSeen: java.util.concurrent.atomic.AtomicBoolean): Task[Unit] = Task.defer {
    // Snapshot the start of THIS iteration. The next iteration uses this as
    // its own `sinceTimestamp`, so events emitted during this iteration
    // (including self-emitted non-terminal tool results the agent acted on)
    // don't re-appear as triggers next time.
    val thisIterationStart = Timestamp(Nowish())
    val stopFlag = Option(stopFlags.get(claimed._id))
    // A Stop may have landed before this iteration even starts; short-
    // circuit if so (graceful = "don't start another iteration"; force
    // = "same, plus the in-flight stream below won't run"). Either way,
    // release and exit.
    if (stopFlag.exists(_.requested)) releaseClaim(claimed)
    else
    // Reload the conversation each iteration — materialized projections
    // (currentMode, modified, etc.) update as Events flow through `publish`,
    // so the conversation we hand to the agent must reflect the latest state.
    withDB(_.conversations.transaction(_.get(convId))).flatMap {
      case None =>
        // Conversation deleted mid-turn — release the lock and exit cleanly.
        releaseClaim(claimed)
      case Some(conv) =>
        // Snapshot suggestedTools BEFORE the iteration so we can detect
        // whether a fresh `find_capability` during the turn replaced them.
        // If it did, the new list survives to the next turn. If it didn't,
        // the snapshot (which the agent saw in its roster this turn)
        // decays — suggestions live for exactly ONE turn; an agent that
        // doesn't call what it discovered loses it.
        viewFor(convId).map(_.projectionFor(agent.id).suggestedTools).flatMap { suggestedSnapshot =>
          buildContext(agent, conv, sinceTimestamp = sinceTimestamp, claimedId = claimed._id).flatMap {
            case (ctx, triggers) =>
              // Wrap the agent's signal stream with a force-stop check so a
              // Stop(force=true) mid-iteration terminates the stream promptly.
              // Greeting mode (only on iteration == 1): dispatch only behaviors
              // with `greetsOnJoin = true` against an empty trigger stream;
              // subsequent iterations (driven by the agent's own non-terminal
              // tool calls) revert to the standard process path.
              val rawStream =
                if (greeting && iteration == 1) agent.processGreeting(ctx)
                else agent.process(ctx, triggers)
              val interruptible = stopFlag match {
                case Some(flag) => rawStream.takeWhile(_ => !flag.force.get())
                case None       => rawStream
              }
              // Tap the stream for user-visible terminal signals. A
              // settled `ToolDelta` whose target ToolInvoke names a
              // user-visible terminal tool (`respond` / `no_response`
              // / etc.) flips the loop-wide flag — so the no-more-
              // triggers branch knows whether to synthesize a
              // placeholder Message. We watch the ToolInvoke (which
              // carries `toolName`) and remember matching invoke ids,
              // then flip the flag on their settle delta.
              val activeUserVisibleInvokes = new java.util.concurrent.ConcurrentHashMap[Id[Event], Boolean]()
              interruptible
                .evalTap {
                  case ti: ToolInvoke if Orchestrator.UserVisibleTerminalTools.contains(ti.toolName.value) =>
                    Task { activeUserVisibleInvokes.put(ti._id, true); () }
                  case td: ToolDelta if td.state.contains(EventState.Complete)
                                     && activeUserVisibleInvokes.containsKey(td.target) =>
                    Task { userVisibleSeen.set(true); () }
                  case _ => Task.unit
                }
                .evalTap(publish)
                .drain
          }.flatMap(_ => decaySuggestedTools(convId, agent.id, suggestedSnapshot))
        }.flatMap { _ =>
          // After the iteration drains, check stop flags before anything
          // else — a Stop that fired mid-stream means exit now, don't
          // continue looping even if there are new triggers.
          if (stopFlag.exists(_.requested))
            ensureSilentTurnReply(agent, convId, userVisibleSeen).flatMap(_ => releaseClaim(claimed))
          else newTriggersExist(agent, conv, sinceTimestamp = thisIterationStart).flatMap {
            case true if iteration < maxAgentIterations =>
              runAgentLoop(agent, convId, claimed, iteration + 1, thisIterationStart, userVisibleSeen = userVisibleSeen)
            case true =>
              // Cap hit — release the lock, then propagate as an error so the
              // calling fiber's failure handler sees it. A runaway loop is a
              // real failure (broken LLM behavior, bad instructions, etc.) and
              // shouldn't masquerade as a successful exit.
              ensureSilentTurnReply(agent, convId, userVisibleSeen).flatMap(_ =>
                releaseClaim(claimed).flatMap(_ =>
                  Task.error(new AgentRunawayException(
                    s"Agent ${agent.id.value} hit maxAgentIterations ($maxAgentIterations) " +
                      s"in conversation ${conv._id.value}; check LLM behavior or raise the cap."))))
            case false =>
              ensureSilentTurnReply(agent, convId, userVisibleSeen).flatMap(_ => releaseClaim(claimed))
          }
        }
    }.handleError { t =>
      // Any unhandled failure mid-turn — release the lock so the agent
      // isn't stuck Active forever, then re-raise so the fiber's error
      // boundary logs it. Failure during release itself is swallowed (we
      // already have the original error to report).
      scribe.error(s"runAgent failed for ${agent.id.value} in ${convId.value}", t)
      releaseClaim(claimed).handleError(_ => Task.unit).flatMap(_ => Task.error(t))
    }
  }

  /** Synthesize a placeholder Message when the agent loop terminates
    * without ever calling a user-visible terminal tool (`respond`,
    * `respond_options`, `respond_field`, `respond_failure`,
    * `no_response`). Otherwise the chat goes silent after a chain of
    * tool calls and the user is stuck — bug #46.
    *
    * Idempotent — caller can invoke from any terminal branch
    * (no-more-triggers, max-iterations, stop-requested) without
    * worrying about double-emission, because the `userVisibleSeen`
    * flag is loop-scoped and not mutated after this. */
  private final def ensureSilentTurnReply(agent: AgentParticipant,
                                          convId: Id[Conversation],
                                          userVisibleSeen: java.util.concurrent.atomic.AtomicBoolean): Task[Unit] =
    if (userVisibleSeen.get()) Task.unit
    else withDB(_.conversations.transaction(_.get(convId))).flatMap {
      case None => Task.unit
      case Some(conv) =>
        // No-topic conversations are a degenerate state — skip rather
        // than fabricate a topic id. Caller's release path handles
        // the rest of the cleanup.
        conv.topics.headOption match {
          case None        => Task.unit
          case Some(topic) =>
            publish(Message(
              participantId  = agent.id,
              conversationId = convId,
              topicId        = topic.id,
              content        = Vector(sigil.tool.model.ResponseContent.Text("(agent completed without a reply)")),
              state          = EventState.Complete
            )).map(_ => ())
        }
    }

  /** One-turn decay for `suggestedTools` on the acting participant's
    * projection. If the current list equals the snapshot we took before
    * the iteration started, no fresh `find_capability` ran this turn —
    * the agent either called a previously-discovered tool (and the list
    * has served its purpose) or ignored it. Either way, clear so the
    * suggestion doesn't leak into another turn. If the list differs, a
    * new discovery landed during the iteration; keep the new list so
    * the NEXT turn can call it. */
  private final def decaySuggestedTools(conversationId: Id[Conversation],
                                         agentId: ParticipantId,
                                         snapshot: List[sigil.tool.ToolName]): Task[Unit] =
    viewFor(conversationId).flatMap { view =>
      val current = view.projectionFor(agentId).suggestedTools
      if (current == snapshot && current.nonEmpty)
        updateProjection(conversationId, agentId)(_.copy(suggestedTools = Nil))
      else Task.unit
    }

  private final def releaseClaim(claimed: AgentState): Task[Unit] = {
    // Always-run cleanup — the flag must never leak even if the
    // Idle/Complete publish fails (broadcaster error, DB error, etc.).
    // A leaked flag is a minor map entry, but across many failures it'd
    // accumulate and match future Stop events against a non-existent
    // claim.
    val cleanup = Task {
      stopFlags.remove(claimed._id)
      ()
    }
    publish(AgentStateDelta(
      target = claimed._id,
      conversationId = claimed.conversationId,
      activity = Some(AgentActivity.Idle),
      state = Some(EventState.Complete)))
      .flatMap(_ => cleanup)
      .handleError(t => cleanup.flatMap(_ => Task.error(t)))
  }

  private final def buildContext(agent: AgentParticipant,
                                 conv: Conversation,
                                 sinceTimestamp: Timestamp,
                                 claimedId: Id[Event]): Task[(TurnContext, Stream[Event])] =
    for {
      rawView <- viewFor(conv._id)
      view = rawView.copy(frames = rawView.frames.filter(f => visibilityAllows(f.visibility, agent.id)))
      triggerEvents <- withDB(_.events.transaction(_.list)).map { all =>
        all.view
          .filter(e => e.conversationId == conv._id
                    && e.timestamp.value > sinceTimestamp.value
                    && TriggerFilter.isTriggerFor(agent, e)
                    && visibilityAllows(e.visibility, agent.id))
          .toList
      }
      chain = buildChain(triggerEvents, agent)
      input <- curate(view, agent.modelId, chain)
    } yield {
      val triggers: Stream[Event] = Stream.emits(triggerEvents)
      val ctx = TurnContext(
        sigil = this,
        chain = chain,
        conversation = conv,
        conversationView = view,
        turnInput = input,
        currentAgentStateId = Some(claimedId)
      )
      (ctx, triggers)
    }

  private final def newTriggersExist(agent: AgentParticipant,
                                     conv: Conversation,
                                     sinceTimestamp: Timestamp): Task[Boolean] =
    withDB(_.events.transaction(_.list)).map { all =>
      all.exists(e => e.conversationId == conv._id
                   && e.timestamp.value > sinceTimestamp.value
                   && TriggerFilter.isTriggerFor(agent, e))
    }

  private final def buildChain(triggers: List[Event], agent: AgentParticipant): List[ParticipantId] = {
    val source = triggers.find(_.participantId != agent.id).map(_.participantId)
    source.toList :+ agent.id
  }

  /** Stable per-(agent, conversation) id used as both the AgentState key and
    * the lock-acquisition target inside `tx.modify`. */
  private final def agentStateLockId(agentId: AgentParticipantId, convId: Id[Conversation]): Id[Event] =
    Id(s"agentlock:${agentId.value}:${convId.value}")

  // -- lifecycle --

  /**
   * Phase-1 lifecycle: populate every fabric `PolyType` discriminator
   * with the framework + app-defined subtypes. Pure JVM-level effect
   * — does not open the LightDB / RocksDB store, does not start any
   * background fibers. Idempotent (`.singleton`).
   *
   * Codegen / schema-introspection tasks (e.g. Dart generator,
   * OpenAPI schema dumper) call `polymorphicRegistrations.sync()`
   * instead of `instance.sync()`. That gives them the populated
   * `summon[RW[Signal]].definition`, `summon[RW[ParticipantId]].definition`,
   * etc. without contending with a live backend for the RocksDB
   * lock — multiple developer terminals can run codegen against the
   * same Sigil module while a server is running.
   *
   * `instance` runs this first, so runtime consumers see the same
   * ordering as before.
   *
   * **Registration order matters.** Leaf polys (no fields referencing
   * other polys) MUST be populated before composite polys whose
   * case-class subtypes have fields typed against the leaves —
   * otherwise fabric's lazy-val `Definition` for those subtypes
   * captures an empty leaf-poly snapshot when `RW.poly` walks them
   * at register-time, and downstream callers see leaf polys with
   * zero subtypes.
   */
  val polymorphicRegistrations: Task[Unit] = Task.defer {
    for {
      _ <- logger.info("Sigil registering polymorphic discriminators...")
      // Leaf polys (no fields referencing other polys) first — `RW.poly`
      // reads each subtype's `definition` eagerly at register-time
      // (fabric/rw/RW.scala:207) and case-class definitions are
      // `lazy val` (fabric/rw/CompileRW.scala:996), so the first read
      // freezes the leaf-poly state in. Any aggregate registration
      // (Participant, Tool, Signal) whose subtypes have fields typed
      // against a leaf must run after that leaf, otherwise downstream
      // consumers (notably the Spice Dart codegen) see empty
      // dispatchers despite the leaf register call succeeding.
      _ = SpaceId.register((RW.static(GlobalSpace) :: spaceIds).distinct*)
      _ = sigil.tool.ToolKind.register(
            (RW.static(sigil.tool.BuiltinKind) :: toolKindRegistrations).distinct*
          )
      _ = ParticipantId.register(participantIds*)
      _ = Mode.register((ConversationMode :: modes).distinct.map(m => RW.static(m))*)
      _ = sigil.provider.WorkType.register(
            (List[sigil.provider.WorkType](
              sigil.provider.ConversationWork,
              sigil.provider.CodingWork,
              sigil.provider.AnalysisWork,
              sigil.provider.ClassificationWork,
              sigil.provider.CreativeWork,
              sigil.provider.SummarizationWork
            ) ++ workTypeRegistrations).distinct.map(w => RW.static(w))*
          )
      // Bug #53 — `toolInputRegistrations` is the mixin extension
      // point for non-static tools whose `inputRW` isn't reachable
      // through the static-roster scan (notably `JsonInput`, used by
      // `ScriptTool` and `McpTool`). Without including it here,
      // `ScriptSigil` / `McpSigil` apps would crash at the first
      // runtime tool's `ToolInvoke` persistence with `Type not found
      // [JsonInput]`.
      _ = ToolInput.register((CoreTools.inputRWs ++ findTools.toolInputRWs ++ toolInputRegistrations).distinctBy(_.definition.className)*)
      _ = sigil.viewer.ViewerStatePayload.register(viewerStatePayloadRegistrations.distinct*)
      // Aggregates after leaves.
      _ = Participant.register((summon[RW[DefaultAgentParticipant]] :: participants)*)
      _ = sigil.tool.Tool.register((staticTools.map(t => RW.static(t)) ++ toolRegistrations).distinct*)
      _ = Signal.register((allEventRWs ++ allDeltaRWs ++ allNoticeRWs ++ signalRegistrations)*)
    } yield ()
  }.singleton

  /** True once [[instance]]'s task body has begun executing — used by
    * [[shutdown]] to skip DB-dispose when no instance was ever
    * constructed (e.g. codegen-only paths that ran
    * `polymorphicRegistrations` without opening the store). */
  private val instanceStarted: java.util.concurrent.atomic.AtomicBoolean =
    new java.util.concurrent.atomic.AtomicBoolean(false)

  val instance: Task[SigilInstance] = Task.defer {
    for {
      _ <- polymorphicRegistrations
      _ <- logger.info("Sigil initializing...")
      _ <- Task(Profig.initConfiguration())
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
        appUpgrades = List(new sigil.tool.StaticToolSyncUpgrade(staticTools))
      )
      _ <- db.init
      _ <- if (vectorWired) vectorIndex.ensureCollection(embeddingProvider.dimensions)
           else Task.unit
      _ <- cache.loadFromDisk
      _ <- startModelRefresh()
    } yield SigilInstance(
      config = config,
      db = db
    )
  }.singleton

  /**
   * Kick off the periodic model-registry refresh. First refresh runs
   * immediately; subsequent ones every [[modelRefreshInterval]].
   * Failures are logged and swallowed — the registry keeps its last
   * known state (loaded from disk on init), so a transient OpenRouter
   * outage doesn't break the app.
   */
  private def startModelRefresh(): Task[Unit] = modelRefreshInterval match {
    case None => Task.unit
    case Some(interval) =>
      def safeRefresh: Task[Unit] = OpenRouter.refreshModels(this).handleError { e =>
        Task { scribe.warn(s"Model refresh failed: ${e.getMessage}; keeping current registry"); () }
      }
      def loop: Task[Unit] =
        if (isShutdown) Task.unit
        else safeRefresh.flatMap(_ => Task.sleep(interval)).flatMap(_ => loop)
      Task { loop.startUnit(); () }
  }

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
      Task { hub.close() }
    }.flatMap { _ =>
      if (!instanceStarted.get()) Task.unit
      else instance.flatMap { sigil =>
        sigil.db.dispose.handleError { t =>
          Task { scribe.warn(s"Sigil shutdown: db.dispose failed: ${t.getMessage}"); () }
        }
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

  /**
   * Disk fallback location for the model registry. Default: a
   * `models.json` file alongside the configured `sigil.dbPath`.
   * Apps can override to put it elsewhere or return `None` to disable
   * disk persistence entirely.
   */
  def modelCachePath: Option[Path] = {
    val raw = Profig("sigil.dbPath").asOr[String]("db/sigil")
    Some(Path.of(raw).resolve("models.json"))
  }

  /**
   * How often the in-memory model registry is refreshed from
   * upstream (OpenRouter). Default `None` — apps that don't use
   * OpenRouter-sourced models (local llama.cpp deployments,
   * direct-Anthropic / direct-OpenAI configurations, etc.) get no
   * background HTTP traffic. OpenRouter consumers opt in by
   * overriding to e.g. `Some(8.hours)` for periodic refresh, or
   * leave `None` and call `OpenRouter.refreshModels` manually.
   */
  def modelRefreshInterval: Option[FiniteDuration] = None

  /**
   * In-memory model registry — the canonical source of catalog
   * lookups. `Provider.models` and `isImageOnlyModel`-style hot paths
   * read it synchronously (single `AtomicReference` deref, no DB
   * round-trip). Populated from disk on init and refreshed in the
   * background per [[modelRefreshInterval]].
   */
  final lazy val cache: ModelRegistry = new ModelRegistry(modelCachePath)

  /**
   * Does the registered [[sigil.db.Model]] declare support for the
   * given request parameter (e.g. `"temperature"`, `"top_p"`,
   * `"tools"`)?
   *
   * Returns `true` when the model isn't in [[cache]] OR the model's
   * `supportedParameters` set is empty — both signal "we don't have
   * authoritative capability info, so don't filter." This is the
   * fail-open posture the framework wants on cold cache so cataloged
   * features (`temperature = 0.0` in the topic classifier, etc.) keep
   * working until the registry refreshes. Provider impls should still
   * apply per-API safety nets (e.g. dropping `temperature` for
   * fixed-sampling model families) for the cold-cache window.
   *
   * Apps that want a stricter posture (fail-closed, "if we don't know
   * the model don't send the param") override this hook.
   */
  def supportsParameter(modelId: Id[Model], parameterName: String): Boolean =
    cache.find(modelId) match {
      case Some(model) if model.supportedParameters.nonEmpty =>
        model.supportedParameters.contains(parameterName)
      case _ => true
    }

  /**
   * Convenience accessor for [[sigil.transport.SignalTransport]] — the
   * bridge from `signalsFor(viewer)` to wire sinks (SSE, DurableSocket).
   * Apps can construct a `new SignalTransport(this)` directly; this
   * accessor exists so the typical "subscribe a sink for a viewer" call
   * site reads as `sigil.signalTransport.attach(viewer, sink, resume)`.
   */
  final lazy val signalTransport: SignalTransport = new SignalTransport(this)

  /**
   * Spice [[spice.http.durable.EventLog]] adapter that reads from the
   * same `SigilDB.events` store that `Sigil.publish` writes to. Apps
   * mounting a `DurableSocketServer` pass this as the `eventLog`
   * argument so resume reads stream from the durable history with no
   * separate buffer.
   */
  final lazy val eventLog: sigil.transport.SigilDbEventLog =
    new sigil.transport.SigilDbEventLog(this)

  case class SigilInstance(config: Config, db: DB)
}
