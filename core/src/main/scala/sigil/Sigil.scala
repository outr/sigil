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
import sigil.conversation.{ActiveSkillSlot, ContextFrame, ContextKey, ContextMemory, ContextSummary, Conversation, EncodedContext, FrameBuilder, MemorySource, MemoryStatus, ParticipantProjection, ProgressContext, SkillSource, ToolCallState, Topic, TopicEntry, TopicShiftResult, TurnInput, UpsertMemoryResult}
import sigil.SpaceId
import sigil.cache.ModelRegistry
import sigil.controller.OpenRouter
import sigil.embedding.{EmbeddingProvider, NoOpEmbeddingProvider}
import sigil.transport.SignalTransport

import java.nio.file.Path
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import sigil.tool.consult.{ConsultTool, TopicClassifierTool}
import sigil.provider.{GenerationSettings, TokenUsage}
import sigil.db.{DefaultSigilDB, Model, SigilDB}
import sigil.dispatcher.{StopFlag, TriggerFilter}
import sigil.event.{AgentState, CapabilityResults, Event, EventsPage, Message, MessageRole, MessageVisibility, ModeChange, Stop, ToolInvoke, TopicChange, TopicChangeKind}
import sigil.role.Role
import sigil.orchestrator.Orchestrator
import sigil.provider.{Complexity, ConversationMode, ConversationRequest, Mode, ProviderStrategy, ReasoningMode, ToolPolicy, WorkType}
import sigil.information.Information
import sigil.participant.{AgentParticipant, AgentParticipantId, DefaultAgentParticipant, Participant, ParticipantId}
import sigil.pipeline.{ContentExternalizationTransform, GeocodingEnrichmentEffect, InboundTransform, LocationCaptureTransform, MemoryCacheInvalidationEffect, MessageIndexingEffect, RedactLocationTransform, RespondOptionsSelectionFramingTransform, SettledEffect, SignalHub, TopicIndexCanonicalizingTransform, ViewerTransform, WorkerConversationAddressingTransform}
import sigil.render.{ContentRenderer, HtmlRenderer, MarkdownRenderer, PlainTextRenderer, SlackMrkdwnRenderer}
import sigil.provider.Provider
import sigil.service.Service
import sigil.signal.{AgentActivity, AgentStateDelta, CoreSignals, Delta, EventState, LocationDelta, Notice, ServiceLogSignal, ServiceStatusSignal, Signal, ToolDelta}
import sigil.spatial.{Geocoder, NoOpGeocoder, Place}
import sigil.tool.Tool
import sigil.tool.fs.{FileSystemContext, LocalFileSystemContext}
import sigil.tool.core.{CoreTools, FindCapabilityTool}
import sigil.tool.model.ResponseContent
import sigil.tool.{ToolFinder, ToolInput}
import sigil.vector.{NoOpVectorIndex, VectorIndex, VectorPoint, VectorPointId, VectorSearchResult}

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

trait Sigil extends ProviderConfigStore with MemoryOps with ViewerStateOps {

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
   * App-specific [[sigil.heal.CorruptionEvidence]] subtypes. The
   * framework's three core cases
   * ([[sigil.heal.CorruptionEvidence.MissingToolResult]],
   * [[sigil.heal.CorruptionEvidence.DanglingToolResultOrigin]],
   * [[sigil.heal.CorruptionEvidence.OrphanSummaryCoverage]]) register
   * automatically; this list extends the discriminator for app-defined
   * corruption shapes.
   *
   * Default empty — apps add subtypes only when their healing
   * strategies emit non-framework evidence.
   */
  protected def corruptionEvidenceRegistrations: List[RW[? <: sigil.heal.CorruptionEvidence]] = Nil

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

  /** Mixin hook for polytype registrations that need the framework's leaf
    * polytypes (Mode, WorkType, SpaceId, ...) populated before the mixin
    * subtypes' RW Definitions are eagerly evaluated. Runs inside
    * [[polymorphicRegistrations]] after the framework leaves and before
    * the aggregates (Participant, Tool, Signal). Default `Task.unit`. */
  protected def mixinPolymorphicRegistrations: rapid.Task[Unit] =
    rapid.Task(sigil.information.Information.register(summon[RW[sigil.information.StoredInformation]]))

  /** Aggregate of framework-shipped + app-registered [[WorkType]] subtypes —
    * symmetric with [[modes]] / [[spaceIds]]. The codegen pipeline iterates
    * this list to populate the Dart `WorkType` polytype's subtype dispatch
    * + singleton fields. Apps add their own subtypes via
    * [[workTypeRegistrations]]; the framework's own ride for free. */
  protected def workTypes: List[sigil.provider.WorkType] = (List[sigil.provider.WorkType](
    sigil.provider.ConversationWork,
    sigil.provider.CodingWork,
    sigil.provider.AnalysisWork,
    sigil.provider.ClassificationWork,
    sigil.provider.CreativeWork,
    sigil.provider.SummarizationWork
  ) ++ workTypeRegistrations).distinct

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

  /**
   * When true, the provider stack runs
   * [[sigil.diagnostics.RequestProfiler]] over every outbound request
   * and emits the per-section token breakdown — plus
   * [[sigil.diagnostics.ContextManagementInsight]] derivations — as a
   * [[sigil.signal.WireRequestProfile]] Notice. Default ON: the
   * tokenizer pass is fast (jtokkit milliseconds even on 25K-token
   * requests per Phase 0 measurements) and the data is what apps
   * surface to drive their always-visible context-utilisation gauge.
   *
   * Apps that don't need the data override to false to skip the
   * tokenizer pass.
   */
  def profileWireRequests: Boolean = true

  /**
   * Wall-clock budget (ms) the framework allows a streaming provider
   * call to spend emitting only keepalive / non-meaningful chunks
   * before the wire layer raises a typed transient
   * [[sigil.provider.ProviderStreamException]] (`errorType =
   * upstream_silent`). The retry classifier promotes the typed
   * exception to `Retry` so the framework's transient-retry wrapper
   * re-attempts the call. Set to `0` to disable.
   *
   * Default `60_000` (60 seconds). Tightening risks false-firing on
   * legitimately slow first-token paths (reasoning models warming
   * up); loosening trades early failure detection for keeping the
   * user waiting longer. The check fires lazily on each incoming
   * chunk (no timer thread) — so it actually triggers on the NEXT
   * keepalive after the threshold rather than precisely at the
   * threshold.
   */
  def streamingSilenceTimeoutMs: Long = 60000L

  /**
   * Sigil #258 — streaming-silence budget (ms) applied while a stream
   * has NOT yet produced any meaningful content: a "dead on arrival"
   * upstream that emitted only keepalive chunks since it opened. A
   * dead upstream is obvious well before the full
   * [[streamingSilenceTimeoutMs]], so this shorter budget abandons it
   * fast and lets the framework's transient-retry path try a fresh
   * connection (OpenRouter frequently re-routes to a healthy upstream
   * on a retry). Once a stream has produced meaningful content — text,
   * a tool call, OR reasoning — the full `streamingSilenceTimeoutMs`
   * applies instead: a stall after committed work is not retried
   * aggressively.
   *
   * Default `20_000` (20 s). `0` disables the dead-on-arrival budget
   * (the full `streamingSilenceTimeoutMs` then applies throughout).
   * The master switch is `streamingSilenceTimeoutMs` — setting THAT
   * to `0` turns off silence detection entirely, this budget included.
   */
  def streamingDeadOnArrivalTimeoutMs: Long = 20000L

  /**
   * Threshold at which the curator emits
   * [[sigil.signal.PinnedMemoryBudgetWarning]] — pinned memories +
   * static system-prompt overhead occupying more than this fraction of
   * the model's context window trips the warning. Apps subscribed to
   * `signals` filtered to `PinnedMemoryBudgetWarning` render a UI
   * banner; the curator also injects a `_budgetWarning` entry into
   * `TurnInput.extraContext` so the agent reads it on the next turn.
   *
   * Soft signal — write operations never fail because of this. Apps
   * that want hard rejection wire their own pre-write check using
   * [[sigil.conversation.CoreContextValidator]] and reject in their
   * own flow.
   *
   * Apps with rich compliance / persona pin sets (regulated industries)
   * loosen (`0.40`+); apps with sparse pinning tighten (`0.15`).
   */
  def pinnedShareLimit: Double = 0.25

  /** Backwards-compatible alias for [[pinnedShareLimit]]. New code
    * uses `pinnedShareLimit`; this remains so existing callers
    * compile. */
  final def coreContextShareLimit: Double = pinnedShareLimit

  /**
   * Sigil #283 — multiplier applied to a model's
   * [[sigil.db.Model.inputTokensPerMinute]] when computing the
   * per-request input-token ceiling the pre-flight rate-limit guard
   * enforces. A request whose estimated input-token count exceeds
   * `model.inputTokensPerMinute * rateLimitSafetyMargin` is shed by
   * the provider's emergency shed; if it still doesn't fit, the
   * framework throws
   * [[sigil.provider.RequestExceedsRateLimitException]] and skips the
   * 429 retry loop (retrying a request larger than the per-minute
   * budget can't succeed against the same ceiling).
   *
   * Default `0.85` — leaves 15% of the per-minute budget for
   * concurrent calls landing within the same window. Apps with a
   * single-tenant key and slow user cadence can loosen toward `1.0`;
   * apps with bursty concurrent agent fan-out tighten toward `0.5`.
   * Models with `inputTokensPerMinute = None` skip the guard entirely
   * regardless of this value.
   */
  def rateLimitSafetyMargin: Double = 0.85

  /**
   * Sigil #301 — multiplier applied to a model's
   * [[sigil.db.Model.contextLength]] when computing the per-request
   * input-token ceiling the pre-flight context-window guard enforces.
   *
   * Mirrors [[rateLimitSafetyMargin]] for the context-window axis.
   * Absorbs the [[sigil.provider.Provider.estimateRequest]] estimator's
   * documented ~7-15% gap between piecewise-summed estimate and
   * wire-rendered payload, so requests that estimate just under
   * `Model.contextLength` but render just over don't reach the
   * provider's HTTP 400 path. The provider's emergency shed runs
   * against this tightened limit; if shedding still can't fit,
   * [[sigil.provider.RequestOverBudgetException]] fires.
   *
   * Default `0.92` — leaves 8% of the model's window for the
   * estimator gap (rounded conservatively from the documented 7%
   * lower bound). Providers that override `estimateRequest` with an
   * exact backend tokenizer (LlamaCpp, etc.) can loosen toward `1.0`;
   * estimator-only paths should stay at the default or tighten
   * further.
   */
  def contextLengthSafetyMargin: Double = 0.92

  /**
   * Sigil #285 — intra-turn compactor consulted between iterations
   * of [[runAgentLoop]]. When the compactor's [[IntraTurnCompactor.shouldCompact]]
   * fires AND [[IntraTurnCompactor.selectFoldable]] returns a non-
   * empty list, the framework runs [[MemoryContextCompressor.compressCovering]]
   * on the selected frames and persists a [[ContextSummary]] tagged
   * with their event ids. The curator filters those events out of
   * subsequent turns' frames so the agent sees the summary text in
   * their place.
   *
   * Default [[StandardIntraTurnCompactor]] — fires on size pressure
   * (estimated tokens >= [[compressionTriggerTokens]]) OR after a
   * standard-role `respond` Message. Apps with app-specific
   * sub-task-closed signals (terminal tools that mark a unit of
   * work done) supply a custom [[StandardIntraTurnCompactor]] with
   * `terminalTools` populated, or implement [[IntraTurnCompactor]]
   * directly. */
  def intraTurnCompactor: _root_.sigil.conversation.compression.IntraTurnCompactor =
    _root_.sigil.conversation.compression.StandardIntraTurnCompactor(
      invariants = compactionInvariants
    )

  /**
   * Typed predicates that identify events whose ids MUST survive a
   * compaction or shed pass. Consumed by [[intraTurnCompactor]] and the
   * curator's frame-shed stage; the union of every returned id set is
   * the protected ground truth and shedders operate on the complement.
   *
   * Default set covers the framework's structural protections:
   *   - [[sigil.conversation.compression.CompactionInvariant.CurrentUserTaskMessage]]
   *   - [[sigil.conversation.compression.CompactionInvariant.CurrentAgentClaimAnchor]]
   *   - [[sigil.conversation.compression.CompactionInvariant.PairedToolResult]]
   *
   * Apps override to add app-specific protections (e.g. "never fold
   * the most-recent `respond_card` while the thread render is live")
   * or to drop a default for a custom shedder shape. */
  def compactionInvariants: List[_root_.sigil.conversation.compression.CompactionInvariant] =
    _root_.sigil.conversation.compression.CompactionInvariant.standard

  /** Sigil #285 — compressor invoked by the framework when the
    * [[intraTurnCompactor]] decides to fold this iteration's eligible
    * events. Default is a fresh [[MemoryContextCompressor]] with the
    * standard prompts and extract-disabled (the mid-loop call skips
    * memory extraction to keep the iteration boundary fast — apps
    * that want extraction at this boundary supply a compressor with
    * `extractFacts = true` or wire their own
    * [[sigil.conversation.compression.IntraTurnCompactor]] that
    * pre-runs extraction.
    *
    * Distinct from any compressor wired into the standard curator
    * — the curator's compressor runs at user-turn boundaries; this
    * one runs at iteration boundaries within a single user turn. */
  def intraTurnCompressor: _root_.sigil.conversation.compression.MemoryContextCompressor =
    _root_.sigil.conversation.compression.MemoryContextCompressor(extractFacts = false)

  /**
   * Sigil #286 — when `true`, the framework narrows the per-iteration
   * tool roster to (recently-used tools from the rolling window
   * [[recentToolInvocationsLimit]] ∪ framework essentials ∪
   * `find_capability` ∪ suggested ∪ [[ToolPolicy.Active]] overlays)
   * instead of shipping every tool the agent's [[ToolPolicy]] declares.
   * Reduces the fixed per-request cost of tool-schema bytes from
   * "scales with total app tool count" to "scales with what the agent
   * is currently doing."
   *
   * Safety gate: narrowing only kicks in when `find_capability` is
   * in the effective roster. Without that recovery path, an agent
   * that needs a tool not in the narrowed roster has no way to
   * recover; the framework refuses to narrow in that configuration.
   *
   * First-iteration safety: when the projection's
   * `recentToolInvocations` is empty (start of conversation, fresh
   * agent, just cleared), narrowing skips and the full baseline ships
   * — the agent gets a wide view to start, then narrowing kicks in
   * once usage data accumulates.
   *
   * Default `false` (opt-in). Apps with > 20 tools and
   * `find_capability` in their policy turn this on to cut typical
   * per-request schema bytes ~60-80%; apps with small tool rosters
   * (< 10) or apps that don't surface `find_capability` leave it off.
   */
  def narrowRosterByRecentUse: Boolean = false

  /** Sigil #285 — per-iteration cost threshold above which the
    * intra-turn compactor considers folding worthwhile. Default =
    * `0.6 × min(contextLength, inputTokensPerMinute × safetyMargin)`,
    * leaving 40% headroom for the un-shed sections (system prompt,
    * tool roster, the kept-recent events).
    *
    * Models with no `inputTokensPerMinute` configured fall through
    * to `0.6 × contextLength`. Models with no registry record at
    * all return `Long.MaxValue` (no threshold — only natural-boundary
    * triggers will fire). Apps override for stricter / looser
    * folding cadence. */
  def compressionTriggerTokens(modelId: Id[Model]): Long = {
    val model = cache.find(modelId)
    val ctxBound = model.map(_.contextLength).getOrElse(Long.MaxValue)
    val rateBound = model.flatMap(_.inputTokensPerMinute) match {
      case Some(ipm) => (ipm * rateLimitSafetyMargin).toLong
      case None      => Long.MaxValue
    }
    val effective = math.min(ctxBound, rateBound)
    if (effective == Long.MaxValue) Long.MaxValue
    else math.max(1L, (effective * 0.6).toLong)
  }

  // -- tool catalog --

  /**
   * Static tool singletons synced into [[sigil.db.SigilDB.tools]] on
   * every startup by [[sigil.tool.StaticToolSyncUpgrade]] and registered
   * into the polymorphic `Tool` RW via `RW.static`.
   *
   * Defaults to [[sigil.tool.core.CoreTools.all]] so the framework
   * essentials (`respond`, `cancel`, `find_capability`) are always
   * resolvable by name. Apps with multiple
   * [[sigil.provider.Mode]]s add `ChangeModeTool` themselves; it's
   * shipped in core but not auto-registered, since single-mode apps
   * don't need it. Apps add their
   * own static tools by overriding and concatenating:
   * {{{
   *   override def staticTools: List[Tool] = super.staticTools ++ List(MyTool, OtherTool)
   * }}}
   *
   * NOTE: this method is invoked MORE THAN ONCE during startup —
   * once for input-RW gathering and again when registering the
   * polymorphic `Tool` RW. Any value an override constructs inline
   * gets re-built each call, so tools that hold mutable state
   * (e.g. [[sigil.tool.process.ProcessRegistry]]) must be hoisted
   * to a `lazy val` (or `val`) on the Sigil subclass and referenced
   * from the override:
   * {{{
   *   private lazy val processRegistry = new ProcessRegistry()
   *   override def staticTools: List[Tool] =
   *     super.staticTools ++ AllShippedTools(fs, MySpace, Some(processRegistry))
   * }}}
   * Otherwise the second invocation hands tools a fresh registry
   * and handles minted via the first call's tools become unfindable.
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
   * Apps' authoring shape for [[sigil.skill.Skill]] singletons that
   * should always be present in the DB. Mirrors [[staticTools]]:
   * synced into [[sigil.db.SigilDB.skills]] every startup by
   * [[sigil.skill.StaticSkillSyncUpgrade]] and surfaced through
   * `find_capability` once the agent's request matches.
   *
   * {{{
   *   override def staticSkills: List[Skill] = super.staticSkills ++ List(MySkill)
   * }}}
   */
  def staticSkills: List[sigil.skill.Skill] = Nil

  /**
   * App-provided `Skill` subtypes for runtime instance creation —
   * mirrors [[toolRegistrations]] but for skills. Apps building agent
   * flows that author skills at runtime register their case-class RWs
   * here so the polymorphic `Skill` RW can round-trip records.
   */
  def skillRegistrations: List[RW[? <: sigil.skill.Skill]] = Nil

  /**
   * App-provided [[sigil.tool.ToolInput]] RWs. Registered into the
   * `ToolInput` poly at init so providers can deserialize tool-call
   * arguments. `staticTools`' input RWs are auto-derived; this list
   * adds inputs for runtime-created tools (e.g. `ScriptInput` even if
   * no static `ScriptTool` is registered).
   */
  def toolInputRegistrations: List[RW[? <: sigil.tool.ToolInput]] = Nil

  /**
   * RWs for app-defined [[sigil.tool.ToolOutput]] subtypes, registered
   * alongside the framework's `Pending` / `Progress` cases at
   * [[polymorphicRegistrations]] time. Sigil #265 — `ToolInvoke.output`
   * is a polymorphic field carrying the typed result payload (the
   * pre-#265 model had the result on a separate `ToolResults` event);
   * apps that ship concrete `ToolOutput` subtypes (`TextToolOutput`,
   * custom result types) add their `RW`s here so persistence and the
   * wire round-trip cleanly.
   *
   * Empty by default — the framework's `Pending` / `Progress` cover
   * the generic in-flight + progress-reporting surface; apps that ship
   * concrete output types (`TextToolOutput`, etc.) add their RWs here.
   */
  def toolOutputRegistrations: List[RW[? <: sigil.tool.ToolOutput]] = Nil

  /**
   * Capability-discovery finder. Default queries [[sigil.db.SigilDB.tools]]
   * via [[sigil.tool.DbToolFinder]] — apps override only when they
   * need a custom finder (marketplace union, in-memory test catalog,
   * etc.).
   */
  def findTools: sigil.tool.ToolFinder = defaultFindTools

  /** Skill discovery finder. Default queries [[sigil.db.SigilDB.skills]]
    * via [[sigil.skill.DbSkillFinder]] (BM25 over `searchText`,
    * mode-scoped post-filter). Apps override for custom skill catalogs. */
  def findSkills(request: sigil.tool.DiscoveryRequest): rapid.Task[List[sigil.skill.Skill]] =
    sigil.skill.DbSkillFinder(this).apply(request)

  /** Maximum number of memory matches surfaced by [[findCapabilitiesMemories]].
    * Memory catalogs grow large; an aggressive cap keeps `find_capability`
    * results focused. */
  def findCapabilitiesMemoriesMaxResults: Int = 10

  /** Memory discovery for `find_capability`. BM25 search over the
    * [[sigil.conversation.ContextMemory]] `searchText` index, post-
    * filtered by space affinity (`spaceId == GlobalSpace` OR caller
    * has access). Returns the top
    * [[findCapabilitiesMemoriesMaxResults]] hits, each as a
    * (memory, BM25 score) pair. Apps override for vector / hybrid
    * scoring or alternate filters. */
  def findCapabilitiesMemories(request: sigil.tool.DiscoveryRequest): rapid.Task[List[(sigil.conversation.ContextMemory, Double)]] = {
    import lightdb.Sort
    import lightdb.filter.*
    val tokens = request.keywords.toLowerCase.split("\\s+").filter(_.nonEmpty).toList
    if (tokens.isEmpty) Task.pure(Nil)
    else withDB(_.memories.transaction { tx =>
      tx.query
        .filter { _ =>
          val keywordClauses = tokens.map { kw =>
            FilterClause(ContextMemory.searchText.exactly(kw), Condition.Should, None)
          }
          Filter.Multi(minShould = 1, filters = keywordClauses)
        }
        .scored
        .sort(Sort.BestMatch())
        .limit(findCapabilitiesMemoriesMaxResults * 2)
        .toList
    }).map { memories =>
      memories
        .filter(m => m.spaceId == GlobalSpace || request.callerSpaces.contains(m.spaceId))
        .take(findCapabilitiesMemoriesMaxResults)
        // BM25 score is already implicit in the order; surface a simple
        // descending integer so memory matches mix sensibly with tool /
        // skill scores in the merged result list.
        .zipWithIndex
        .map { case (m, i) => m -> (findCapabilitiesMemoriesMaxResults - i).toDouble }
    }
  }

  /**
   * Unified discovery across every category of capability the
   * framework surfaces (tools, modes, skills, memories). Bug #66.
   *
   * Default composition:
   *   - Calls [[findTools]] to gather matching tools, wraps each as a
   *     `CapabilityMatch(_, _, Tool, _, Ready)`.
   *   - Calls [[findModes]] to gather matching modes (excluding the
   *     currently-active mode — switching to the mode you're already
   *     in is a no-op), wraps each with a `RequiresSetup(change_mode("…"))`
   *     hint so the agent has the actionable next call inline.
   *   - Calls [[findSkills]] to gather matching skills available in
   *     the current mode, wraps each with a
   *     `RequiresSetup(activate_skill("…"))` hint.
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
    val activeChainsTask: rapid.Task[Set[String]] = request.conversationId match {
      case Some(convId) => activeToolchains(convId)
      case None         => rapid.Task.pure(Set.empty[String])
    }
    // Bug #97 — fold conversation overlay policies into the discovery
    // policy filter. A tool either has to pass the active mode's
    // policy OR be permitted by some overlay's policy (e.g. an
    // `Active(metals/lsp/bsp tool names)` overlay installed by
    // `start_metals`).
    val overlayPoliciesTask: rapid.Task[List[ToolPolicy]] = request.conversationId match {
      case Some(convId) => conversationToolOverlays(convId).map(_.map(_.policy))
      case None         => rapid.Task.pure(Nil)
    }
    for {
      rawTools         <- findTools(request)
      overlayPolicies  <- overlayPoliciesTask
      tools             = rawTools
        .filter { t =>
          sigil.tool.DiscoveryFilter.passesPolicy(t, request.mode.tools) ||
            overlayPolicies.exists(p => sigil.tool.DiscoveryFilter.passesPolicy(t, p))
        }
        .filter(t => !t.requiresAccessibleSpaces || request.callerSpaces.nonEmpty)
      activeChains <- activeChainsTask
      modes    <- findModes(request)
      skills   <- findSkills(request)
      memories <- findCapabilitiesMemories(request)
    } yield {
      val toolMatches = tools.map { t =>
        // Bug #90 — score tools on the same absolute scale as modes
        // (DiscoveryFilter.score: 10 exact-name, 8 curated-keyword,
        // 6 name-part, 5 substring + 2 desc-substring per query
        // term). The previous position-derived score (tools.size − i)
        // capped at maxResults and could not compete with mode
        // scores, which routinely reach 15-40. Result: tools were
        // sorted below modes for any query that matched a mode's
        // keywords, even when a tool was the actual best answer.
        // Bug #85 — toolchain boost: language-runtime-backed tools
        // (lsp_*, bsp_* when Metals is running) outrank generic
        // verbs for inspection-shaped queries.
        // Bug #86 — generic primitives (grep, glob, bash, …) opt
        // into a penalty that drops them below domain-specific
        // tools. They stay findable when no domain-specific tool
        // matches; they just stop winning ties.
        val baseScore   = sigil.tool.DiscoveryFilter.score(t, request.keywords)
        val boost       = if (t.toolchain.exists(activeChains.contains)) toolchainBoost else 0.0
        val penalty     = if (t.preferIfNoBetter) preferIfNoBetterPenalty else 0.0
        // Exact-name match outranks every other signal so a literal
        // tool-name query always returns that tool first.
        val nameMatch   = if (t.name.value.equalsIgnoreCase(request.keywords.trim)) exactNameBoost else 0.0
        CapabilityMatch(
          name = t.name.value,
          description = t.description,
          capabilityType = CapabilityType.Tool,
          score = baseScore + boost - penalty + nameMatch,
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
      val skillMatches = skills.zipWithIndex.map { case (s, i) =>
        CapabilityMatch(
          name = s.name,
          description = s.description,
          capabilityType = CapabilityType.Skill,
          score = (skills.size - i).toDouble,
          status = CapabilityStatus.RequiresSetup(s"""activate_skill("${s.name}")""")
        )
      }
      val memoryMatches = memories.map { case (m, score) =>
        // Memory matches surface key + summary only — the agent calls
        // `lookup(capabilityType=Memory, name=key)` to pull the full
        // fact when it decides the memory is worth the tokens.
        val displayName = m.key.getOrElse(m._id.value)
        val displaySummary = m.summary
        CapabilityMatch(
          name = displayName,
          description = displaySummary,
          capabilityType = CapabilityType.Memory,
          score = score,
          status = CapabilityStatus.RequiresSetup(s"""lookup(capabilityType="Memory", name="$displayName")""")
        )
      }
      (toolMatches ++ modeMatches ++ skillMatches ++ memoryMatches).sortBy(-_.score)
    }
  }

  /** Maximum number of modes [[findModes]] returns. Mode catalogs are
    * typically small (3-10), so a tight cap prevents `find_capability`
    * from drowning the agent in suggestions. Apps with broader mode
    * spaces override. */
  def findModesMaxResults: Int = 5

  /**
   * Score-and-filter the registered modes against the
   * [[sigil.tool.DiscoveryRequest]]'s keyword query. Default: lexical
   * match against `name + description + skill.content + keywords`
   * (case-insensitive). Curated [[sigil.provider.Mode.keywords]] are
   * the highest-weighted signal so authors can steer matching for
   * terms not in the public description. Excludes the currently-active
   * mode (no-op switch); returns the top [[findModesMaxResults]]
   * paired with relevance scores.
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
        val curatedKeywords = m.keywords.map(_.toLowerCase)
        val haystack = (
          m.name + " " +
          m.description + " " +
          m.skill.map(_.content).getOrElse("")
        ).toLowerCase
        // Score per keyword: take the strongest signal. Curated keyword
        // set match (8) beats exact-word match in haystack (5) beats
        // substring (2). Sum across input keywords.
        // `haystack`'s word set is loop-invariant — split once, not per keyword.
        val words = haystack.split("\\W+").toSet
        val score = needles.foldLeft(0.0) { (acc, kw) =>
          val curated = if (curatedKeywords.contains(kw)) 8.0 else 0.0
          val exact = if (words.contains(kw)) 5.0 else 0.0
          val sub   = if (haystack.contains(kw)) 2.0 else 0.0
          acc + math.max(curated, math.max(exact, sub))
        }
        m -> score
      }
      .filter(_._2 > 0.0)
      .toList
      .sortBy(-_._2)
      .take(findModesMaxResults)
  }

  /** The set of [[SpaceId]]s the caller chain is authorized to see in
    * the context of `conversationId` — used to filter `find_capability`
    * results, scope memory retrieval, gate `lookup`, etc. Apps that
    * need per-conversation space scoping (per-workspace memory pools,
    * per-tenant isolation in multi-tenant apps, per-topic spaces)
    * override THIS method and use `conversationId` to select the right
    * scope.
    *
    * Default delegates to the conversation-agnostic
    * [[accessibleSpaces(chain)]] for backward compatibility — apps
    * that previously overrode the single-arg method continue to work.
    *
    * Bug #77: prior to this signature, the conversation context wasn't
    * available at access-decision time, so apps either side-stored
    * "active workspace per chain participant" (brittle — one
    * participant has many concurrent conversations) or returned every
    * conceivable space (over-shared across conversations). Both
    * workarounds are wrong; the framework should let the app decide
    * based on the actual conversation that's running.
    */
  def accessibleSpaces(chain: List[ParticipantId],
                       conversationId: Id[Conversation]): Task[Set[SpaceId]] =
    accessibleSpaces(chain)

  /** Conversation-agnostic access set — used by admin paths that
    * don't run inside a conversation (storedFile lookups, provider-
    * config reads, viewer-scoped tool listings). Apps without
    * per-conversation scoping override this single hook and the
    * two-arg [[accessibleSpaces(chain, conversationId)]] inherits
    * the same set for every conversation by default. Default empty
    * (fail-closed). */
  def accessibleSpaces(chain: List[ParticipantId]): Task[Set[SpaceId]] =
    Task.pure(Set.empty)

  /** Toolchains attached to `conversationId` — when a tool's
    * [[sigil.tool.Tool.toolchain]] matches a name in this set,
    * [[findCapabilities]]'s ranker adds [[toolchainBoost]] to its
    * score. Sigil bug #85.
    *
    * Apps register active toolchains as conversations attach
    * runtimes:
    *   - `MetalsSigil` returns `Set("lsp", "bsp")` when Metals is
    *     running for the conversation's workspace.
    *   - Apps wiring TypeScript LSP would return `Set("ts-server")`
    *     for conversations bound to a JS/TS workspace.
    *   - Apps not exposing language runtimes leave the default
    *     `Set.empty` and tools rank purely by keyword score.
    *
    * Default empty — no contextual boost without app opt-in. */
  def activeToolchains(conversationId: Id[Conversation]): Task[Set[String]] =
    Task.pure(Set.empty)

  /** Score boost added to a tool's [[findCapabilities]] result when
    * its [[sigil.tool.Tool.toolchain]] is in
    * [[activeToolchains]]. Default `10.0` — large enough to lift
    * LSP/BSP tools above generic verbs (grep, glob, execute_script
    * cluster around 7-10), small enough that a tool with no
    * keyword match doesn't displace a strong direct match. Apps
    * tune by override. Sigil bug #85. */
  def toolchainBoost: Double = 10.0

  /** Score penalty subtracted from a tool's [[findCapabilities]]
    * result when [[sigil.tool.Tool.preferIfNoBetter]] is set.
    * Generic primitives (grep, glob, bash, …) get nudged below
    * domain-specific tools that ranker score them as ties. Default
    * `3.0` — large enough to push grep below LSP for "examine code"
    * queries, small enough that a generic-only match still ranks
    * positive (no domain match → grep is still the top result).
    * Sigil bug #86. */
  def preferIfNoBetterPenalty: Double = 3.0

  /** Score added when a tool's name exactly matches the
    * (case-insensitive) keywords query. Defaults to 100.0 — large
    * enough that an exact-name match always outranks any
    * description-derived ranking, so a query for the literal tool
    * name reliably returns that tool first. */
  def exactNameBoost: Double = 100.0

  /** Persist a user-created tool. Typical call site: an app's agent
    * flow that dynamically generates a `ScriptTool(...)` with the
    * caller's `SpaceId`, then writes it via this helper. Returns the
    * stored tool. */
  def createTool(tool: sigil.tool.Tool): Task[sigil.tool.Tool] =
    withDB(_.tools.transaction(_.upsert(tool)))

  // -- conversation tool overlays (#97) --

  /** Per-conversation [[ToolPolicy]] overlays — additive on top of the
    * mode + role policies already folded into the agent's effective
    * roster. When `start_metals` succeeds, it installs an
    * `Active(metals/lsp/bsp tool names)` overlay so subsequent turns
    * can call those tools directly without a `find_capability`
    * round-trip. Also applied to `findCapabilities` so the same tools
    * remain visible to keyword discovery.
    *
    * Default reads from `db.conversationToolOverlays`. Apps that
    * want transient (non-persisted) overlays override this and the
    * mutation hooks in tandem. */
  def conversationToolOverlays(conversationId: Id[Conversation]): Task[List[sigil.conversation.ConversationToolOverlay]] =
    withDB(_.conversationToolOverlays.transaction { tx =>
      tx.query
        .filter(_ => sigil.conversation.ConversationToolOverlay.conversationId === conversationId)
        .toList
    }).map(_.toList.sortBy(_.installedAt.value))

  /** Install (or upsert) a conversation-scoped tool overlay. Keyed
    * by `(conversationId, source)`; calling twice with the same
    * source replaces the prior policy. */
  def addConversationToolOverlay(overlay: sigil.conversation.ConversationToolOverlay): Task[sigil.conversation.ConversationToolOverlay] = {
    val withId = overlay.copy(_id = sigil.conversation.ConversationToolOverlay.idFor(overlay.conversationId, overlay.source))
    withDB(_.conversationToolOverlays.transaction(_.upsert(withId)))
  }

  /** Remove the overlay installed for `(conversationId, source)`.
    * No-op when nothing matches. */
  def removeConversationToolOverlay(conversationId: Id[Conversation], source: String): Task[Unit] =
    withDB(_.conversationToolOverlays.transaction(_.delete(sigil.conversation.ConversationToolOverlay.idFor(conversationId, source)))).unit

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
                 metadata: Map[String, String] = Map.empty,
                 category: sigil.storage.StoredFileCategory = sigil.storage.StoredFileCategory.UserAttachment,
                 expiresAt: Option[lightdb.time.Timestamp] = None): Task[sigil.storage.StoredFile] = {
    val record = sigil.storage.StoredFile(
      space = space,
      path = "",
      contentType = contentType,
      size = data.length.toLong,
      category = category,
      expiresAt = expiresAt,
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

  /** The model id stamped on the most recent agent [[Message]] in
    * `conversationId`, when one exists. Reads `Message.modelId`
    * (populated by the orchestrator at settle-time from the resolved
    * `ConversationRequest.modelId`), so a mid-conversation pin /
    * strategy swap is reflected here.
    *
    * `None` for fresh conversations and for conversations where the
    * orchestrator hasn't yet stamped any agent Message (e.g. the
    * agent's only emissions so far are tool results). */
  def lastUsedModel(conversationId: Id[Conversation]): Task[Option[Id[Model]]] =
    withDB(_.eventsTransaction(conversationId)(_.list)).map { events =>
      events.iterator
        .collect { case m: sigil.event.Message if m.conversationId == conversationId => m }
        .filter(_.modelId.isDefined)
        .toList
        .sortBy(-_.timestamp.value)
        .headOption
        .flatMap(_.modelId)
    }

  // ---- Bug #128 / #167 — per-message routing state ----
  //
  // Split into two stores with disjoint concerns so mid-turn state
  // changes (pin, unpin, mode switch, etc.) can't shadow each other:
  //
  //   - classifierMemo  : pure memoization of the classifier LLM call.
  //                       Keyed by userMessageId (globally unique).
  //                       Value never mutates after write — same user
  //                       message implies the same classifier output.
  //                       Only purpose: avoid re-running the classifier
  //                       across the iterations of one agent turn.
  //
  //   - perTurnEscalations : per-turn mutable counter. Reset whenever
  //                          the conversation's userMessageId changes.
  //
  // Effective routing (pin vs classifier vs escalation) is computed
  // fresh on every `classifyForRoute` call from current conversation
  // state, so a mid-turn pin / unpin surfaces on the next iteration
  // without needing any cache invalidation logic.

  private val classifierMemo: java.util.concurrent.ConcurrentHashMap[Id[Event], (WorkType, Complexity)] =
    new java.util.concurrent.ConcurrentHashMap()

  private val perTurnEscalations: java.util.concurrent.ConcurrentHashMap[Id[Conversation], (Id[Event], Int)] =
    new java.util.concurrent.ConcurrentHashMap()

  /** When `true`, the iteration-cap soft-stop (sigil bug #125) auto-
    * bumps complexity one tier up for the forced-synthesis turn —
    * giving the recovery attempt the strongest available reasoning
    * in the chain. Logged via scribe. Default `false` to preserve
    * cost ceilings for apps that don't want auto-escalation. */
  def escalateOnCapHit: Boolean = false

  /** Sigil bug #287 — when `true` (default), the orchestrator's
    * duplicate-call cap ([[maxIdenticalToolCallsInWindow]]) bumps the
    * conversation's complexity tier one step on each cap trip, so the
    * next iteration routes to a more capable model that can read the
    * Failure and pick a different next move. Detection alone isn't
    * enough on small models — the same model that produced the
    * duplicate keeps producing it; escalation is what breaks the
    * loop. Apps that pin a single tier and don't want auto-bump set
    * to `false`; the cap still fires (Failure Message + refusal) but
    * stays at the current tier. */
  def escalateOnDuplicateCallCap: Boolean = true

  /** Classify the user's latest message for this conversation,
    * caching the result for the lifetime of that user turn. Returns
    * `(WorkType, Complexity)` — the routing key the framework
    * matches against [[ModelCandidate.supportedComplexity]] when
    * picking a candidate.
    *
    * Skip gates (cheapest-first):
    *   - Strategy didn't supply [[ProviderStrategy.inferWorkType]] /
    *     [[ProviderStrategy.inferComplexity]] → use `defaultWorkType`
    *     / `Complexity.Medium`.
    *   - Strategy's [[ProviderStrategy.workTypeMatters]] /
    *     [[ProviderStrategy.complexityMatters]] is false → skip the
    *     classifier; outcome can't change the candidate.
    *
    * On classifier failure (network, unparsable response, etc.) the
    * routing falls back to defaults rather than blocking the turn.
    *
    * Memo is keyed by `userMessageId` — the classifier output is a
    * function of the user's message text and never changes within
    * one turn. Pin / unpin / escalation read fresh from the
    * conversation + per-turn escalation counter, so mid-turn tier
    * changes surface on the next call without any cache machinery
    * to coordinate.
    */
  def classifyForRoute(strategy: ProviderStrategy,
                       defaultWorkType: WorkType,
                       conversation: sigil.conversation.Conversation,
                       userMessage: Option[sigil.event.Message],
                       turnContext: sigil.TurnContext): Task[(WorkType, Complexity)] = {
    val userMsg = userMessage
    val userText = userMsg.flatMap(_.content.collect {
      case ResponseContent.Text(t)     => t
      case ResponseContent.Markdown(t) => t
    }.headOption).getOrElse("")
    val msgId = userMsg.map(_._id).getOrElse(Event.id())

    // Reset the per-turn escalation counter when the user turn
    // advances. Done eagerly so `requestEscalation` and the
    // RouteResolved escalation read both see the right turn-scope.
    perTurnEscalations.compute(conversation._id, (_, existing) =>
      if (existing == null || existing._1 != msgId) (msgId, 0) else existing
    )

    // Memo: classifier output for this user message. Pure function
    // of (userText), so safe to memoize across the iterations of
    // this turn. Compute once, re-use.
    val memoed = Option(classifierMemo.get(msgId))
    val classifierTask: Task[(WorkType, Complexity)] = memoed match {
      case Some(v) => Task.pure(v)
      case None    =>
        val wtTask: Task[WorkType] =
          if (strategy.shouldClassifyWorkType && userText.nonEmpty)
            strategy.inferWorkType.get.apply(userText, turnContext)
              .handleError { e =>
                scribe.warn(s"inferWorkType failed (${e.getClass.getSimpleName}: ${e.getMessage}) — falling back to ${defaultWorkType}")
                Task.pure(defaultWorkType)
              }
          else Task.pure(defaultWorkType)
        wtTask.flatMap { wt =>
          val cxTask: Task[Complexity] =
            if (strategy.shouldClassifyComplexity(wt) && userText.nonEmpty)
              strategy.inferComplexity.get.apply(userText, turnContext)
                .handleError { e =>
                  scribe.warn(s"inferComplexity failed (${e.getClass.getSimpleName}: ${e.getMessage}) — falling back to ${strategy.defaultComplexity}")
                  Task.pure(strategy.defaultComplexity)
                }
            else Task.pure(strategy.defaultComplexity)
          cxTask.map { cx =>
            val v = (wt, cx)
            classifierMemo.putIfAbsent(msgId, v)
            v
          }
        }
    }

    // Effective routing: fresh derivation from current state.
    // Pin wins over inference (bug #152). Escalations apply on
    // top of the classifier complexity. Pin and escalations are
    // independent — when a pin is in effect, escalations are
    // intentionally ignored so the pinned tier stays binding for
    // the duration of the turn.
    classifierTask.map { case (wt, classifierCx) =>
      val effectiveCx = conversation.pinnedComplexity match {
        case Some(pinned) => pinned
        case None =>
          val escalations = Option(perTurnEscalations.get(conversation._id)).map(_._2).getOrElse(0)
          (1 to escalations).foldLeft(classifierCx)((acc, _) => Complexity.bumpUp(acc))
      }
      (wt, effectiveCx)
    }
  }

  /** Bump the per-turn complexity tier one step up for the current
    * user turn — what [[sigil.tool.core.RequestEscalationTool]] calls
    * when the agent realizes mid-turn that the task is harder than
    * the classifier's initial assessment. Returns `(newTier, bumped)`:
    *
    *   - `bumped = true` means the tier actually moved (Low → Medium
    *     or Medium → High);
    *   - `bumped = false` means we were already at High (clamp) or
    *     no classification has been done yet (no message id to
    *     attach the escalation to).
    *
    * The escalation count is held in [[perTurnEscalations]] keyed
    * by conversation; subsequent calls to [[classifyForRoute]] apply
    * the count on top of the classifier's raw complexity to produce
    * the effective tier. */
  def requestEscalation(conversationId: Id[Conversation], reason: String): Task[(Complexity, Boolean)] = Task {
    val state = perTurnEscalations.get(conversationId)
    if (state == null) (Complexity.Medium, false)
    else {
      val (msgId, count) = state
      // Compute the would-be effective tier from the memo + new count.
      // The memo's complexity may not exist yet if shouldClassifyComplexity
      // is false (e.g., the strategy doesn't have a classifier); in that
      // case bump from defaultComplexity-equivalent Medium as a safe baseline.
      val classifierCx = Option(classifierMemo.get(msgId)).map(_._2).getOrElse(Complexity.Medium)
      val currentEffective = (1 to count).foldLeft(classifierCx)((acc, _) => Complexity.bumpUp(acc))
      val nextEffective = Complexity.bumpUp(currentEffective)
      val bumped = nextEffective != currentEffective
      if (bumped) perTurnEscalations.put(conversationId, (msgId, count + 1))
      scribe.info(s"requestEscalation conv=${conversationId.value} from=$currentEffective to=$nextEffective bumped=$bumped reason=$reason")
      (nextEffective, bumped)
    }
  }

  /** Internal hook for the cap-hit forced-synthesis path. Bumps the
    * cached tier when [[escalateOnCapHit]] is true; no-op otherwise.
    * The bumped tier flows through the next candidate-resolution
    * call (the forced-synthesis turn) so the recovery attempt runs
    * against a more capable model. */
  protected[sigil] def escalateForCapHit(conversationId: Id[Conversation]): Task[Unit] =
    if (!escalateOnCapHit) Task.unit
    else requestEscalation(conversationId, reason = "iteration-cap forced synthesis").map(_ => ())

  /** Emit a [[sigil.event.RouteResolved]] event capturing the
    * per-turn routing decision. Includes the classifier output (or
    * `None` when the framework defaulted), the candidate chain
    * considered, which candidate won, and per-candidate skip
    * reasons. Best-effort: emission failures are swallowed so a
    * forensic-channel hiccup never blocks the turn itself. */
  private final def publishRouteResolved(agentId: ParticipantId,
                                         conversation: Conversation,
                                         userMessage: Option[sigil.event.Message],
                                         strategyOpt: Option[ProviderStrategy],
                                         inferredWorkType: WorkType,
                                         complexity: Complexity,
                                         candidateChain: List[Id[Model]],
                                         chosenModelId: Id[Model],
                                         skipReasons: Map[Id[Model], String]): Task[Unit] = {
    val classifierFired = strategyOpt.exists(_.shouldClassifyWorkType) ||
      strategyOpt.exists(_.shouldClassifyComplexity(inferredWorkType))
    val event = sigil.event.RouteResolved(
      participantId       = agentId,
      conversationId      = conversation._id,
      topicId             = conversation.currentTopicId,
      userMessageId       = userMessage.map(_._id),
      inferredWorkType    = if (strategyOpt.exists(_.shouldClassifyWorkType)) Some(inferredWorkType) else None,
      inferredComplexity  = if (strategyOpt.exists(_.shouldClassifyComplexity(inferredWorkType))) Some(complexity) else None,
      candidateChain      = candidateChain,
      chosenModelId       = chosenModelId,
      skipReasons         = skipReasons,
      classifierLatencyMs = None,
      escalationCount     = Option(perTurnEscalations.get(conversation._id)).map(_._2).getOrElse(0)
    )
    publish(event).map(_ => ()).handleError(_ => Task.unit)
  }

  /** Resolve the model id this conversation would dispatch to on the
    * next turn — the same lookup chain `runAgentTurn` uses, exposed as
    * a read-only helper for introspection (e.g. [[CurrentModelTool]]).
    * Order: [[sigil.conversation.Conversation.pinnedModelId]] →
    * [[sigil.provider.Mode.strategyId]] →
    * [[resolveProviderStrategy]] for the conversation's space → first
    * candidate for the conversation's effective work type. Returns
    * `None` when no resolution layer applies (a deeply un-configured
    * Sigil where dispatch would itself error). */
  def currentModelFor(conversation: sigil.conversation.Conversation): Task[Option[Id[Model]]] = {
    val workType = conversation.currentMode.workType.getOrElse(sigil.provider.ConversationWork)
    conversation.pinnedModelId match {
      case Some(pinned) => Task.pure(Some(pinned))
      case None =>
        conversation.currentMode.strategyId match {
          case Some(modeStrategyId) =>
            withDB(_.providerStrategies.transaction(_.get(modeStrategyId)))
              .map(_.map(materializeStrategy).flatMap(_.availableCandidates(workType).headOption.map(_.modelId)))
          case None =>
            resolveProviderStrategy(conversation.space)
              .map(_.flatMap(_.availableCandidates(workType).headOption.map(_.modelId)))
        }
    }
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

  /** Pick a model for `workType`, scoped to `chain`. Default impl walks
    * the chain's accessible spaces, resolves each space's
    * [[sigil.provider.ProviderStrategy]], and returns the first
    * available candidate for `workType` whose `Model.contextLength`
    * can accommodate `estimatedInputTokens + reservedOutputTokens`.
    * Falls back to `fallback` when no strategy applies or no candidate
    * fits.
    *
    * Bug #26 — used by the framework's compressor to route
    * summarization through a `SummarizationWork`-tier model rather
    * than inheriting the calling agent's modelId.
    *
    * Bug #41 — `estimatedInputTokens` lets callers skip candidates
    * whose context window can't physically fit the request, so a
    * cost-aware chain like `[llama (32K), gpt-5.5, claude]` does the
    * right thing automatically: small input → llama; oversized input
    * → fall through to a frontier candidate. `None` (default) keeps
    * the legacy head-first behavior for callers that have no size
    * signal. Candidates whose `Model.contextLength` isn't in the
    * cache are NOT skipped (treated as "size unknown" → keep) so
    * apps with custom-provider models lacking a registered
    * contextLength aren't broken.
    *
    * `reservedOutputTokens` is the budget reserved for the response
    * — added to `estimatedInputTokens` when measuring fit. Default
    * 1024 is enough for typical summary outputs; callers expecting
    * larger responses pass higher.
    *
    * Apps override for custom routing (e.g. cost-aware fallback
    * ordering, sticky model preferences). */
  def routedModelFor(workType: sigil.provider.WorkType,
                     chain: List[ParticipantId],
                     fallback: Id[Model],
                     estimatedInputTokens: Option[Long] = None,
                     reservedOutputTokens: Long = 1024L,
                     // Sigil #289 — optional complexity hint. When set,
                     // candidates whose `supportedComplexity` doesn't
                     // include this tier are filtered out before the
                     // first-fit pick. When None (the default), no
                     // complexity filtering applies (preserves prior
                     // behaviour for every existing caller). The
                     // primary motivator is `delegate_task` letting
                     // the spawning agent express "give the worker a
                     // High-tier model" without having to enumerate a
                     // specific modelId.
                     complexity: Option[sigil.provider.Complexity] = None): Task[Id[Model]] = {
    val convId: Id[Conversation] = sigil.conversation.Conversation.id("__no_conv__")
    val required = estimatedInputTokens.map(_ + reservedOutputTokens)

    def fits(candidate: sigil.provider.ModelCandidate): Boolean = required match {
      case None => true
      case Some(needed) =>
        cache.find(candidate.modelId).map(_.contextLength) match {
          // contextLength unknown → don't filter (custom provider /
          // stale registry). Caller still gets a candidate; if it
          // overflows downstream, the compressor's chunk-and-merge
          // fallback in `compressLarge` handles it.
          case None       => true
          case Some(0L)   => true
          case Some(ctx)  => needed <= ctx
        }
    }

    // #315 — degrade to the nearest available tier AT OR BELOW the
    // requested one (High → Medium → Low), not cratering to the pinned
    // fallback when the exact tier has no candidate. `None` complexity
    // keeps the legacy first-fit behaviour. Down-only by default: a
    // High request never silently escalates to a VeryHigh candidate.
    def pickFrom(avail: List[sigil.provider.ModelCandidate]): Option[Id[Model]] =
      complexity match {
        case None => avail.find(fits).map(_.modelId)
        case Some(requested) =>
          sigil.provider.Complexity.atOrBelow(requested).iterator
            .flatMap(tier => avail.filter(_.supportedComplexity.contains(tier)).find(fits))
            .map(_.modelId)
            .nextOption()
      }

    accessibleSpaces(chain, convId).flatMap { spaces =>
      val ordered = spaces.toList
      def loop(remaining: List[SpaceId]): Task[Option[Id[Model]]] = remaining match {
        case Nil => Task.pure(None)
        case space :: rest =>
          resolveProviderStrategy(space).flatMap {
            case None => loop(rest)
            case Some(strategy) =>
              pickFrom(strategy.availableCandidates(workType)) match {
                case Some(modelId) => Task.pure(Some(modelId))
                case None          => loop(rest)
              }
          }
      }
      loop(ordered).map(_.getOrElse(fallback))
    }
  }

  /** #357 — whether a conversation's
    * [[sigil.conversation.Conversation.pinnedModelId]] also governs the
    * framework's auxiliary LLM calls (topic classifier, memory
    * extractor, progress checkpoint, curate compression).
    *
    * Default `false` (cost-first): a pin governs only the agent's main
    * turn; auxiliary calls route through [[routedModelFor]] to the
    * cheapest viable tier for their work type, independent of the pin —
    * which is usually the right default (classification / extraction /
    * summarization don't need a frontier model). Apps that want a pin to
    * mean "every LLM call in this conversation" override to `true`. */
  def pinCoversAuxiliaryCalls: Boolean = false

  /** #357 — auxiliary-call model resolution. When
    * [[pinCoversAuxiliaryCalls]] is `false` (default) this is exactly
    * [[routedModelFor]] — cost-first routing for `workType`, ignoring any
    * conversation pin. When `true` and the conversation carries a
    * [[sigil.conversation.Conversation.pinnedModelId]], the pin wins
    * (honoring the "pin = every LLM call" contract); otherwise it falls
    * back to `routedModelFor`. The conversation read only happens when
    * the knob is on, so the default path adds no DB cost. */
  def auxModelFor(conversationId: Id[Conversation],
                  workType: sigil.provider.WorkType,
                  chain: List[ParticipantId],
                  fallback: Id[Model],
                  estimatedInputTokens: Option[Long] = None,
                  reservedOutputTokens: Long = 1024L,
                  complexity: Option[sigil.provider.Complexity] = None): Task[Id[Model]] =
    if (!pinCoversAuxiliaryCalls)
      routedModelFor(workType, chain, fallback, estimatedInputTokens, reservedOutputTokens, complexity)
    else
      withDB(_.conversations.transaction(_.get(conversationId))).flatMap {
        case Some(conv) if conv.pinnedModelId.isDefined => Task.pure(conv.pinnedModelId.get)
        case _ => routedModelFor(workType, chain, fallback, estimatedInputTokens, reservedOutputTokens, complexity)
      }

  private final lazy val defaultFindTools: sigil.tool.ToolFinder = {
    val staticInputs = staticTools.map(_.inputRW).distinctBy(_.definition.className)
    val allInputs = (staticInputs ++ toolInputRegistrations).distinctBy(_.definition.className)
    sigil.tool.DbToolFinder(this, allInputs)
  }

  // -- context curation --

  /**
   * Per-turn curator: given the conversation id, target model, and
   * participant chain, produce the [[TurnInput]] the provider will
   * render. Policy lives here — pick which memories / summaries /
   * information to surface, apply app-specific overlays, add extra
   * context, run budget-based shedding, etc.
   *
   * Bug #26 — the curator now sources frames from `db.events` (via
   * [[Event.contextFrame]]) and per-participant projections from
   * `db.participantProjections` directly; the old `ConversationView`
   * has been retired.
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
   * / `change_mode` results.
   */
  def curate(conversationId: Id[Conversation],
             modelId: Id[Model],
             chain: List[ParticipantId]): Task[TurnInput] =
    sigil.conversation.compression.StandardContextCurator(this).curate(conversationId, modelId, chain)

  // -- information lookup --

  /**
   * Resolve an [[Information]] by id. The default reads
   * [[sigil.information.StoredInformation]] records from
   * `SigilDB.storedInformations`; apps with a custom Information
   * catalog override.
   */
  def getInformation(id: Id[Information]): Task[Option[Information]] =
    withDB(_.storedInformations.transaction(_.get(Id[sigil.information.StoredInformation](id.value))))
      .map(_.map(s => s: Information))

  /**
   * Persist an [[Information]] record. The default writes
   * [[sigil.information.StoredInformation]] to
   * `SigilDB.storedInformations`; other subtypes are ignored. Block
   * extraction during compression
   * (see [[sigil.conversation.compression.StandardBlockExtractor]])
   * resolves back through [[getInformation]].
   */
  def putInformation(information: Information): Task[Unit] = information match
    case s: sigil.information.StoredInformation =>
      withDB(_.storedInformations.transaction(_.upsert(s))).unit
    case _ => Task.unit

  /**
   * Bulk variant of [[putInformation]]. The framework's
   * [[sigil.conversation.compression.StandardBlockExtractor]] hands
   * the entire batch in one call so apps backed by transactional
   * stores (LightDB + Lucene, RocksDB, Postgres) can amortise commit
   * / fsync / segment-flush overhead across the whole batch.
   *
   * Default: one LightDB transaction across the whole batch of
   * [[sigil.information.StoredInformation]] records — a bulk import
   * (50K+ events) costs one commit rather than `N`. Other Information
   * subtypes are ignored; apps with a custom catalog override.
   */
  def putInformations(informations: Vector[Information]): Task[Unit] =
    val stored = informations.collect { case s: sigil.information.StoredInformation => s }
    if stored.isEmpty then Task.unit
    else withDB(_.storedInformations.transaction(tx => Task.sequence(stored.toList.map(s => tx.upsert(s))))).unit

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
   *   override protected def modes: List[Mode] = List(MyCodingMode, WorkflowMode)
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
                         suggested: List[sigil.tool.ToolName],
                         overlays: List[ToolPolicy] = Nil,
                         /** Sigil #286 — recently-used tool names lifted
                           * from the participant projection's
                           * `recentToolInvocations`. The caller in
                           * [[defaultProcess]] passes the actual set;
                           * non-conversation callers (e.g. `DelegateTaskTool`'s
                           * up-front roster build) leave it empty (no
                           * narrowing). */
                         recentlyUsedTools: Set[sigil.tool.ToolName] = Set.empty): List[sigil.tool.ToolName] = {
    import sigil.tool.core.{
      ChangeModeTool, FindCapabilityTool, NoResponseTool, RespondTool, RespondOptionsTool
    }
    import sigil.tool.skill.ActivateSkillTool
    // Reply surface: `respond` (markdown + Field-callout + H2-Card +
    // disposition) for telling; `respond_options` (typed) for asking.
    // The standalone `respond_field` / `respond_failure` /
    // `respond_card` tools are opt-in (not essentials) — markdown
    // callouts and disposition cover their cases in `respond`.
    // `no_response` dropped from defaults in sigil bug #156.
    // `cancel` deliberately omitted from both essentials lists — agents
    // reach for it under stress (duplicate-call warning, ambiguous
    // input) and terminate conversations when the right move is
    // `respond` to ask the user. PureDiscovery without an exit hatch
    // relies on the runaway cap as the safety mechanism.
    val fullEssentials = List(
      RespondTool, RespondOptionsTool
    ).map(_.schema.name)
    val pureDiscoveryEssentials = List.empty[sigil.tool.ToolName]

    case class PolicyState(extras: List[sigil.tool.ToolName],
                           includesFindCapability: Boolean,
                           includesBaseline: Boolean,
                           pureDiscovery: Boolean)
    val initial = PolicyState(Nil, includesFindCapability = true, includesBaseline = true, pureDiscovery = false)

    def apply(s: PolicyState, p: ToolPolicy): PolicyState = p match {
      case ToolPolicy.Standard           => s
      case ToolPolicy.None               => s.copy(includesFindCapability = false, includesBaseline = false)
      case ToolPolicy.PureDiscovery      => s.copy(pureDiscovery = true)
      case ToolPolicy.Active(names)      => s.copy(extras = s.extras ++ names)
      // Sigil #262 — same as Active but additionally strips
      // `find_capability` from the roster. Sticky-off semantics in the
      // fold: once any contributor flips includesFindCapability false,
      // later policies don't re-enable it (matching the intent of "this
      // host doesn't want discovery indirection at all").
      case ToolPolicy.ActiveOnly(names)  => s.copy(includesFindCapability = false, extras = s.extras ++ names)
      case ToolPolicy.Discoverable(_)    => s
      case ToolPolicy.Exclusive(names)   => s.copy(includesBaseline = false, extras = s.extras ++ names)
      case ToolPolicy.Scoped(_)          => s
    }

    // Bug #97 — fold conversation overlays last so they're additive
    // on top of the agent + mode policies. `Active(names)` from
    // `start_metals` adds those names; `Exclusive` / `None` from a
    // user-installed overlay can also restrict, mirroring the
    // mode-side semantics.
    val state = overlays.foldLeft(apply(apply(initial, agent.tools), mode.tools))(apply)
    val essentials     = if (state.pureDiscovery) pureDiscoveryEssentials else fullEssentials
    val findCapability = if (state.includesFindCapability) List(FindCapabilityTool.schema.name) else Nil
    val baselineFull   = if (state.includesBaseline) agent.toolNames else Nil
    // Sigil #286 — narrow the app-declared roster to recently-used
    // tools when the framework's narrowing knob is on AND
    // find_capability is in the effective roster (recovery path: an
    // agent that needs a narrowed-out tool calls find_capability and
    // the next turn picks it up via `suggested`). First-iteration
    // safety: empty recentlyUsedTools skips narrowing so the agent
    // sees the full roster on a fresh conversation.
    //
    // Sigil #287 — narrow BOTH `baseline` (from `agent.toolNames`)
    // AND `state.extras` (from `ToolPolicy.Active(names)` /
    // `ToolPolicy.Exclusive(names)` overlays). Consumers that
    // register their full catalog via `Active(...)` or `Exclusive(...)`
    // — rather than `agent.toolNames` — were getting no narrowing
    // because the prior implementation only touched baseline. Both
    // surfaces are app-level "make these tools available"
    // declarations; both should narrow uniformly.
    val (baseline, extras) =
      if (narrowRosterByRecentUse && state.includesFindCapability && recentlyUsedTools.nonEmpty)
        (baselineFull.filter(recentlyUsedTools.contains),
         state.extras.filter(recentlyUsedTools.contains))
      else (baselineFull, state.extras)
    val merged         = (essentials ++ findCapability ++ baseline ++ extras ++ suggested).distinct
    val deduped =
      if (state.pureDiscovery) {
        // Strip the entire respond family + no_response so the agent
        // can only reach a reply through discovery. The legacy
        // standalone tools (deprecated post sigil bug #157) stay in
        // the strip set so apps that opted back into them retain the
        // same pure-discovery semantics.
        val stripped: Set[sigil.tool.ToolName] =
          Set(RespondTool, RespondOptionsTool, NoResponseTool).map(_.schema.name)
        merged.filterNot(stripped.contains)
      } else merged
    // Tool position bias is real for smaller models — they tend to pick the
    // first appropriate-looking tool. Put discovery + action tools first so
    // a "do X" request can land on `find_capability` / `change_mode` instead
    // of being captured by the always-applicable `respond` family. Response
    // tools render last so they're available for chat without dominating
    // when an action tool is the right call.
    //
    // Sigil #302 — `find_capability` is slot 0, not `change_mode`. Discovery
    // is the framework's CORE ideology and every other tool (change_mode
    // included) is reachable through it; channeling stress-confused agents
    // into change_mode's first slot was driving redundant-call loops when
    // the action tools the agent actually needed had fallen out of scope.
    val priority: Map[sigil.tool.ToolName, Int] = (Map(
      FindCapabilityTool.schema.name    -> 0,
      ChangeModeTool.schema.name        -> 1,
      ActivateSkillTool.schema.name     -> 2,
      sigil.tool.core.CancelTool.schema.name -> 100,
      // Within the response tail, `respond_options` precedes `respond` so first-tool
      // bias on small models surfaces the specific "asking" shape before the
      // catch-all "telling" tool. Sigil bug #168.
      RespondOptionsTool.schema.name    -> 101,
      RespondTool.schema.name           -> 102,
      NoResponseTool.schema.name        -> 105
    ): @annotation.nowarn("cat=deprecation")).withDefaultValue(50)
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
   *   1. Resolve the live [[Provider]] + [[Model]] via `resolveProviderModel(modelId)`.
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

  /** The result of per-turn model routing — the strategy chain, the
    * classifier's `(WorkType, Complexity)` verdict, the candidate chain
    * for that work type, per-candidate skip reasons, and the chosen
    * model id (falling back to `agent.modelId` when no candidate
    * supports the resolved complexity). Shared by `runAgentTurn` (which
    * also publishes a `RouteResolved` control event and a fallback
    * notice) and `resolveRoutedModelId` (the curator's budget-gate
    * pre-resolution). */
  private final case class RoutingResolution(strategyOpt: Option[ProviderStrategy],
                                             userMsg: Option[sigil.event.Message],
                                             routedWorkType: WorkType,
                                             complexity: Complexity,
                                             candidateChain: List[sigil.provider.ModelCandidate],
                                             skipReasons: Map[Id[Model], String],
                                             chosen: Option[sigil.provider.ModelCandidate],
                                             modelId: Id[Model])

  /** Resolve per-turn model routing for `agent` in `conv`. Pure of any
    * side effect — no `RouteResolved` publish, no fallback notice;
    * callers layer those on. `ctx` is the [[TurnContext]] handed to the
    * strategy's classifier callbacks: `runAgentTurn` passes the full
    * turn context, `resolveRoutedModelId` passes a stub (curate hasn't
    * run yet at that point). */
  private final def resolveRouting(agent: AgentParticipant,
                                   conv: Conversation,
                                   ctx: TurnContext): Task[RoutingResolution] = {
    // Mode-overrides-agent for work-type routing: a mode that
    // intrinsically dictates a work shape routes the turn to the
    // matching candidate chain even when the agent itself defaults to
    // `ConversationWork`. Modes that don't pin a work type fall through
    // to whatever the agent declares.
    val effectiveWorkType: WorkType =
      conv.currentMode.workType.getOrElse(agent.workType)
    // Strategy resolution: conversation-level pin beats Mode override
    // beats space-level assignment beats agent's pinned modelId.
    val strategyTask: Task[Option[ProviderStrategy]] =
      conv.pinnedModelId match {
        case Some(pinnedId) =>
          Task.pure(Some(ProviderStrategy.single(pinnedId)))
        case None =>
          conv.currentMode.strategyId match {
            case Some(modeStrategyId) =>
              withDB(_.providerStrategies.transaction(_.get(modeStrategyId)))
                .map(_.map(materializeStrategy))
            case None =>
              resolveProviderStrategy(conv.space)
          }
      }
    // The latest user-authored Message — classifier input + the
    // debounce anchor for the routing-fallback notice.
    val latestUserMessage: Task[Option[sigil.event.Message]] =
      withDB(_.eventsTransaction(conv._id)(_.list)).map { evs =>
        evs.iterator
          .collect { case m: sigil.event.Message if m.conversationId == conv._id => m }
          .filter(m => !m.participantId.isInstanceOf[sigil.participant.AgentParticipantId])
          .filter(_.role == sigil.event.MessageRole.Standard)
          .toList
          .sortBy(-_.timestamp.value)
          .headOption
      }.handleError(_ => Task.pure(None))
    for {
      strategyOpt <- strategyTask
      userMsg     <- latestUserMessage
      (routedWorkType, complexity) <- strategyOpt match {
        case Some(strategy) => classifyForRoute(strategy, effectiveWorkType, conv, userMsg, ctx)
        case None           => Task.pure((effectiveWorkType, Complexity.Medium))
      }
      candidateChain = strategyOpt.toList.flatMap(_.availableCandidates(routedWorkType))
      skipReasons    = candidateChain.iterator.collect {
        case c if !c.supportedComplexity.contains(complexity) =>
          c.modelId -> s"supportedComplexity does not include $complexity"
      }.toMap
      // #315 — degrade to the nearest available tier at or below the
      // inferred one before falling back to the agent's pinned model.
      chosen  = Complexity.atOrBelow(complexity).iterator
                  .flatMap(tier => candidateChain.find(_.supportedComplexity.contains(tier)))
                  .nextOption()
      modelId = chosen.map(_.modelId).getOrElse(agent.modelId)
    } yield RoutingResolution(strategyOpt, userMsg, routedWorkType, complexity,
      candidateChain, skipReasons, chosen, modelId)
  }

  private def runAgentTurn(agent: AgentParticipant,
                           context: TurnContext): Stream[Signal] = {
    val effectiveChain = context.chain.filterNot(_ == agent.id) :+ agent.id
    // Sigil #226 + #281 — surface this turn's discovered capabilities
    // (find_capability matches recorded into the per-loop cache via
    // `TurnContext.recordDiscovery`) on the next iteration's tool
    // roster. The system-prompt "Capabilities you've already discovered"
    // section gives the model the names; this folds them into
    // `effectiveToolNames` so the wire roster actually carries them.
    // Without this fold, find_capability returns matches, the model
    // sees them in the prompt section, but the wire `tools` array
    // doesn't include them — the model can't call what isn't there.
    val discoveredToolNames: List[sigil.tool.ToolName] =
      context.discoveredCapabilities.values.toList.flatMap(_.matches)
    val suggested = (context.turnInput.projectionFor(agent.id).suggestedTools ++ discoveredToolNames).distinct

    val resolved: Task[(Vector[Tool], Id[Model], GenerationSettings, List[sigil.role.Role])] =
      for {
        routing <- resolveRouting(agent, context.conversation, context)
        strategyOpt    = routing.strategyOpt
        userMsg        = routing.userMsg
        routedWorkType = routing.routedWorkType
        complexity     = routing.complexity
        candidateChain = routing.candidateChain
        skipReasons    = routing.skipReasons
        chosen         = routing.chosen
        modelId        = routing.modelId
        _ <- publishRouteResolved(
               agentId            = agent.id,
               conversation       = context.conversation,
               userMessage        = userMsg,
               strategyOpt        = strategyOpt,
               inferredWorkType   = routedWorkType,
               complexity         = complexity,
               candidateChain     = candidateChain.map(_.modelId),
               chosenModelId      = modelId,
               skipReasons        = skipReasons
             )
        // Sigil bug #175 — when every candidate is skipped (typically
        // because an expected provider is unavailable, e.g. an env-var
        // unset took its candidate out of the chain), `chosen` is None
        // and dispatch falls back to `agent.modelId`. RouteResolved
        // records the skip reasons but is a ControlPlaneEvent — it
        // doesn't enter the agent's ContextFrame projection, so the
        // agent has no way to read "the framework wanted to route
        // higher but couldn't." The observed failure mode is an
        // infinite `change_mode` loop: the agent calls `change_mode`,
        // notices the model didn't change, calls it again, and so on
        // until the iteration cap fires.
        //
        // Emit a Standard-role Message (visibility=All) so the agent
        // sees the structural failure on its next iteration's
        // TurnInput and stops retrying. Tool-role would be semantically
        // closer to "this is framework output," but #174's contract
        // requires Tool-role events to carry an origin pointing at a
        // parent ToolInvoke — there's no invoke to pair with here.
        // The Standard-role message lands as a Text frame in the
        // agent's context and reads naturally.
        //
        // Debounce: routing resolves per agent iteration, but the
        // chain doesn't change mid-loop. Suppress when a prior
        // routing-fallback notice already exists later than the
        // latest user message on this conversation — the agent has
        // already seen it.
        _ <- if (chosen.isEmpty && candidateChain.nonEmpty) {
               val skipBody =
                 if (skipReasons.isEmpty) "(no skip reasons recorded)"
                 else skipReasons.map { case (id, why) => s"  - ${id.value}: $why" }.mkString("\n")
               val alreadyEmittedTask: Task[Boolean] =
                 withDB(_.eventsTransaction(context.conversation._id)(_.list)).map { evs =>
                   val userTs = userMsg.map(_.timestamp.value).getOrElse(0L)
                   evs.exists {
                     case m: sigil.event.Message =>
                       m.conversationId == context.conversation._id &&
                         m.source.contains("routing-fallback") &&
                         m.timestamp.value >= userTs
                     case _ => false
                   }
                 }.handleError(_ => Task.pure(false))
               alreadyEmittedTask.flatMap {
                 case true  => Task.unit
                 case false =>
                   publish(Message(
                     participantId  = agent.id,
                     conversationId = context.conversation._id,
                     topicId        = context.conversation.currentTopicId,
                     role           = MessageRole.Standard,
                     state          = EventState.Complete,
                     source         = Some("routing-fallback"),
                     content        = Vector(sigil.tool.model.ResponseContent.Text(
                       s"[Routing notice] Classifier resolved complexity = $complexity, but no candidate in the " +
                       s"strategy chain supports that tier. Falling back to ${modelId.value}. Skip reasons:\n" +
                       skipBody +
                       "\n\nThis usually means an expected provider is unavailable (missing env var / network) " +
                       "or the strategy's chain doesn't cover this tier. Repeated `change_mode` or `pin_complexity` " +
                       "calls won't change this — the chain itself is the gap. Tell the user; don't loop."
                     ))
                   )).map(_ => ())
               }
             } else Task.unit
        // Bug #97 — fold conversation overlays into the effective
        // tool roster. `start_metals` etc. install Active(names) so
        // the LSP/BSP/metals tools are present in subsequent turns
        // without a `find_capability` round-trip.
        overlays    <- conversationToolOverlays(context.conversation.id)
        // Sigil #286 — pull recently-used tool names from the
        // projection's rolling window. When `narrowRosterByRecentUse`
        // is on, `effectiveToolNames` narrows the baseline roster to
        // this set (intersected); when off, the set is unused.
        recentlyUsed = context.turnInput.projectionFor(agent.id).recentToolInvocations
          .iterator.map(_.toolName).toSet
        effectiveNames = effectiveToolNames(
          agent, context.conversation.currentMode, suggested, overlays.map(_.policy), recentlyUsed
        ).distinct
        // Per-candidate `settings` overlays the agent's
        // generationSettings. The framework keeps the agent's settings
        // as the base — the candidate's settings take precedence on
        // any field they specify (currently a wholesale replace; if
        // we want field-by-field merge later that lives here).
        genSettings  = chosen.map(_.settings).getOrElse(agent.generationSettings)
        rawTools    <- Task.sequence(effectiveNames.map(n => findTools.byName(n))).map(_.flatten.toVector)
        // Filter out memory tools when the chain has no accessible
        // spaces — surfacing `save_memory` / `unpin_memory` /
        // `list_memories` to an agent that has nowhere to write
        // would just waste tokens on tool descriptions the agent
        // would fail to use.
        accessible  <- accessibleSpaces(effectiveChain, context.conversation.id)
        t            = if (accessible.isEmpty) rawTools.filterNot(_.requiresAccessibleSpaces)
                        else rawTools
        // Resolve the agent's roles for this turn. Static agents return
        // their declared `roles` field; DB-backed agents (e.g. apps
        // with persona records) consult persistence here. Empty result
        // is treated as a programmer error.
        rolesResolved <- agent.resolveRoles(context).map { rs =>
          require(rs.nonEmpty,
            s"AgentParticipant.resolveRoles must return a non-empty list (id=${agent.id.value})")
          rs
        }
      } yield (t, modelId, genSettings, rolesResolved)

    Stream.force(resolved.map { case (tools, modelId, genSettings, rolesResolved) =>
      // Bug #91 — stamp the registry's canonical id on outbound
      // requests when one is known. Settled Messages then carry the
      // prefixed form (`openai/gpt-5.5`) that the cost projection
      // and OpenRouter-derived catalog lookups expect, so we don't
      // depend on the tolerant fallback at every read site. No-op
      // when the candidate's id isn't in the registry.
      val canonicalModelId = cache.canonicalIdFor(modelId)
      // Sigil #277 + the modelResolver refactor — resolve the canonical
      // id to BOTH the live provider and the registered Model record in
      // one pass. A miss throws `UnregisteredModelException` here so the
      // failure surfaces at the turn boundary instead of silently
      // truncating on the wire. In a multi-provider app the registry
      // dispatches by the id's namespace, so the provider always matches
      // the model it's about to serve.
      val resolvedPM = resolveProviderModel(canonicalModelId)
      val provider = resolvedPM.provider
      val resolvedModel: Model = resolvedPM.model
      // Sigil bug #199 — forced-synthesis is the framework's
      // last-resort "make the model respond" turn. Tool-call already
      // narrowed to the respond family at the orchestrator boundary;
      // here we ALSO bound the output budget and force reasoning
      // mode off. Reasoning-template local models (qwen3.5-9b via
      // llama.cpp, DeepSeek-R1 family) otherwise burn the entire
      // context window on `reasoning_content` and emit zero
      // `tool_calls` — observed 4-minute hangs that turn a
      // recoverable hiccup into a permanently failed turn.
      val effectiveSettings =
        if (context.forceResponseSynthesis)
          // Cap aggressively even when the caller didn't — forced-
          // synthesis is supposed to emit ONE respond call, ≤ a few
          // hundred tokens of content. `tightenedTo` preserves a
          // tighter caller-supplied cap (sigil #276).
          genSettings.tightenedTo(2048).copy(
            // Hard override (not orElse) — the narrow tool_choice
            // means there's nothing worth reasoning about anyway.
            reasoningMode = ReasoningMode.Off
          )
        else genSettings
      val request = ConversationRequest(
        conversationId = context.conversation.id,
        model = resolvedModel,
        instructions = agent.instructions,
        turnInput = context.turnInput,
        currentMode = context.conversation.currentMode,
        currentTopic = context.conversation.currentTopic,
        previousTopics = context.conversation.previousTopics,
        generationSettings = effectiveSettings,
        tools = tools,
        builtInTools = agent.builtInTools ++ context.conversation.currentMode.builtInTools,
        chain = effectiveChain,
        roles = rolesResolved,
        isGreeting = context.isGreeting,
        forceResponseSynthesis = context.forceResponseSynthesis,
        discoveredCapabilities = context.discoveredCapabilities,
        // Sigil #281 follow-up — thread the LIVE per-agent-loop cache
        // ref through to the orchestrator's tool dispatch. Without
        // this, `FindCapabilityTool.recordDiscovery` writes to a fresh
        // ref on the orchestrator's TurnContext that the next iteration
        // never sees; the discovered tool ends up invisible to the
        // wire roster.
        discoveredCapabilitiesRef = context.discoveredCapabilitiesRef,
        turnStartedAt = context.turnStartedAt
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
   * `save_memory` invocations) when the agent doesn't supply one
   * explicitly. Apps that want per-user / per-conversation /
   * per-project scoping return the appropriate concrete subtype; apps
   * that haven't wired memory yet return `Task.pure(None)` (the
   * memory tools fail with a helpful error in that case).
   */
  def defaultMemorySpace(conversationId: Id[Conversation]): Task[Option[SpaceId]] =
    Task.pure(None)

  /**
   * The default [[SpaceId]] set used by recall-style searches
   * (e.g. `semantic_search`) when the agent doesn't supply a filter.
   * Apps typically return the caller's user/space combination.
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

  /** All pinned memories scoped to the supplied spaces — the
    * inviolable subset the framework renders every turn. Used by
    * `list_memories(pinned=true)` and the core-context cap validator.
    * Pushes the `pinned == true` filter into Lucene; the result is
    * filtered to the requested spaces in-memory (since `SpaceId` is
    * polymorphic the equality side uses the indexed `spaceIdValue`
    * projection downstream of [[findMemories]]). */
  def findCriticalMemories(spaces: Set[SpaceId]): Task[List[ContextMemory]] =
    findMemories(spaces).map(_.filter(_.pinned))

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
    List(LocationCaptureTransform, ContentExternalizationTransform, RespondOptionsSelectionFramingTransform, TopicIndexCanonicalizingTransform, WorkerConversationAddressingTransform)

  /**
   * Bytes — content blocks larger than this get pushed to the
   * configured [[storageProvider]] and replaced with a
   * [[sigil.tool.model.ResponseContent.StoredFileReference]] before
   * the Message persists. Default 8 KB. Set to `Long.MaxValue` to
   * disable externalization entirely.
   */
  def inlineContentThreshold: Long = 8L * 1024L

  /**
   * Sigil #288 — mirror of [[inlineContentThreshold]] for the
   * assistant-side `tool_use` payloads. When the agent calls a tool
   * whose `externalizableInputFields` includes a field whose value
   * exceeds this threshold, the curator replaces the value with a
   * short placeholder in subsequent turns' wire prompts. The durable
   * event log keeps the full input; the agent recovers the original
   * via `search_conversation` if needed.
   *
   * Default mirrors [[inlineContentThreshold]] so apps tuning one
   * gauge tune both. Set to `Long.MaxValue` to disable tool_use
   * externalization while keeping tool_result externalization active.
   *
   * The current iteration's tool_use stays inline regardless — the
   * agent's about to read its own emission on the next turn boundary
   * and needs the full payload. Externalization only applies to
   * tool_use frames from PRIOR iterations.
   */
  def inlineToolUseContentThreshold: Long = inlineContentThreshold

  /**
   * Sigil #289 — how many of the most-recent image-bearing tool
   * results (`ToolCall.state = Complete(_, images)` frames with a
   * non-empty `images` list) the curator preserves in the per-turn
   * wire prompt. Older image-bearing frames render with empty
   * `images` and a short text stub in place of the original
   * `content`; the durable event log is untouched (anything stubbed
   * is recoverable via `search_conversation`).
   *
   * Default `1` — the most recent image stays inline so the agent
   * can see current visual state; everything older is suppressed.
   * Apps that need multiple in-context images (a side-by-side
   * comparison tool, a multi-page OCR reader) override to widen.
   * Set to `Int.MaxValue` to disable supersession entirely.
   *
   * Rationale: visual outputs (`ImageToolOutput`) are typically
   * ~50-100 KB per image. A 32-iteration turn doing repeated
   * previews would otherwise accumulate megabytes of stale images
   * the agent doesn't need — only the latest snapshot is
   * load-bearing for the reasoning at hand.
   */
  def keepRecentImages: Int = 1

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
    List(MessageIndexingEffect, GeocodingEnrichmentEffect, MemoryCacheInvalidationEffect)

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

  /**
   * Recognises refusal language in an agent's `respond.content`
   * (sigil bug #126). When the detector fires AND no
   * `find_capability` call exists in the conversation tail since
   * the last user-authored Message, the orchestrator suppresses
   * the respond emission and substitutes a Tool-role `Failure`
   * the agent reads on its next iteration, prompting it to
   * actually consult the catalog before refusing.
   *
   * Default: [[sigil.provider.RefusalDetector.Default]] — a
   * conservative regex set tuned against the wire-log scenario
   * the bug was filed from. Apps where refusal is a valid
   * outcome (moderation flows, sandbox executors) override with
   * [[sigil.provider.RefusalDetector.Never]] or a custom
   * implementation.
   */
  def refusalDetector: sigil.provider.RefusalDetector =
    sigil.provider.RefusalDetector.Default

  /**
   * Reactive self-healers registered with the framework. When an
   * iteration of [[runAgentLoop]] throws, the agent loop's
   * `handleError` chain walks this list with the thrown error and
   * picks the first match — runs the strategy in
   * [[sigil.heal.HealingMode.Recover]], records the corruption and
   * re-throws in [[sigil.heal.HealingMode.Strict]].
   *
   * Default ships [[sigil.heal.MissingToolResultStrategy]] — heals
   * the orphan-`ToolInvoke` shape (#313). Apps override to add their
   * own strategies; concatenate with `super.healingStrategies` to
   * keep the framework defaults.
   */
  def healingStrategies: List[sigil.heal.HealingStrategy] =
    List(sigil.heal.MissingToolResultStrategy)

  /**
   * Per-instance toggle for how the framework reacts to a
   * [[sigil.heal.HealingStrategy]]-matched error. Production default
   * [[sigil.heal.HealingMode.Recover]] heals + retries the agent's
   * iteration. Dev / CI default ([[sigil.heal.HealingMode.Strict]],
   * overridden in `TestSigil`) re-throws so the developer hits the
   * failure; corruption is still recorded via the durable
   * [[sigil.event.ConversationCorruptionDetected]] event.
   */
  def healingMode: sigil.heal.HealingMode = sigil.heal.HealingMode.Recover

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
   * Named registry of [[ContentRenderer]]s — the projection table the
   * framework (and apps' settled effects / wire transports) use to turn
   * a `Vector[ResponseContent]` into a target representation.
   *
   * Defaults ship four [[String]] renderers — `"markdown"`,
   * `"slack"`, `"html"`, `"text"` — covering the common conversation
   * UI / Slack / email / fallback surfaces. Apps register additional
   * named renderers (Discord-flavoured markdown, Microsoft Teams
   * AdaptiveCard JSON, terminal ANSI, …) by overriding this hook with
   * a superset:
   *
   * {{{
   *   override def contentRenderers: Map[String, ContentRenderer[String]] =
   *     super.contentRenderers + ("discord" -> DiscordRenderer)
   * }}}
   *
   * Apps that need non-`String` outputs (Slack Block Kit JSON, HTML
   * AST nodes) define a separate registry on their `Sigil` subclass
   * — the framework registry stays `String`-typed to keep the common
   * "render-and-send-text" path simple.
   */
  def contentRenderers: Map[String, ContentRenderer[String]] = Map(
    "markdown" -> MarkdownRenderer,
    "slack"    -> SlackMrkdwnRenderer,
    "html"     -> HtmlRenderer,
    "text"     -> PlainTextRenderer
  )

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

  /** Per-subscriber queue capacity for the [[SignalHub]]. Every
    * `sigil.signals` / `signalsFor(viewer)` subscriber gets its own
    * bounded queue of this size; when one fills, the hub sheds the
    * oldest transient signal (a `Delta` / `Notice`) rather than block
    * the publisher. The default ([[SignalHub.DefaultCapacity]]) is
    * sized for always-on reasoning-model streams; apps with even
    * heavier signal volume — or many slow wire subscribers — raise it. */
  def signalHubCapacity: Int = SignalHub.DefaultCapacity

  /** Multicast dispatcher populated by [[publish]]. One per Sigil
    * instance; initialized lazily so initialization order is safe. */
  private final lazy val hub: SignalHub = new SignalHub(signalHubCapacity)

  /** #317 — event ids whose live broadcast already happened eagerly
    * (via [[broadcastEager]]) ahead of the per-iteration batch drain.
    * [[publish]] consults this set at its hub-emit step and SKIPS the
    * re-broadcast for a matching event id (removing it), so the event
    * is broadcast exactly once even though it still flows through the
    * full publish pipeline for durable persist + projection. */
  private final val eagerlyBroadcastEventIds: java.util.Set[Id[Event]] =
    java.util.concurrent.ConcurrentHashMap.newKeySet[Id[Event]]()

  /**
   * #317 — broadcast `event` to live [[signals]] / [[signalsFor]]
   * subscribers immediately, ahead of the per-iteration batch drain
   * (and ahead of any blocking tool execution that drain would
   * otherwise gate the broadcast behind).
   *
   * Records the event id so the subsequent [[publish]] of the same
   * event — which still runs the full pipeline (durable insert,
   * projection, fan-out, settled effects) — suppresses its own
   * hub re-emit. The event is therefore broadcast exactly once and
   * persisted exactly once; only the broadcast *timing* moves
   * forward.
   *
   * Inbound transforms run here so the eagerly-broadcast shape
   * matches what `publish` would have emitted; `_id` is stable
   * across transforms, so the suppression key holds.
   */
  final def broadcastEager(event: Event): Task[Unit] =
    applyInboundTransforms(event).map {
      case resolved: Event =>
        eagerlyBroadcastEventIds.add(resolved._id)
        hub.emit(resolved)
        ()
      case _ => ()
    }

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

  /**
   * Pluggable text-to-speech / speech-to-text / image-generation
   * provider. Default [[sigil.media.NoOpMediaProvider]] raises
   * `UnsupportedMediaOperation` from every method; apps that need
   * media wire a concrete implementation (e.g. Sage's
   * `sage.media.ElevenLabsTts` for voice, `sage.media.OpenAIImageGen`
   * for image gen). A downstream app's speech and image-generation
   * services become thin call-throughs to whatever this provides.
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

  /**
   * Per-chunk diagnostic hook for streaming SSE provider responses
   * (sigil bug #194). Default
   * [[sigil.provider.debug.ChunkLogger.NoOp]] writes nothing and pays
   * zero overhead; apps that want post-hoc stall diagnosis on
   * streaming turns override with
   * [[sigil.provider.debug.FileChunkLogger]] pointing at a separate
   * JSONL file:
   *
   * {{{
   *   override def chunkLogger: ChunkLogger =
   *     FileChunkLogger(Paths.get("target/wire-chunks.jsonl"))
   * }}}
   *
   * Decoupled from [[wireInterceptor]] because per-chunk traffic is
   * 50–100x more lines than the request-aggregated mode and many apps
   * only need the request/response wire log most of the time.
   */
  def chunkLogger: sigil.provider.debug.ChunkLogger = sigil.provider.debug.ChunkLogger.NoOp

  // -- participants (registration for polymorphic RW) --

  /**
   * App-specific [[Participant]] subtypes registered into the polymorphic
   * discriminator so [[sigil.conversation.Conversation.participants]] can
   * round-trip them through fabric RW. Framework subtypes
   * ([[DefaultAgentParticipant]]) are registered automatically; this list
   * extends the poly with app-specific agent types (Planner, Critic, etc.).
   */
  protected def participants: List[RW[? <: Participant]] = Nil

  // -- services (long-lived background dependencies) --

  /**
   * Persistent background services Sigil advertises to UIs through
   * status chips with state controls and (optionally) streaming logs.
   * Each entry surfaces as one chip; concrete examples are a running
   * LSP / BSP server, a model server, an MCP connection, a database
   * pool. The framework caches the most recent
   * [[ServiceStatusSignal]] per service id and replays it to fresh
   * clients on connect via [[sigil.transport.SignalTransport.attach]].
   *
   * Concrete [[sigil.provider.Provider]] implementations automatically
   * satisfy the [[Service]] interface (every provider IS a service);
   * apps wire their resolved providers into this list when they want
   * provider chips visible in the services panel. Apps that don't use
   * the services surface leave the default `Nil` and pay nothing —
   * the framework's status-emit machinery is no-op unless a service
   * publishes through [[publishServiceStatus]].
   */
  def services: List[Service] = Nil

  /** All services keyed by id. Computed once from [[services]];
    * read by [[serviceById]] / [[serviceStatusReplay]]. */
  private final lazy val servicesById: Map[Id[Service], Service] =
    services.map(s => s.id -> s).toMap

  /** Look up a registered service by its stable id. Returns `None`
    * for unknown ids — apps that need fail-loud semantics check the
    * result themselves. */
  final def serviceById(id: Id[Service]): Option[Service] = servicesById.get(id)

  /** Latest [[ServiceStatusSignal]] observed per service id. Populated
    * by [[publish]] whenever a `ServiceStatusSignal` flows through and
    * read by [[serviceStatusReplay]] so a fresh client connecting after
    * a status transition sees the current state immediately rather
    * than waiting for the next transition. The map is process-local;
    * no disk persistence. */
  private final val serviceStatusCache: AtomicReference[Map[Id[Service], ServiceStatusSignal]] =
    new AtomicReference(Map.empty)

  /** Synchronous read of the cached latest status for a single service.
    * Returns the cached signal if any has been published in this
    * process lifetime, otherwise synthesises one from the service's
    * [[Service.currentState]] so fresh consumers always see something. */
  final def latestServiceStatus(id: Id[Service]): Option[ServiceStatusSignal] = {
    serviceStatusCache.get.get(id).orElse {
      servicesById.get(id).map(s => ServiceStatusSignal(s.id, s.currentState))
    }
  }

  /** Latest status snapshot for every registered service — the
    * payload [[sigil.transport.SignalTransport.attach]] sends to a
    * freshly-connected subscriber so its chips paint with current
    * state immediately. Services that have published at least once
    * report their cached signal; services that haven't fall back to
    * a synthetic signal derived from [[Service.currentState]]. */
  final def serviceStatusReplay: List[ServiceStatusSignal] = {
    val cached = serviceStatusCache.get
    services.map { svc =>
      cached.getOrElse(svc.id, ServiceStatusSignal(svc.id, svc.currentState))
    }
  }

  /** Publish a [[ServiceStatusSignal]] and update the latest-status
    * cache. Apps and framework subsystems call this when a service
    * transitions state. Idempotent — re-publishing the same state
    * still broadcasts (consumers tracking deltas can dedupe), and
    * still refreshes the cache so the timestamp behaviour stays
    * consistent. */
  final def publishServiceStatus(signal: ServiceStatusSignal): Task[Unit] =
    publish(signal)

  /** Publish a [[ServiceLogSignal]] as a live-only Notice — never
    * persisted, never cached. Apps and framework subsystems route
    * service stdout / stderr through this helper so log-tail UIs
    * receive the line in real time. */
  final def publishServiceLog(signal: ServiceLogSignal): Task[Unit] =
    publish(signal)

  /** Update the latest-status cache when a [[ServiceStatusSignal]]
    * flows through [[publish]]. Called from the publish pipeline
    * before the hub emit so a subscribe-then-poll race can't observe
    * a state where the hub has delivered the new signal but the
    * cache still reports the old one. */
  private final def updateServiceStatusCache(signal: Signal): Unit = signal match {
    case s: ServiceStatusSignal =>
      serviceStatusCache.updateAndGet(_ + (s.serviceId -> s))
      ()
    case _ => ()
  }

  // -- provider resolution --

  /**
   * The framework's model→provider binding. A single [[Provider]] for a
   * one-backend app, or a [[sigil.provider.ProviderRegistry]] that
   * dispatches by the model id's provider namespace for a multi-backend
   * app (e.g. `ProviderRegistry(List(llamaCpp, anthropic, cloudflare))`).
   *
   * This replaces the former abstract `providerFor(modelId, chain)`:
   * model→provider dispatch is now the framework's job, not something
   * each consumer re-implements (the gap behind sigil #333). Because a
   * registry dispatches by namespace and every provider renders its wire
   * `model` field under that same key, a namespaced id can only reach the
   * provider that serves it.
   *
   * Per-user / per-credential resolution (an app that lets users bring
   * their own provider) is layered above this seam at the app level; this
   * resolver is the internal / fallback binding.
   */
  def modelResolver: sigil.provider.ModelResolver

  /**
   * Resolve a model id to its [[sigil.provider.ProviderModel]] via
   * [[modelResolver]], throwing [[sigil.provider.UnregisteredModelException]]
   * when nothing serves it. The single resolution entry point the turn
   * pipeline, [[sigil.tool.consult.ConsultTool]], and workflow steps go
   * through — fail-loud rather than silently degrading.
   */
  final def resolveProviderModel(modelId: Id[Model]): sigil.provider.ProviderModel =
    modelResolver.resolve(modelId).getOrElse(
      throw new sigil.provider.UnregisteredModelException(modelId, cache.all.map(_._id))
    )

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
        Task { updateServiceStatusCache(resolved); hub.emit(resolved); () }
      }
    case _ =>
      validateEventInvariants(signal).flatMap { _ =>
      applyInboundTransforms(signal).flatMap { resolved =>
        for {
          _ <- withDB(_.apply(resolved))
          _ <- attachContextFrameOnSettle(resolved)
          _ <- updateConversationProjection(resolved)
          _ <- updateView(resolved)
          _ <- maybeApplyModeSkill(resolved)
          _ <- applyStop(resolved)
          // #317 — skip the re-broadcast when this event was already
          // broadcast eagerly at tool-start; persist + projection above
          // still ran, so the event stays durable + projected exactly once.
          _ <- Task {
                 val alreadyBroadcast = resolved match {
                   case e: Event => eagerlyBroadcastEventIds.remove(e._id)
                   case _        => false
                 }
                 if (!alreadyBroadcast) hub.emit(resolved)
                 ()
               }
          _ <- resolved match {
                 case e: Event => fanOut(e)
                 case _: sigil.signal.Delta => Task.unit
                 case _: sigil.signal.Notice => Task.unit  // unreachable here, exhaustive
               }
          _ <- applySettledEffects(resolved)
        } yield ()
      }
      }
  }

  /** Validate Event invariants before persistence. Bug #64 —
    * fail-loud at the write boundary so a malformed event never
    * reaches the DB (and never poisons subsequent reads via
    * `FrameBuilder`'s recovery path). The Throwable's stack
    * trace points directly at the caller that bypassed the
    * invariant — diagnostically useful in a way the read-side
    * throw never was.
    *
    * Currently checks: every `MessageRole.Tool` event must
    * carry `origin` pointing to its parent ToolInvoke. Apps
    * with custom Event subtypes can extend the validation by
    * overriding this hook. */
  protected def validateEventInvariants(signal: Signal): Task[Unit] = signal match {
    case e: sigil.event.Event
      if e.role == sigil.event.MessageRole.Tool && e.origin.isEmpty =>
      Task.error(new IllegalStateException(
        s"Refusing to publish ${e.getClass.getSimpleName} with role=Tool but no `origin`. " +
          s"Every Tool-role event MUST carry `origin` pointing to its parent ToolInvoke. " +
          s"Event id=${e._id.value}; participantId=${e.participantId.value}; " +
          s"conversationId=${e.conversationId.value}. " +
          s"Caller stack trace identifies the emission site that bypassed origin-stamping."
      ))
    case _ => Task.unit
  }

  /** When a published [[Signal]] settles an Event to
    * `EventState.Complete` (atomic Complete event OR Delta whose
    * application yields a Complete state), compute the event's
    * [[sigil.conversation.ContextFrame]] via
    * [[FrameBuilder.computeFrame]] and write it back to `db.events`.
    * Idempotent — recomputing on an event that already carries a
    * frame is a no-op (we skip the write if the frame matches).
    *
    * Bug #26 — settle-time frame inlining is the source-of-truth path
    * for prompt construction; the curator queries
    * `event.contextFrame.isDefined` against `db.events` instead of
    * walking a separate frames Vector projection. */
  private final def attachContextFrameOnSettle(signal: Signal): Task[Unit] = {
    val targetOpt: Option[(Id[Conversation], Id[Event])] = signal match {
      case e: Event if e.state == EventState.Complete =>
        Some(e.conversationId -> e._id)
      case d: sigil.signal.Delta =>
        Some(d.conversationId -> d.target.asInstanceOf[Id[Event]])
      case _ => None
    }
    targetOpt match {
      case None => Task.unit
      case Some((conversationId, eventId)) =>
        withDB(_.eventsTransaction(conversationId) { tx =>
          tx.get(eventId).flatMap {
            case None => Task.unit
            case Some(event) if event.state != EventState.Complete => Task.unit
            case Some(event) =>
              val frame = FrameBuilder.computeFrame(event)
              // Sigil #261/#265 — write whenever computeFrame disagrees
              // with the inlined frame. `computeFrame` is a pure
              // function of the event's current state, so any
              // disagreement means the durable state has moved on (a
              // ToolDelta folded `output` / `outcome` onto a
              // ToolInvoke, flipping its frame from Active to
              // Complete; a settling MessageDelta replaced a
              // streaming Message's content; …) and the inline frame
              // must follow. Pre-#265 the framework guarded with a
              // hard "never replace" rule because the cross-event
              // pair-update was the only legitimate mutation —
              // collapsing the tool transaction into a single
              // stateful invoke removed that constraint.
              val ownWrite: Task[Unit] = frame match {
                case None                                 => Task.unit
                case Some(f) if event.contextFrame.contains(f) => Task.unit
                case Some(f)                              => tx.upsert(event.withContextFrame(Some(f))).unit
              }
              // Sigil #261 — when a ToolResults settles, fold its
              // content into the prior ToolInvoke's inlined ToolCall
              // frame so the projection carries the full tool
              // transaction in one frame. Pair adjacency on the wire
              // is then guaranteed by construction, regardless of what
              // else interleaved between the invoke and the result.
              // Sigil #263 — pair-update fires on ANY Tool-role event
              // with an `origin`, not just `ToolResults`. The orchestrator
              // surfaces tool-input parse failures via a Tool-role
              // `Message` (`disposition = Failure`, `origin =
              // Some(invokeId)`) — same shape `settleOrphanToolInvoke`
              // emits for stream-abort orphans. Narrowing pair-update
              // to `ToolResults` left those failures unpaired, so the
              // ToolInvoke stayed Active and `renderFrames` complained
              // about dangling tool_calls. Generalising over the
              // Tool-role+origin shape closes that hole and keeps the
              // invariant honest: every event the framework attributes
              // to a parent tool call settles its frame.
              val pairUpdate: Task[Unit] = event match {
                case e: Event if e.role == MessageRole.Tool =>
                  e.origin match {
                    case Some(invokeId) =>
                      tx.get(invokeId).flatMap {
                        case Some(ti) =>
                          ti.contextFrame match {
                            case Some(tc: ContextFrame.ToolCall)
                                if tc.state == ToolCallState.Active =>
                              val (content, images) = FrameBuilder.toolResultPayload(e)
                              val updated = tc.copy(
                                state = ToolCallState.Complete(content, images)
                              )
                              tx.upsert(ti.withContextFrame(Some(updated))).unit
                            case _ => Task.unit
                          }
                        case None => Task.unit
                      }
                    case None => Task.unit
                  }
                case _ => Task.unit
              }
              ownWrite.flatMap(_ => pairUpdate)
          }
        })
    }
  }

  /**
   * Bulk-import historical events into a conversation. Persists +
   * projects the batch, then emits a single
   * [[sigil.signal.ConversationHistoryImported]] Notice carrying the
   * conversation id and the count of events added.
   *
   * Skipped vs. [[publish]]: per-event `hub.emit`, [[fanOut]] (trigger
   * evaluation), inbound transforms, and [[settledEffects]] do NOT run
   * — these are *historical* events being seeded into context, not
   * "happened just now" wire events. Persistence and projection still
   * run so the conversation surfaces the events to subsequent reads.
   *
   * Caller is responsible for any follow-up triggering. Typical
   * pattern: `publish` a Tool-role success Message after this resolves
   * so the agent's normal trigger path fires once with the imported
   * history fully in place.
   *
   * @param events         events to import, in source order. Each
   *                       event's `conversationId` should match
   *                       `conversationId`; mismatches are persisted
   *                       under the event's own `conversationId`
   *                       without complaint (caller's responsibility
   *                       to validate if it cares).
   * @param conversationId target conversation. Surfaced on the emitted
   *                       notice so clients scope their refresh
   *                       decision.
   */
  final def publishHistorical(events: Seq[sigil.event.Event],
                              conversationId: Id[Conversation]): Task[Unit] =
    publishHistoricalSilent(events, conversationId).flatMap(_ =>
      notifyHistoryImported(conversationId, events.size)
    )

  /**
   * Persist + project a batch of historical events WITHOUT firing the
   * [[sigil.signal.ConversationHistoryImported]] refresh Notice.
   *
   * Used by long-running imports that progressively persist chunks
   * into a staging conversation: the workflow can call this many
   * times during processing (each chunk lands in DB silently — no
   * subscriber sees per-chunk refresh churn), then call
   * [[notifyHistoryImported]] exactly once at the end (typically
   * after [[mergeStagingIntoMain]] flips the events to the real
   * conversation).
   *
   * Apps doing one-shot imports (no staging step) should keep
   * calling [[publishHistorical]] — that's the convenience wrapper
   * that pairs Silent + Notify in a single call.
   */
  final def publishHistoricalSilent(events: Seq[sigil.event.Event],
                                    conversationId: Id[Conversation]): Task[Unit] =
    if (events.isEmpty) Task.unit
    else Task.sequence(events.toList.map(e => validateEventInvariants(e))).flatMap { _ =>
      // Inline contextFrame on every imported event before persisting
      // so the bulk-import path matches the publish-time pipeline's
      // settle-time inlining (bug #26). Events that are still Active
      // (rare for imports, but supported) keep `contextFrame = None`.
      //
      // Sigil #261 — two-pass to mirror the unified ToolCall frame
      // model: first compute each event's own frame (ToolInvoke →
      // ToolCall, state derived from the invoke's own EventState +
      // settled output/outcome); then fold any Tool-role Message
      // diagnostics (orchestrator-synthesised refusal challenges /
      // repeated-query intercepts) into the matching ToolInvoke's
      // inlined ToolCall frame, transitioning it to Complete. This
      // is the bulk-import equivalent of the cross-event update
      // `attachContextFrameOnSettle` does at live-publish time.
      val framedMap = scala.collection.mutable.LinkedHashMap.empty[Id[Event], Event]
      events.foreach { e =>
        val withFrame =
          if (e.state != EventState.Complete || e.contextFrame.nonEmpty) e
          else e.withContextFrame(FrameBuilder.computeFrame(e))
        framedMap(e._id) = withFrame
      }
      framedMap.values.toList.foreach {
        case m: Message if m.role == MessageRole.Tool && m.state == EventState.Complete =>
          m.origin.foreach { invokeId =>
            framedMap.get(invokeId).foreach { ti =>
              ti.contextFrame match {
                case Some(tc: ContextFrame.ToolCall) if tc.state == ToolCallState.Active =>
                  val (content, images) = FrameBuilder.toolResultPayload(m)
                  val updated = tc.copy(state = ToolCallState.Complete(content, images))
                  framedMap(invokeId) = ti.withContextFrame(Some(updated))
                case _ =>
              }
            }
          }
        case _ =>
      }
      val framed = framedMap.values.toSeq
      val batches = framed.grouped(1000).toList
      val persistAll: Task[Unit] = Task.sequence(batches.map { batch =>
        withDB(_.events.transaction { tx =>
          Task.sequence(batch.toList.map(tx.upsert))
        })
      }).unit
      for {
        _ <- persistAll
        _ <- coalescedProjectionFor(conversationId, events)
        // Frames are sourced live from `db.events` — no view to rebuild post-publishHistoricalSilent.
      } yield ()
    }

  /**
   * Emit the [[sigil.signal.ConversationHistoryImported]] refresh
   * Notice for `conversationId`. Idempotent — safe to call from
   * cancel-handlers that aren't sure whether the import made
   * progress. Pair with [[publishHistoricalSilent]] for chunked
   * progressive imports; the convenience [[publishHistorical]]
   * already wraps both steps for one-shot imports.
   */
  final def notifyHistoryImported(conversationId: Id[Conversation], totalEventCount: Int): Task[Unit] =
    Task {
      hub.emit(sigil.signal.ConversationHistoryImported(conversationId, totalEventCount))
      ()
    }

  /** Coalesced [[Conversation]] row update for a bulk import: applies
    * the *last* [[ModeChange]] / [[TopicChange]] in the batch and adds
    * the summed cost from imported [[Message]]s. Skips per-event
    * [[sigil.signal.ConversationCostUpdated]] Notices — bulk imports
    * are silent on the wire by design. */
  private final def coalescedProjectionFor(conversationId: Id[Conversation],
                                           events: Seq[sigil.event.Event]): Task[Unit] = {
    val complete = events.filter(_.state == EventState.Complete)
    val latestMode = complete.reverseIterator.collectFirst { case mc: ModeChange => mc }
    val latestTopic = complete.reverseIterator.collectFirst { case tc: TopicChange => tc }
    val totalCost: BigDecimal = complete.iterator.collect {
      case m: Message =>
        m.modelId.flatMap(cache.find).map { model =>
          Sigil.costFor(model.pricing, m.usage)
        }.getOrElse(BigDecimal(0))
    }.foldLeft(BigDecimal(0))(_ + _)

    val applyMode: Task[Unit] = latestMode match {
      case None => Task.unit
      case Some(mc) =>
        withDB(_.conversations.transaction(_.modify(conversationId) {
          case Some(conv) if conv.currentMode != mc.mode =>
            Task.pure(Some(conv.copy(currentMode = mc.mode, modified = Timestamp(Nowish()))))
          case other => Task.pure(other)
        })).unit
    }
    val applyTopic: Task[Unit] = latestTopic match {
      case None     => Task.unit
      case Some(tc) => applyTopicChangeToStack(tc)
    }
    val applyCost: Task[Unit] =
      if (totalCost <= 0) Task.unit
      else withDB(_.conversations.transaction(_.modify(conversationId) {
        case None => Task.pure(None)
        case Some(conv) =>
          Task.pure(Some(conv.copy(cost = conv.cost + totalCost, modified = Timestamp(Nowish()))))
      })).unit

    applyMode.flatMap(_ => applyTopic).flatMap(_ => applyCost)
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
   * Wrap a framework-internal Task with lifecycle Notices so client
   * UIs can render a progress indicator for it. Bug #50.
   *
   * Emits a [[sigil.signal.FrameworkWorkflowNotice]] in three (or
   * two) phases:
   *   1. `Started(label)` — immediately on enter.
   *   2. (optional) `Step(stepLabel, durationMs)` — emitted from
   *      within `task` via the `step` callback handed to it.
   *   3. `Completed(durationMs)` on success OR `Failed(reason,
   *      durationMs)` on error.
   *
   * Notices are transient — broadcast through the SignalHub, NOT
   * persisted to `db.events`. Pre-flight runs every turn; persisting
   * a started/completed pair per pre-flight pollutes the event log
   * with noise nothing reads later. Clients filter the Notice for
   * activity-bar / latency-trace rendering and apply their own
   * threshold ("don't paint sub-300ms workflows") client-side.
   *
   * `workflowType` is the broad category (`"preflight"`,
   * `"compress"`, `"frame-load"`, …). Apps wrap their own
   * framework-internal operations by calling this with their own
   * type strings — the framework treats the field as opaque.
   *
   * `conversationId` scopes the workflow to a conversation when
   * applicable (most common case); `None` for cross-conversation
   * operations.
   */
  final def runAsFrameworkWorkflow[A](workflowType: String,
                                      label: String,
                                      conversationId: Option[Id[Conversation]] = None)
                                     (task: FrameworkWorkflowControl => Task[A]): Task[A] = {
    // Backwards-compat shim. The public signature has not changed —
    // every prior callsite (in this repo and downstream) still calls
    // it the same way. Internally it delegates to [[RunUnit.execute]]
    // for the Started → Completed | Failed pulse, but ALSO carries the
    // extras this surface was always responsible for:
    //
    //   - the `Step` callback handed to the body via
    //     [[FrameworkWorkflowControl]] (with its implicit
    //     cancellation-token checkpoint),
    //   - registration / deregistration in
    //     [[Sigil.activeFrameworkWorkflows]] for the
    //     `cancel_framework_workflow` tool surface,
    //   - the special `CancellationException` failure-reason format
    //     ("cancelled: <reason>") that
    //     [[sigil.tool.core.CancelFrameworkWorkflowTool]] depends on.
    //
    // The persistent-Event vs operational-Notice distinction
    // pinned on [[RunUnit.execute]] still holds — apps and the
    // framework continue to publish persistent lifecycle Events
    // separately for their own callsites; the wrap here only emits
    // the operational `FrameworkWorkflowNotice`.
    val token = new CancellationToken(java.util.UUID.randomUUID().toString)
    val record = ActiveFrameworkWorkflow(token.workflowId, workflowType, label, conversationId,
      System.currentTimeMillis(), token)
    val started = record.startedAtMillis
    val stepCb: String => Task[Unit] = { stepLabel =>
      token.checkpoint.flatMap(_ =>
        publish(sigil.signal.FrameworkWorkflowNotice(
          token.workflowId,
          workflowType,
          sigil.signal.FrameworkWorkflowPhase.Step(stepLabel, System.currentTimeMillis() - started),
          conversationId
        ))
      )
    }
    val control = FrameworkWorkflowControl(stepCb, token)
    given sigilGiven: Sigil = this
    Task {
      Sigil.activeFrameworkWorkflows.put(token.workflowId, record)
      ()
    }.flatMap(_ => RunUnit.execute(new FunctionRunUnit[A](
      label = label,
      workflowType = workflowType,
      conversationId = conversationId,
      run = task(control),
      cleanup = Task {
        Sigil.activeFrameworkWorkflows.remove(token.workflowId)
        ()
      },
      cancellationToken = Some(token)
    )))
  }

  /** Convenience overload: wrap a Task that doesn't need to emit
    * intermediate `Step` Notices and doesn't poll cancellation
    * itself (the wrapper still aborts cleanly on cancellation
    * before the task runs, but won't interrupt mid-execution).
    * Bug #50 / #51. */
  final def runAsFrameworkWorkflow[A](workflowType: String,
                                      label: String,
                                      conversationId: Option[Id[Conversation]],
                                      task: Task[A]): Task[A] =
    runAsFrameworkWorkflow(workflowType, label, conversationId)(c => c.token.guard(task))

  /** Snapshot list of every framework workflow currently in flight.
    * Read-only; the records expose ids, type, label, conversation
    * scope, start timestamp, and cancellation token. Used by
    * `cancel_framework_workflow` and the activity-list surface.
    * Bug #51. */
  final def activeFrameworkWorkflows: List[ActiveFrameworkWorkflow] = {
    import scala.jdk.CollectionConverters.*
    Sigil.activeFrameworkWorkflows.values().asScala.toList.sortBy(_.startedAtMillis)
  }

  /** Cancel an in-flight framework workflow by id. Idempotent —
    * cancelling a finished or already-cancelled workflow is a no-
    * op. Returns whether this call flipped the flag (informational
    * for the caller — the workflow's body still has to reach a
    * checkpoint to honour it). Bug #51. */
  final def cancelFrameworkWorkflow(workflowId: String, reason: String): Boolean =
    Option(Sigil.activeFrameworkWorkflows.get(workflowId))
      .map(_.cancellationToken.cancel(reason))
      .getOrElse(false)

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
        // Uncapped canonical read returns the conversation's whole
        // event log oldest-first (the live-edge merge picks up any
        // in-flight iteration events); the trailing `limit` events
        // are the most-recent window the snapshot delivers.
        eventsFor(convId, maxMessages = None).flatMap { page =>
          val cap = math.max(0, limit)
          val sorted = page.events
          val window = if (sorted.length <= cap) sorted else sorted.drop(sorted.length - cap)
          val hasMore = sorted.length > cap
          publishTo(fromViewer, sigil.signal.ConversationSnapshot(convId, window.toVector, hasMore))
        }

      case sigil.signal.RequestConversationHistory(convId, beforeMs, limit) =>
        // Uncapped canonical read with an exclusive upper timestamp
        // bound returns every event older than the `beforeMs` cursor,
        // oldest-first; the trailing `limit` of that set is the page
        // closest to the cursor.
        eventsFor(convId, maxMessages = None, maxTimestamp = Some(lightdb.time.Timestamp(beforeMs))).flatMap { page =>
          val cap = math.max(0, limit)
          val sorted = page.events
          val window = if (sorted.length <= cap) sorted else sorted.drop(sorted.length - cap)
          val hasMore = sorted.length > cap
          publishTo(fromViewer, sigil.signal.ConversationHistorySnapshot(convId, window.toVector, hasMore))
        }

      // -- tool listing vocabulary (BUGS.md #38) --

      case sigil.signal.RequestToolList(spaces, kinds) =>
        listTools(fromViewer, spaces, kinds).flatMap { summaries =>
          publishTo(fromViewer, sigil.signal.ToolListSnapshot(summaries))
        }

      // -- conversation search vocabulary (bug #291) --
      // Symmetric to RequestConversationList for the search axis.
      // Reuses the same `searchConversationEvents` primitive that the
      // agent's `search_conversation` tool calls, so UI hits and agent
      // hits land identically. `conversationId` is required (no UI-side
      // "default conversation" concept in the framework — the panel
      // already knows which conversation it's filtering).

      case sigil.signal.RequestConversationSearch(query, convIdOpt, topicId, limit) =>
        convIdOpt match
          case None =>
            publishTo(fromViewer, sigil.signal.ConversationSearchSnapshot(query, Nil))
          case Some(convId) =>
            searchConversationEvents(convId, query, topicId, limit).map(_.map(searchHit)).flatMap { hits =>
              publishTo(fromViewer, sigil.signal.ConversationSearchSnapshot(query, hits))
            }

      // -- memory list vocabulary (sigil #292) --
      // Viewer-scoped (createdBy = fromViewer). Apps wanting wider
      // visibility override `handleNotice` to run their own query.
      case r: sigil.signal.RequestMemoryList =>
        withDB(_.memories.transaction(_.query.toList)).flatMap { all =>
          val q = r.query.map(_.toLowerCase).filter(_.nonEmpty)
          val filtered = all
            .filter(_.createdBy.exists(_.value == fromViewer.value))
            .filter(m => r.memoryType.forall(_ == m.memoryType))
            .filter(m => r.pinned.forall(_ == m.pinned))
            .filter(m => !r.hasLocation || m.location.isDefined)
            .filter(m => q.forall(needle =>
              m.fact.toLowerCase.contains(needle) ||
                m.label.toLowerCase.contains(needle) ||
                m.summary.toLowerCase.contains(needle)))
            .take(math.max(0, r.limit))
            .map(sigil.tool.model.MemoryListEntry.from)
          publishTo(fromViewer, sigil.signal.MemoryListSnapshot(filtered))
        }

      // -- model catalog vocabulary (sigil #293) --
      // Global (no per-viewer scoping); apps wanting per-tenant
      // restrictions override.
      case r: sigil.signal.RequestModelCatalog =>
        val filtered = cache.all
          .filter(m => r.provider.forall(_.equalsIgnoreCase(m.provider)))
          .filter(m => r.modality.forall(md =>
            m.architecture.modality.equalsIgnoreCase(md) ||
              m.architecture.inputModalities.exists(_.equalsIgnoreCase(md))))
          .filter(m => r.query.map(_.toLowerCase).filter(_.nonEmpty).forall(needle =>
            m.name.toLowerCase.contains(needle) ||
              m._id.value.toLowerCase.contains(needle)))
        publishTo(fromViewer, sigil.signal.ModelCatalogSnapshot(filtered))

      // -- conversation lifecycle (sigil #300) --
      // Client-initiated clear/delete. The framework runs the action
      // (which broadcasts the corresponding outbound ConversationCleared
      // / ConversationDeleted Notice to every viewer) on behalf of the
      // requesting viewer. Symmetric to every other Request* Notice
      // above — the inbound shape is the verb, the broadcast shape is
      // the confirmation.
      case sigil.signal.RequestConversationClear(convId) =>
        clearConversation(convId, fromViewer)
      case sigil.signal.RequestConversationDelete(convId) =>
        deleteConversation(convId)

      // -- viewer-state + stored-file vocabularies --
      // Handled by the ViewerStateOps mixin. The Notice subtypes there
      // are disjoint from the framework-level ones above, so dispatch
      // order is irrelevant.
      case other if viewerStateNotices.isDefinedAt((other, fromViewer)) =>
        viewerStateNotices((other, fromViewer))

      case _ => Task.unit
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

  /** In-flight provider HTTP-stream cancel handles, keyed per
    * (agent, conversation). A [[Provider]] registers its spice
    * `StreamHandle.cancel` here when an agent turn starts streaming and
    * deregisters when the stream terminates; [[applyStop]] looks up the
    * matching handle on a `Stop` and aborts the in-flight call so the
    * generated-then-discarded output tokens aren't billed. */
  final val providerStreams: sigil.provider.ProviderStreamRegistry =
    new sigil.provider.ProviderStreamRegistry

  /** Per-claim progress-checkpoint state. Keyed by the AgentState id
    * that owns the claim. Carries the prior checkpoint's `currentStatus`
    * (anchor for the next checkpoint's "did things change?" question)
    * and the count of consecutive `meaningfulProgress = false`
    * checkpoints — the framework intervenes when the count reaches
    * [[consecutiveNoProgressLimit]]. Populated on first checkpoint;
    * cleared on `releaseClaim`. */
  private final case class CheckpointState(@volatile var lastStatus: Option[String],
                                            @volatile var noProgressStreak: Int)
  private final val checkpointStates: ConcurrentHashMap[Id[Event], CheckpointState] = new ConcurrentHashMap()

  /** On a [[Stop]] event, set the matching flag(s): one specific agent if
    * `targetParticipantId` is set, else every agent in the conversation.
    * Also aborts any in-flight provider HTTP stream for the matching
    * agent(s) via [[providerStreams]] so the call doesn't drain to
    * natural completion (which still bills the discarded output
    * tokens). Also logs the stop (with `reason`, if supplied) so
    * operators can see where stops originate — otherwise `Stop.reason`
    * would be metadata that only shows up if someone trawls the event
    * log. */
  private final def applyStop(signal: Signal): Task[Unit] = signal match {
    case s: Stop =>
      val setFlags = Task {
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
      // Abort the in-flight provider HTTP stream(s) for the targeted
      // agent (or every agent in the conversation when no target is
      // set). Best-effort — cancel is idempotent and a missing handle
      // is a no-op, so a Stop arriving between iterations just finds
      // nothing to cancel.
      setFlags.flatMap(_ => providerStreams.cancelFor(s.conversationId, s.targetParticipantId))
    case _ => Task.unit
  }

  /** If this signal settles a [[ModeChange]] to `Complete`, resolve the
    * Mode-source [[ActiveSkillSlot]] (via [[sigil.provider.Mode.skill]]) and write it into
    * the acting participant's projection on the view. */
  private final def maybeApplyModeSkill(signal: Signal): Task[Unit] = signal match {
    case mc: ModeChange if mc.state == EventState.Complete => applyModeSkill(mc)
    case d: sigil.signal.Delta =>
      withDB(_.eventsTransaction(d.conversationId)(_.get(d.target.asInstanceOf[Id[Event]]))).flatMap {
        case Some(mc: ModeChange) if mc.state == EventState.Complete => applyModeSkill(mc)
        case _ => Task.unit
      }
    case _ => Task.unit
  }

  private final def applyModeSkill(mc: ModeChange): Task[Unit] =
    updateProjection(mc.conversationId, mc.participantId) { proj =>
      val newModeId = mc.mode.id

      // Step 1 — archive any Discovery slot bound to a different mode,
      // clearing the live Discovery slot. The slot's bound mode is
      // tracked on `discoverySkillMode`, set when `activate_skill`
      // ran. If `discoverySkillMode` is None or already equals the
      // new mode, no archive needed.
      val (archiveMap, clearedDiscovery) = proj.discoverySkillMode match {
        case Some(boundMode) if boundMode != newModeId =>
          val archived = proj.activeSkills.get(SkillSource.Discovery) match {
            case Some(slot) => proj.lastDiscoverySkillByMode + (boundMode -> slot)
            case None       => proj.lastDiscoverySkillByMode
          }
          (archived, proj.activeSkills - SkillSource.Discovery)
        case _ => (proj.lastDiscoverySkillByMode, proj.activeSkills)
      }

      // Step 2 — restore an archived slot for the incoming mode (if
      // one was saved on a prior departure).
      val (restoredSkills, restoredDiscoveryMode, prunedArchive) =
        archiveMap.get(newModeId) match {
          case Some(slot) =>
            (clearedDiscovery + (SkillSource.Discovery -> slot), Some(newModeId), archiveMap - newModeId)
          case None =>
            (clearedDiscovery, proj.discoverySkillMode.filter(_ == newModeId), archiveMap)
        }

      // Step 3 — apply the new mode's bundled skill to the Mode slot.
      val withModeSkill = mc.mode.skill match {
        case Some(slot) => restoredSkills + (SkillSource.Mode -> slot)
        case None       => restoredSkills - SkillSource.Mode
      }

      proj.copy(
        activeSkills = withModeSkill,
        lastDiscoverySkillByMode = prunedArchive,
        discoverySkillMode = restoredDiscoveryMode
      )
    }

  /**
   * Maintain per-participant [[ParticipantProjection]] state as
   * events/deltas flow through `publish`. Bug #26 — frames live on
   * the events themselves (`Event.contextFrame`), so the publish
   * pipeline only needs to project the participant-side state
   * (recentTools, suggestedTools) here.
   *
   * Updates fire exactly once per source event, the moment the
   * event reaches `EventState.Complete`:
   *
   *   - Atomic Complete events — apply directly.
   *   - Events that start Active and settle later via a Delta —
   *     the Delta branch re-reads the target post-apply.
   */
  private final def updateView(signal: Signal): Task[Unit] = signal match {
    case e: Event if e.state == EventState.Complete =>
      applyParticipantProjectionFor(e)
    case d: sigil.signal.Delta =>
      withDB(_.eventsTransaction(d.conversationId)(_.get(d.target.asInstanceOf[Id[Event]]))).flatMap {
        case Some(target) if target.state == EventState.Complete => applyParticipantProjectionFor(target)
        case _ => Task.unit
      }
    case _ => Task.unit
  }

  /** Apply the participant-side projection updates implied by `event`
    * to the relevant [[ParticipantProjection]] record. Runs only on
    * Complete events. */
  private final def applyParticipantProjectionFor(event: Event): Task[Unit] =
    event match {
      case ti: ToolInvoke =>
        // Bug #230 — when the tool author declared
        // [[sigil.tool.Tool.suggestedNextTools]], append those
        // tool-names to the per-participant `suggestedTools` overlay
        // so the next turn's "Suggested tools" prompt section advertises
        // them. Additive merge — never clobbers prior find_capability
        // discoveries or pagination navigators; the overlay still
        // single-turn-decays at agent-loop release.
        val nextTools: List[sigil.tool.ToolName] =
          staticTools.find(_.name == ti.toolName).map(_.suggestedNextTools).getOrElse(Nil)
        // Record the invocation in the rolling-window cache with a
        // canonical args hash + short preview so the prompt renderer
        // can dedupe by (toolName, argsHash) and warn when the same
        // logical call repeats. Keep the most-recent
        // `recentToolInvocationsLimit` entries; older fall off the
        // tail.
        // #354 — a slow tool whose large result raced past the frame
        // settles Complete with a `Pending` outcome (the agent saw a
        // placeholder, not the result). Record `resulted = false` so the
        // duplicate-call cap doesn't count the agent's rational retry of a
        // never-resulted call as a spinning duplicate (which escalated the
        // tier to the ceiling and restricted the tool out of the roster).
        val resulted = ti.outcome != sigil.event.ToolOutcome.Pending
        val invocation = ti.input match {
          case Some(in) => sigil.conversation.RecentToolInvocation(
            toolName    = ti.toolName,
            argsHash    = sigil.tool.ToolInputCanonicalizer.argsHash(in),
            argsPreview = sigil.tool.ToolInputCanonicalizer.argsPreview(in),
            invokedAt   = ti.timestamp,
            resulted    = resulted
          )
          case None => sigil.conversation.RecentToolInvocation(
            toolName    = ti.toolName,
            argsHash    = "",
            argsPreview = "",
            invokedAt   = ti.timestamp,
            resulted    = resulted
          )
        }
        updateProjection(ti.conversationId, ti.participantId) { proj =>
          val recent = (invocation :: proj.recentToolInvocations).take(recentToolInvocationsLimit)
          val suggested =
            if (nextTools.isEmpty) proj.suggestedTools
            else (proj.suggestedTools ++ nextTools).distinct
          proj.copy(recentToolInvocations = recent, suggestedTools = suggested)
        }
      case cr: CapabilityResults =>
        // Sigil bug #226 — the per-loop `find_capability` cache is no
        // longer persisted; `FindCapabilityTool.executeResult` records
        // matches directly onto `TurnContext.discoveredCapabilities`
        // so the cache dies with the agent loop instead of polluting
        // every subsequent turn. The projection update here keeps the
        // `suggestedTools` overlay only — that's a single-turn decay
        // surface that drives the "Suggested tools" prompt section.
        updateProjection(cr.conversationId, cr.participantId) { proj =>
          val toolNames = cr.matches.collect {
            case m if m.capabilityType == sigil.tool.discovery.CapabilityType.Tool => sigil.tool.ToolName(m.name)
          }
          proj.copy(suggestedTools = toolNames)
        }
      case _ => Task.unit
    }

  // -- projection helpers --

  /** Fetch the [[ParticipantProjection]] for `(participantId, conversationId)`,
    * returning an empty seed if one hasn't been materialized yet. Empty
    * projections are NOT persisted — the projection only lands on disk
    * once an event drives an update. */
  def projectionFor(participantId: ParticipantId,
                    conversationId: Id[Conversation]): Task[ParticipantProjection] =
    withDB(_.participantProjections.transaction(_.get(ParticipantProjection.idFor(participantId, conversationId)))).map {
      case Some(p) => p
      case None    => ParticipantProjection.empty(participantId, conversationId)
    }

  /** Sigil #289 — predicate for cross-conversation reads. The
    * conversation-query tools (`search_conversation`, `reload_content`,
    * `query_tool_output`) call this before dispatching a read against
    * a `conversationId` that differs from the caller's current
    * conversation. Allowed when:
    *   - target == current (same conversation; trivially allowed)
    *   - target == current.parentConversationId (worker reading its
    *     parent)
    *   - target.parentConversationId == current.id (parent reading
    *     one of its workers)
    *
    * Anything else returns `Left(reason)` and the tool surfaces a
    * Failure with the reason text. The predicate is intentionally
    * narrow — it doesn't traverse the whole tree (no grandparents /
    * sibling-conversations). Apps that need wider cross-conversation
    * access override this hook. */
  def canReadConversation(currentConversationId: Id[Conversation],
                          targetConversationId: Id[Conversation]): Task[Either[String, Unit]] =
    if (currentConversationId == targetConversationId) Task.pure(Right(()))
    else withDB(_.conversations.transaction { tx =>
      for {
        current <- tx.get(currentConversationId)
        target  <- tx.get(targetConversationId)
      } yield (current, target) match {
        case (None, _) =>
          Left(s"current conversation `${currentConversationId.value}` not found")
        case (_, None) =>
          Left(s"target conversation `${targetConversationId.value}` not found")
        case (Some(c), Some(t)) =>
          val isParentOfTarget = t.parentConversationId.contains(c._id)
          val isChildOfTarget  = c.parentConversationId.contains(t._id)
          if (isParentOfTarget || isChildOfTarget) Right(())
          else Left(
            s"target conversation `${targetConversationId.value}` is not the current " +
              s"conversation, its parent, or one of its workers — cross-conversation " +
              "reads are limited to parent/worker relationships."
          )
      }
    })

  /** Most-recent [[sigil.event.ToolApproval]] for `(toolName,
    * conversationId)`, or `None` when the agent hasn't recorded a
    * decision yet. Sigil bug #83 — the orchestrator's consent gate
    * reads this before dispatching a `requiresUserConsent` tool;
    * apps can also call directly to surface "is this tool approved
    * in this conversation?" UX. */
  def latestToolApproval(toolName: sigil.tool.ToolName,
                         conversationId: Id[Conversation]): Task[Option[sigil.event.ToolApproval]] =
    withDB(_.eventsTransaction(conversationId)(_.list)).map { events =>
      events.iterator
        .collect { case ta: sigil.event.ToolApproval => ta }
        .filter(_.conversationId == conversationId)
        .filter(_.toolName == toolName)
        .toList
        .sortBy(_.timestamp.value)
        .lastOption
    }

  /** Materialize the rolling-window frames for a conversation by querying
    * `db.events` for Complete events with a non-empty
    * [[Event.contextFrame]], honoring the conversation's `clearedAt`
    * watermark. Returns frames in chronological (timestamp-ascending)
    * order.
    *
    * Bug #26 — replaces the legacy `viewFor` / `view.frames` lookup. */
  def framesFor(conversationId: Id[Conversation]): Task[Vector[ContextFrame]] =
    withDB(_.conversations.transaction(_.get(conversationId))).flatMap { convOpt =>
      val watermark = convOpt.flatMap(_.clearedAt).map(_.value).getOrElse(0L)
      withDB(_.eventsTransaction(conversationId)(_.list)).map { all =>
        all.iterator
          .filter(_.conversationId == conversationId)
          .filter(_.timestamp.value > watermark)
          .filter(_.state == EventState.Complete)
          .toVector
          .sortBy(_.timestamp.value)
          .flatMap(_.contextFrame)
      }
    }

  /**
   * Canonical paged read of a conversation's event log.
   *
   * The single "load a window of conversation history" API every
   * cold-path reader funnels through — wire replay, conversation
   * switch / scroll-back, the durable event-log adapter. Returns an
   * [[EventsPage]] of [[Event]]s in chronological (oldest-first
   * within the page) order.
   *
   * Paging is message-counted, not event-counted. `maxMessages`
   * caps the number of [[Message]] events on a page; every
   * non-Message event (ToolInvoke, ToolResults, ModeChange,
   * TopicChange, ...) interleaved between those messages is
   * included for free and does NOT consume budget — a chatty turn
   * full of tool calls can't crowd Messages out of the page. With
   * `maxMessages = None` the whole window comes back on `page` 0 in
   * a single uncapped page.
   *
   * Pages run newest-first: `page` 0 is the most recent
   * `maxMessages` messages, `page` 1 the `maxMessages` before that,
   * and so on. Within a page the events are still ordered
   * oldest-first.
   *
   *   - `page` 0 carries the live edge — trailing non-Message
   *     events newer than its newest Message (an in-flight
   *     ToolInvoke / ToolResults pair) — and merges in the
   *     conversation's still-uncommitted batched-iteration events
   *     so a session joining mid-iteration sees the full picture.
   *   - Interior pages (`page` > 0) are strictly the events between
   *     that page's first and last Message — no live-edge merge.
   *
   * `topicId` restricts the result to a single topic. `minTimestamp`
   * and `maxTimestamp` bound the window — both EXCLUSIVE (an event
   * at exactly the bound is not returned), matching the
   * resume-cursor semantics callers rely on. All three filters
   * compose with `maxMessages` and `page`.
   *
   * No viewer / visibility parameter — this is the raw store read.
   * [[MessageVisibility]] filtering belongs to the delivery layer
   * ([[canSee]] / [[viewerTransforms]]); callers that need
   * viewer-scoping apply it to the returned events.
   *
   * `EventsPage.hasMore` is `true` when older messages exist beyond
   * the returned page.
   */
  def eventsFor(conversationId: Id[Conversation],
                page: Int = 0,
                maxMessages: Option[Int] = None,
                topicId: Option[Id[Topic]] = None,
                minTimestamp: Option[Timestamp] = None,
                maxTimestamp: Option[Timestamp] = None): Task[EventsPage] = {
    import lightdb.filter.*
    val safePage = math.max(0, page)

    // In-memory filters applied identically to the committed DB rows
    // and to the batched-scope accumulator so both halves of a page-0
    // merge are narrowed the same way.
    def passesFilters(e: Event): Boolean =
      topicId.forall(t => e.topicId == t) &&
        minTimestamp.forall(min => e.timestamp.value > min.value) &&
        maxTimestamp.forall(max => e.timestamp.value < max.value)

    // Indexed conversation-scoped query, newest-first. The timestamp
    // bounds fold into the same indexed query; `topicId` is not an
    // indexed field, so it is applied in memory over the
    // conversation-narrowed set.
    val committed: Task[List[Event]] = withDB(_.events.transaction { tx =>
      val base = tx.query.filter(_ => Event.conversationId === conversationId.value)
      val lowered = minTimestamp.fold(base)(min => base.filter(_ => Event.timestamp > min.value))
      val bounded = maxTimestamp.fold(lowered)(max => lowered.filter(_ => Event.timestamp < max.value))
      bounded
        .sort(lightdb.Sort.ByField(Event.timestamp, lightdb.SortDirection.Descending))
        .toList
    }).map(_.filter(passesFilters))

    committed.map { committedDesc =>
      // Page 0 merges the conversation's still-uncommitted
      // batched-iteration events — broadcast to the hub already but
      // not yet in the DB. Keyed by id with the accumulator winning,
      // so an event the current iteration both committed-earlier and
      // re-deltaed resolves to its latest version.
      val mergedDesc: List[Event] =
        if (safePage == 0) {
          val inFlight = withDBScopeSnapshot(conversationId).filter(passesFilters)
          if (inFlight.isEmpty) committedDesc
          else {
            val byId = scala.collection.mutable.LinkedHashMap.empty[Id[Event], Event]
            committedDesc.foreach(e => byId.put(e._id, e))
            inFlight.foreach(e => byId.put(e._id, e))
            byId.values.toList.sortBy(-_.timestamp.value)
          }
        } else committedDesc

      eventsForPage(mergedDesc, safePage, maxMessages)
    }
  }

  /**
   * Snapshot the active batched-event scope's in-flight accumulator
   * for `conversationId`, or an empty list when no [[sigil.db.SigilDB.withBatchedEvents]]
   * scope is open. Read by [[eventsFor]] page 0. Synchronous —
   * `instance` is already resolved by the time any cold-path reader
   * runs, so this never blocks on DB init.
   */
  private def withDBScopeSnapshot(conversationId: Id[Conversation]): List[Event] =
    startedInstance.get() match {
      case Some(inst) => inst.db.batchedEventScope(conversationId).map(_.snapshot).getOrElse(Nil)
      case None       => Nil
    }

  /**
   * Carve `page` out of a newest-first event list using
   * message-counted paging.
   *
   *   - `maxMessages = None`: page 0 is the whole list; higher
   *     pages are empty. `hasMore` always false.
   *   - page 0: from the newest event through the `maxMessages`-th
   *     newest Message inclusive (so trailing non-Message events
   *     newer than the newest Message — the live edge — ride
   *     along). `hasMore` when more messages exist past it.
   *   - interior page p: strictly from that page's first (newest)
   *     Message through its last (oldest) Message.
   *
   * Returns the page re-sorted oldest-first.
   */
  private def eventsForPage(desc: List[Event], page: Int, maxMessages: Option[Int]): EventsPage =
    maxMessages match {
      case None =>
        if (page == 0) EventsPage(desc.reverse, hasMore = false)
        else EventsPage(Nil, hasMore = false)

      case Some(cap) if cap <= 0 =>
        EventsPage(Nil, hasMore = false)

      case Some(cap) =>
        // Indices of Message events in the newest-first list.
        val messageIdx: Vector[Int] =
          desc.iterator.zipWithIndex.collect { case (e, i) if e.isInstanceOf[Message] => i }.toVector
        val totalMessages = messageIdx.length
        val firstMsgOrdinal = page * cap
        if (firstMsgOrdinal >= totalMessages) EventsPage(Nil, hasMore = false)
        else {
          val lastMsgOrdinal = math.min(firstMsgOrdinal + cap - 1, totalMessages - 1)
          val sliceEnd = messageIdx(lastMsgOrdinal)
          val sliceStart = if (page == 0) 0 else messageIdx(firstMsgOrdinal)
          val pageDesc = desc.slice(sliceStart, sliceEnd + 1)
          val hasMore = lastMsgOrdinal + 1 < totalMessages
          EventsPage(pageDesc.reverse, hasMore = hasMore)
        }
    }

  /**
   * Monotonically advance a conversation's `clearedAt` watermark.
   * The curator's stage-3 shed calls this after it summarises the
   * oldest frames into a `ContextSummary`: the watermark moves past
   * the shed slice, the next turn's `framesFor` filters those frames
   * out, and the summary takes their place via `summariesFor` —
   * "compress once, recall many" actually amortises.
   *
   * Monotonicity is enforced inside the transactional modify: the
   * watermark only advances. A concurrent caller racing with a
   * smaller `at` is a no-op on the persisted row. Events stay in
   * `db.events` for `SearchConversationTool` recall; this only
   * filters the curator's rolling-window input. Bug #147.
   */
  def advanceClearedAt(conversationId: Id[Conversation], at: Timestamp): Task[Unit] =
    withDB(_.eventsTransaction(conversationId)(_.list)).flatMap { evs =>
      // #316 — a budget shed may never advance the watermark to or past
      // the current user task. `framesFor` keeps frames with
      // `timestamp > clearedAt`, so capping strictly below the most-
      // recent user-authored Standard Message makes it structurally
      // impossible for a shed to permanently filter the active task out
      // of context — regardless of any shed-logic bug. Old history
      // before the task still sheds (recoverable via search_conversation
      // / query_tool_output). Explicit conversation-clear sets `clearedAt`
      // directly and is intentionally unaffected by this cap.
      val taskTs: Option[Long] = evs.iterator.collect {
        case m: sigil.event.Message
          if m.conversationId == conversationId
          && m.role == sigil.event.MessageRole.Standard
          && !m.participantId.isInstanceOf[sigil.participant.AgentParticipantId] => m.timestamp.value
      }.maxOption
      val capped = taskTs match {
        case Some(t) if at.value >= t => Timestamp(t - 1)
        case _                        => at
      }
      withDB(_.conversations.transaction(_.modify(conversationId) {
        case Some(conv) =>
          val current = conv.clearedAt.map(_.value).getOrElse(0L)
          if (capped.value <= current) Task.pure(Some(conv))
          else Task.pure(Some(conv.copy(clearedAt = Some(capped), modified = Timestamp(Nowish()))))
        case None => Task.pure(None)
      })).unit
    }

  /** Fetch the [[EncodedContext]] cache row for this `(agentId,
    * conversationId, modelId)` triple, returning a fresh empty row if
    * none exists. Bug #26 — the curator looks this up per turn,
    * appends since-cursor frames via the active provider's
    * [[sigil.provider.Provider.appendFrame]], and persists the result.
    * Cache misses (no row, or `builtThrough` behind newest event id)
    * trigger an incremental rebuild.
    *
    * The cache shape is opaque to the framework — only the provider
    * that wrote the bytes understands them. Cross-model mixing is
    * structurally impossible because `modelId` is part of the cache
    * key. */
  def encodedContextFor(agentId: ParticipantId,
                        conversationId: Id[Conversation],
                        modelId: Id[Model]): Task[EncodedContext] =
    withDB(_.encodedContexts.transaction(_.get(EncodedContext.idFor(agentId, conversationId, modelId)))).map {
      case Some(c) => c
      case None    => EncodedContext.empty(agentId, conversationId, modelId)
    }

  /** Persist (or upsert) an [[EncodedContext]] cache row. Returns the
    * stored record (with `modified` and `lastAccessedAt` bumped to
    * `now()`). Apps that drive their own cache flows call this after
    * incrementally appending; the framework's curator does so
    * automatically. */
  def saveEncodedContext(cache: EncodedContext): Task[EncodedContext] = {
    val now = Timestamp(Nowish())
    val updated = cache.copy(modified = now, lastAccessedAt = now)
    withDB(_.encodedContexts.transaction(_.upsert(updated)))
  }

  /** Update a participant's [[ParticipantProjection]] in the projections
    * collection. Creates a fresh empty projection (with the deterministic
    * derived id) if none exists. Use from curators, tools, or any app
    * code that needs to mutate per-participant projection state.
    *
    * Sigil #291 — when `broadcast = true` (default), publishes a
    * [[sigil.signal.ParticipantProjectionUpdated]] Notice after the
    * write commits so multi-client UIs subscribed to this conversation
    * see the change without polling. Framework-internal cache writes
    * that no UI cares about (e.g. cached `previous_response_id`) pass
    * `broadcast = false`. App writes through the convenience methods
    * ([[setParticipantContext]], [[activateSkill]], etc.) broadcast
    * by default — the polling workaround that motivated this signal. */
  def updateProjection(conversationId: Id[Conversation], participantId: ParticipantId,
                       broadcast: Boolean = true)
                      (f: ParticipantProjection => ParticipantProjection): Task[Unit] =
    withDB(_.participantProjections.transaction(_.modify(ParticipantProjection.idFor(participantId, conversationId)) {
      case Some(proj) =>
        Task.pure(Some(f(proj).copy(modified = Timestamp(Nowish()))))
      case None =>
        Task.pure(Some(f(ParticipantProjection.empty(participantId, conversationId))
          .copy(modified = Timestamp(Nowish()))))
    })).flatMap {
      case Some(updated) if broadcast =>
        publish(sigil.signal.ParticipantProjectionUpdated(conversationId, participantId, updated))
      case _ => Task.unit
    }

  /** Convenience: set (or replace) a skill slot for a participant. Discovery
    * and User sources are driven through here by tools that want to activate
    * a skill; Mode-source slots are maintained by the framework via
    * [[sigil.provider.Mode.skill]] on `ModeChange`. */
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

  /** Cache a provider's per-(agent, conversation) server-side state
    * handle. OpenAI's Responses API populates this on every settle so
    * the next turn can pass `previous_response_id` and ship only the
    * delta input. `messageCount` is the rendered-message count at the
    * time of capture — the next call trims that many from the head
    * before sending. */
  def setProviderResponseState(conversationId: Id[Conversation],
                               participantId: ParticipantId,
                               responseId: String,
                               messageCount: Int): Task[Unit] =
    // Sigil #291 — framework-internal provider-cache write; no UI
    // surface mirrors this state, so suppress the broadcast.
    updateProjection(conversationId, participantId, broadcast = false)(
      proj => proj.copy(
        latestProviderResponseId = Some(responseId),
        latestProviderResponseMessageCount = Some(messageCount)
      )
    )

  /** Forget the cached provider response state for an (agent, conversation)
    * pair. Fires when the upstream API rejects `previous_response_id`
    * (`previous_response_not_found` — the id expired). Next turn falls
    * back to the full-transcript request shape. */
  def clearProviderResponseState(conversationId: Id[Conversation],
                                 participantId: ParticipantId): Task[Unit] =
    // Sigil #291 — framework-internal cache invalidation; suppress broadcast.
    updateProjection(conversationId, participantId, broadcast = false)(
      proj => proj.copy(
        latestProviderResponseId = None,
        latestProviderResponseMessageCount = None
      )
    )

  /** Convenience: advance a participant's last-read cursor in
    * `conversationId` to a specific event's server-stamped
    * timestamp. The framework looks up the event's authoritative
    * timestamp — clients never specify a wall-clock time, so
    * client-clock drift is moot. Bug #62.
    *
    * No-op when `readThrough` doesn't resolve (stale id, deleted
    * event). Idempotent: calling twice with the same id is
    * cheap. */
  def markRead(conversationId: Id[Conversation],
               participantId: ParticipantId,
               readThrough: Id[sigil.event.Event]): Task[Unit] =
    withDB(_.eventsTransaction(conversationId)(_.get(readThrough))).flatMap {
      case None    => Task.unit
      case Some(e) => markRead(conversationId, participantId, e.timestamp)
    }

  /** Direct-timestamp overload for the rare case where a caller
    * already has a server-authoritative `Timestamp` in hand
    * (replay tooling, batch catch-up scripts, etc.). Most code
    * should use the event-id overload above — that's the path
    * that's safe against client clock drift. Bug #62. */
  def markRead(conversationId: Id[Conversation],
               participantId: ParticipantId,
               lastReadAt: lightdb.time.Timestamp): Task[Unit] = {
    val stateId = sigil.event.ReadState.idFor(conversationId, participantId)
    withDB(_.eventsTransaction(conversationId)(_.get(stateId))).flatMap {
      case Some(_) =>
        publish(sigil.signal.ReadStateDelta(
          target         = stateId,
          conversationId = conversationId,
          participantId  = participantId,
          lastReadAt     = lastReadAt
        ))
      case None    =>
        // First read for this `(conv, participant)` — insert the
        // ReadState row. Subsequent advances mutate via
        // ReadStateDelta (no new event row).
        withDB(_.conversations.transaction(_.get(conversationId))).flatMap {
          case None       => Task.unit
          case Some(conv) =>
            publish(sigil.event.ReadState(
              participantId  = participantId,
              conversationId = conversationId,
              topicId        = conv.currentTopicId,
              lastReadAt     = lastReadAt,
              _id            = stateId
            ))
        }
    }
  }

  /** Read the current `lastReadAt` cursor for `(conversationId,
    * participantId)`. `None` if the participant has never marked
    * read in this conversation. Bug #62. */
  def readStateFor(conversationId: Id[Conversation],
                   participantId: ParticipantId): Task[Option[sigil.event.ReadState]] = {
    val stateId = sigil.event.ReadState.idFor(conversationId, participantId)
    withDB(_.eventsTransaction(conversationId)(_.get(stateId))).map {
      case Some(r: sigil.event.ReadState) => Some(r)
      case _                              => None
    }
  }

  /** Convenience: publish a [[sigil.event.Reaction]] event for the
    * given message. `removed = false` means "I'm reacting now",
    * `removed = true` means "I'm taking my reaction back." Last-
    * write-wins per `(messageId, participantId, emoji)` — consumers
    * reduce the event tail to find the current state.
    *
    * No-ops if the conversation isn't found (caller's `conversationId`
    * was stale). Bug #61. */
  def react(conversationId: Id[Conversation],
            participantId: ParticipantId,
            messageId: Id[sigil.event.Event],
            emoji: String,
            removed: Boolean = false): Task[Unit] =
    withDB(_.conversations.transaction(_.get(conversationId))).flatMap {
      case None => Task.unit
      case Some(conv) =>
        publish(sigil.event.Reaction(
          participantId  = participantId,
          conversationId = conversationId,
          topicId        = conv.currentTopicId,
          messageId      = messageId,
          emoji          = emoji,
          removed        = removed
        ))
    }

  /** Convenience: publish a [[Stop]] event for the conversation. Lets
    * UI layers (stop button) and programmatic callers issue stops
    * without reconstructing the event by hand. For LLM-initiated stops
    * use [[sigil.tool.core.CancelTool]] instead. */
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

  /** Per-conversation cache for non-critical memory retrieval results.
    * Inter-message-stable — populated lazily on first curate-time
    * read for a conversation, invalidated by
    * [[sigil.pipeline.MemoryCacheInvalidationEffect]] on (a) a
    * non-agent message settling and (b) a topic-change `Switch`
    * settling. See [[sigil.conversation.compression.MemoryRetrievalCache]]
    * for the caching contract.
    *
    * Apps don't typically interact with this directly — the
    * [[sigil.conversation.compression.StandardMemoryRetriever]] consults
    * it transparently via [[cachedMemoryRetrieve]]. Public for
    * observability (specs / app debug tools peek without mutating). */
  final val memoryRetrievalCache: sigil.conversation.compression.MemoryRetrievalCache =
    new sigil.conversation.compression.MemoryRetrievalCache

  /** Read or compute a [[sigil.conversation.compression.MemoryRetrievalResult]]
    * for `conversationId`. The compute thunk runs at most once per
    * (conversation, cache lifetime). */
  def cachedMemoryRetrieve(conversationId: Id[Conversation],
                           compute: => Task[sigil.conversation.compression.MemoryRetrievalResult]
                          ): Task[sigil.conversation.compression.MemoryRetrievalResult] =
    memoryRetrievalCache.getOrCompute(conversationId, compute)

  /** Invalidate the cached retrieval for a conversation — called by
    * [[sigil.pipeline.MemoryCacheInvalidationEffect]] on appropriate
    * settled events. Idempotent. */
  def invalidateMemoryRetrievalCache(conversationId: Id[Conversation]): Unit =
    memoryRetrievalCache.invalidate(conversationId)

  /** All versions of a keyed memory in `spaceId`, chronologically
    * (oldest first by `created`). */
  def memoryHistory(key: String, spaceId: SpaceId): Task[List[ContextMemory]] =
    if (key.isEmpty) Task.pure(Nil)
    else withDB(_.memories.transaction { tx =>
      import lightdb.filter.*
      tx.query
        .filter(m => (m.spaceIdValue === spaceId.value) && (m.key === Some(key)))
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
    * retrieval paths (`semantic_search`, MemoryRetriever) so apps can
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
          db.eventsTransaction(conversationId) { tx =>
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
    withDB(_.eventsTransaction(conversationId)(_.list)).map { all =>
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

  /** Adapt a single [[Event]] into the [[sigil.tool.model.SearchConversationHit]]
    * shape both the agent tool and the [[sigil.signal.ConversationSearchSnapshot]]
    * notice emit. Apps with custom event subtypes can override to contribute
    * richer snippets — but matching the existing format keeps UI and tool
    * results renderable from the same view code. */
  protected def searchHit(e: Event): sigil.tool.model.SearchConversationHit = {
    val snippet = e match {
      case m: Message =>
        m.content.collect { case ResponseContent.Text(t) => t }.mkString(" ").take(280)
      case tc: TopicChange => s"[topic change] ${tc.newLabel}"
      case other           => other.getClass.getSimpleName
    }
    sigil.tool.model.SearchConversationHit(
      eventId       = e._id.value,
      timestamp     = e.timestamp.value,
      participantId = e.participantId.value,
      topicId       = e.topicId.value,
      eventType     = e.getClass.getSimpleName,
      snippet       = snippet
    )
  }

  /** Maintain materialized projections on the [[Conversation]] record:
    *   - `currentMode` tracks the latest [[ModeChange]]
    *   - `topics` (the navigation stack) tracks the latest [[TopicChange]]:
    *     - `Switch` either pushes a new entry (if the topic isn't on the
    *       stack) or truncates the stack back to that entry (if it is —
    *       the natural "return to prior topic" flow)
    *     - `Rename` mutates the active entry's label + summary in place
    *   - `cost` is incremented when a [[Message]] settles whose
    *     `modelId` resolves to a known [[Model]] in
    *     [[sigil.cache.ModelRegistry]] (USD; per-token pricing
    *     multiplied by [[sigil.provider.TokenUsage]]). Each non-zero
    *     increment publishes a [[sigil.signal.ConversationCostUpdated]]
    *     Notice with the new total + per-Message delta.
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
        withDB(_.eventsTransaction(d.conversationId)(_.get(d.target.asInstanceOf[Id[Event]])))
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
      case Some(cc: sigil.event.ComplexityChange) =>
        // Sigil bug #177 — symmetric with ModeChange. The pin/unpin
        // tools mutate `pinnedComplexity` themselves; this projection
        // arm keeps the event the source of truth so future emitters
        // (e.g. classifier-driven auto-escalation) flow through the
        // same path without duplicating the conversation modify.
        withDB(_.conversations.transaction(_.modify(cc.conversationId) {
          case Some(conv) if conv.pinnedComplexity != cc.newTier =>
            Task.pure(Some(conv.copy(pinnedComplexity = cc.newTier, modified = Timestamp(Nowish()))))
          case Some(conv) => Task.pure(Some(conv))
          case None       => Task.pure(None)
        })).unit
      case Some(tc: TopicChange) =>
        applyTopicChangeToStack(tc)
      case Some(m: Message) =>
        applyEventCostToConversation(m.conversationId, m.modelId, m.usage)
      case Some(t: ToolInvoke) =>
        // For tool-call-only turns (change_mode, cancel, find_capability,
        // …) the per-turn usage attaches to the ToolInvoke via
        // [[sigil.signal.ToolDelta]]. Cost projection picks it up off
        // the same `modelId × usage` pair that drives the Message path.
        applyEventCostToConversation(t.conversationId, t.modelId, t.usage)
      case _ => Task.unit
    }
  }

  /** Increment [[Conversation.cost]] for a settled cost-bearing event
    * (either a [[Message]] or a [[ToolInvoke]]) whose `modelId` is
    * known to the [[sigil.cache.ModelRegistry]].
    *
    * Math: per-token pricing × token counts (USD). Cache miss,
    * `modelId = None`, or zero usage → no-op. On a non-zero delta,
    * publishes a [[sigil.signal.ConversationCostUpdated]] Notice
    * carrying the new running total + the per-event delta. */
  private final def applyEventCostToConversation(
    conversationId: Id[Conversation],
    modelId: Option[Id[Model]],
    usage: TokenUsage
  ): Task[Unit] = {
    // Bug #91 — `findTolerant` lets an event stamped with a bare id
    // (`gpt-5.5`) match a registry entry indexed by its prefixed id
    // (`openai/gpt-5.5`). Without it, every cost projection on a
    // bare-id event silently misses and the conversation's running
    // total stays at zero.
    val deltaOpt: Option[BigDecimal] = modelId.flatMap { mid =>
      cache.findTolerant(mid).map(model => Sigil.costFor(model.pricing, usage))
    }.filter(_ > 0)
    deltaOpt match {
      case None => Task.unit
      case Some(delta) =>
        withDB(_.conversations.transaction(_.modify(conversationId) {
          case None => Task.pure(None)
          case Some(conv) =>
            Task.pure(Some(conv.copy(cost = conv.cost + delta, modified = Timestamp(Nowish()))))
        })).flatMap {
          case Some(updated) =>
            publish(sigil.signal.ConversationCostUpdated(
              conversationId = updated._id,
              cost = updated.cost,
              delta = delta
            ))
          case None => Task.unit
        }
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
  /** Topic labels the classifier should NEVER match against as
    * "<prior label>" — generic catch-alls (agent's own name, app
    * name, "Greeting", "Initial setup", "Chat", "Help") that
    * would otherwise pull every subsequent turn back to them via
    * the prior-match path. Apps that brand the agent override and
    * include the agent's display name. Sigil bug #89. */
  def reservedTopicLabels: Set[String] = Set(
    "greeting", "initial setup", "chat", "help", "assistant", "conversation"
  )

  def classifyTopicShift(modelId: Id[Model],
                         chain: List[ParticipantId],
                         current: TopicEntry,
                         priors: List[TopicEntry],
                         proposedLabel: String,
                         proposedSummary: String,
                         userMessage: String,
                         // #357 — when supplied, the classifier consult routes via
                         // `auxModelFor` so an app that set `pinCoversAuxiliaryCalls`
                         // sends this aux call to the conversation's pinned model.
                         // `None` keeps the cost-first `routedModelFor` path.
                         conversationId: Option[Id[Conversation]] = None): Task[TopicShiftResult] = {
    // Bug #89 — strip reserved labels (agent name, "Greeting",
    // "Initial setup", etc.) from the prior list before the
    // classifier sees them. The `priors` parameter is the
    // conversation's persisted topic history (orchestrator passes
    // `request.previousTopics`). Filtering at the classifier
    // boundary stops an early seed topic that happens to be the
    // agent's own name from pulling every subsequent turn back to
    // it via "<prior label>" matching.
    val reservedLowered = reservedTopicLabels.map(_.toLowerCase)
    val filteredPriors = priors.filterNot(p => reservedLowered.contains(p.label.toLowerCase))
    // Bug #92 — also redact reserved-label substrings from the user
    // message before the classifier sees it. Anthropic + similar
    // providers don't grammar-constrain tool args, so the model can
    // hallucinate a `kind` straight from the user text it just read
    // ("Hi Sage" → kind="Sage"). Replacing the substrings shields
    // the classifier from echoing agent-name leakage as a topic
    // verdict. Match is case-insensitive, whole-word.
    val sanitizedUserMessage = reservedTopicLabels.foldLeft(userMessage) { (acc, term) =>
      acc.replaceAll(s"(?i)\\b${java.util.regex.Pattern.quote(term)}\\b", "[reserved]")
    }
    val priorsBlock =
      if (filteredPriors.isEmpty) "  (none)"
      else filteredPriors.map(p => s"  - \"${p.label}\" — ${p.summary}").mkString("\n")
    val systemPrompt =
      """You categorize how a proposed topic relates to a conversation's existing topics.
        |Pick exactly one value from the enum:
        |  - "NoChange" — proposed is the same subject as Current; nothing new to label.
        |  - "Refine"   — same subject as Current, but proposed is a sharper / more specific label.
        |  - <prior label> — same subject as one of the prior topics. The user is returning.
        |  - "New"      — genuinely different from Current and all priors.""".stripMargin
    val userPrompt =
      s"""User just said: ${quote(sanitizedUserMessage)}
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
    val tool = new TopicClassifierTool(filteredPriors.map(_.label))
    // Sampling settings are baseline `temperature = 0.0` (deterministic
    // classification) — but only when the model supports it. GPT-5 +
    // reasoning-only families (o1, o3, …) hard-reject `temperature`,
    // so consult [[supportsParameter]] before including it. The
    // provider layer also filters as a safety net; gating here too
    // means the framework doesn't emit a parameter it knows the
    // model will reject.
    val started = System.currentTimeMillis()
    // Classifier consults route through the tool's declared work type
    // to the cheap classification tier and use its canonical
    // consultSettings (bounded output + reasoning off); temperature is
    // stamped when the routed model accepts it for deterministic
    // classification.
    val resolveClassifierModel = conversationId match {
      case Some(cid) => auxModelFor(cid, tool.consultWorkType, chain, modelId)
      case None      => routedModelFor(tool.consultWorkType, chain, modelId)
    }
    resolveClassifierModel.flatMap { routedModelId =>
      val classifierSettings = {
        val base = ConsultTool.settingsFor(tool)
        if (supportsParameter(routedModelId, "temperature")) base.copy(temperature = Some(0.0))
        else base
      }
      ConsultTool.invokeRich[sigil.tool.consult.TopicClassifierInput](
        sigil = this,
        modelId = routedModelId,
        chain = chain,
        systemPrompt = systemPrompt,
        userPrompt = userPrompt,
        tool = tool,
        generationSettings = classifierSettings
      )
    }.flatMap {
      case sigil.tool.consult.ConsultOutcome.Parsed(input) => Task.pure(input.kind match {
        case "NoChange" => TopicShiftResult.NoChange
        case "Refine"   => TopicShiftResult.Refine
        case "New"      => TopicShiftResult.New
        case other      =>
          // Bug #92 — defensive validator. If the classifier returns
          // a reserved label (agent name etc.) it must NOT be a Return
          // target even when it accidentally matches a persisted prior;
          // force `New` and log so future drift is observable.
          if (reservedLowered.contains(other.toLowerCase)) {
            scribe.warn(s"classifyTopicShift: model returned reserved label '$other' as kind — forcing New")
            TopicShiftResult.New
          } else {
            // Bug #89 — Return must hit a prior that survived the
            // reserved-label filter. If somehow the classifier
            // returned a label that was filtered out (or never
            // existed), fall back to "New" rather than NoChange so
            // the new topic actually gets recorded.
            filteredPriors.find(_.label == other)
              .map(TopicShiftResult.Return(_))
              .getOrElse {
                scribe.warn(s"classifyTopicShift: out-of-enum kind '$other' (priors=${filteredPriors.map(_.label).mkString(",")}) — falling back to New")
                TopicShiftResult.New
              }
          }
      })
      // Sigil bug #197 — `NoOpinion` is a legitimate "model declined
      // to call the tool"; default to NoChange silently. `Truncated`
      // / `Failed` are diagnostic events — surface a Failed
      // FrameworkWorkflowNotice so the gap is visible to operators
      // and downstream code rather than being a zero-event silence.
      case sigil.tool.consult.ConsultOutcome.NoOpinion =>
        Task.pure(TopicShiftResult.NoChange)
      case t @ sigil.tool.consult.ConsultOutcome.Truncated(_, _, _) =>
        emitClassifierFailedNotice(
          "classifyTopicShift",
          s"truncated at finish_reason: length (promptTokens=${t.promptTokens.getOrElse("?")}, " +
            s"completionTokens=${t.completionTokens.getOrElse("?")}) — falling back to NoChange",
          started
        ).map(_ => TopicShiftResult.NoChange)
      case sigil.tool.consult.ConsultOutcome.Failed(cause) =>
        emitClassifierFailedNotice(
          "classifyTopicShift",
          s"${cause.getClass.getSimpleName}: ${Option(cause.getMessage).getOrElse("")} — falling back to NoChange",
          started
        ).map(_ => TopicShiftResult.NoChange)
    }
  }

  /** Surface a `ConsultOutcome.Truncated` / `Failed` from a framework-
    * internal classifier consult as a [[sigil.signal.FrameworkWorkflowNotice]]
    * with `Failed` phase. Wire subscribers (clients, dashboards) see
    * the gap; operator logs get a scribe.warn with the same reason. */
  private final def emitClassifierFailedNotice(workflowType: String,
                                               reason: String,
                                               startedMs: Long): Task[Unit] = {
    scribe.warn(s"$workflowType $reason")
    publish(sigil.signal.FrameworkWorkflowNotice(
      workflowId    = java.util.UUID.randomUUID().toString,
      workflowType  = workflowType,
      phase         = sigil.signal.FrameworkWorkflowPhase.Failed(
        reason     = reason,
        durationMs = System.currentTimeMillis() - startedMs
      )
    )).handleError(_ => Task.unit)
  }

  /**
   * Resolve a respond's declared `topicLabel` + `topicSummary` against
   * the conversation's topic stack. Returns the [[TopicChange]]
   * event(s) the caller should emit ahead of the respond's
   * [[Message]] — empty when the proposed topic is the active one
   * (no-op shift).
   *
   * Shared by both code paths that handle respond emission:
   *
   *   - [[sigil.orchestrator.Orchestrator]]'s streaming branch (when
   *     respond's content streamed live via ContentBlockDeltas — the
   *     orchestrator wraps the result as `Signal` and emits before
   *     the Message-settle delta).
   *   - [[sigil.tool.core.RespondTool.executeResult]] for atomic
   *     respond calls (llama.cpp grammar-constrained, OpenAI strict-
   *     mode, Anthropic, Google — every provider whose respond
   *     materialises as a function call). The tool's stream emits the
   *     TopicChange events as ordinary `Event`s; the orchestrator's
   *     `runExecute` pairs each with a settling `StateDelta`.
   *
   * Side effect: when the classifier returns `Refine`, the active
   * Topic record's label/summary is rewritten in-place; on `New`, a
   * fresh Topic record is persisted. Both paths emit the matching
   * `TopicChange` event.
   *
   * Fast-path shortcuts avoid the classifier LLM call when the
   * answer is unambiguous from a label match alone (active topic's
   * label, or any prior topic's label).
   */
  def resolveTopicShift(proposedLabel: String,
                        proposedSummary: String,
                        caller: ParticipantId,
                        conversation: Conversation,
                        currentTopic: TopicEntry,
                        previousTopics: List[TopicEntry],
                        modelId: Id[Model],
                        chain: List[ParticipantId],
                        userMessage: String): Task[List[Event]] = {
    if (proposedLabel.equalsIgnoreCase(currentTopic.label)) Task.pure(Nil)
    else previousTopics.find(_.label.equalsIgnoreCase(proposedLabel)) match {
      case Some(prior) =>
        Task.pure(List(buildSwitch(caller, conversation._id, currentTopic.id, prior.id, prior.label, prior.summary)))
      case None =>
        classifyTopicShift(modelId, chain, currentTopic, previousTopics, proposedLabel, proposedSummary, userMessage,
                           conversationId = Some(conversation._id)).flatMap {
          case TopicShiftResult.NoChange       => Task.pure(Nil)
          case TopicShiftResult.Refine         => resolveRenameTopic(proposedLabel, proposedSummary, caller, conversation, currentTopic.id)
          case TopicShiftResult.New            => resolveNewTopic(proposedLabel, proposedSummary, caller, conversation, currentTopic.id)
          case TopicShiftResult.Return(prior)  =>
            Task.pure(List(buildSwitch(caller, conversation._id, currentTopic.id, prior.id, prior.label, prior.summary)))
        }
    }
  }

  private def buildSwitch(caller: ParticipantId,
                          convId: Id[Conversation],
                          previousTopicId: Id[Topic],
                          newTopicId: Id[Topic],
                          newLabel: String,
                          newSummary: String): TopicChange =
    TopicChange(
      kind           = TopicChangeKind.Switch(previousTopicId = previousTopicId),
      newLabel       = newLabel,
      newSummary     = newSummary,
      participantId  = caller,
      conversationId = convId,
      topicId        = newTopicId
    )

  private def resolveNewTopic(proposedLabel: String,
                              proposedSummary: String,
                              caller: ParticipantId,
                              conversation: Conversation,
                              previousTopicId: Id[Topic]): Task[List[Event]] = {
    val created = Topic(
      conversationId = conversation._id,
      label          = proposedLabel,
      summary        = proposedSummary,
      createdBy      = caller
    )
    withDB(_.topics.transaction(_.upsert(created))).map { stored =>
      List(buildSwitch(caller, conversation._id, previousTopicId, stored._id, stored.label, stored.summary))
    }
  }

  private def resolveRenameTopic(proposedLabel: String,
                                 proposedSummary: String,
                                 caller: ParticipantId,
                                 conversation: Conversation,
                                 currentTopicId: Id[Topic]): Task[List[Event]] =
    withDB(_.topics.transaction(_.get(currentTopicId))).flatMap {
      case None                                  => Task.pure(Nil)
      case Some(current) if current.labelLocked  => Task.pure(Nil)
      case Some(current)                         =>
        val renamed = current.copy(label = proposedLabel, summary = proposedSummary, modified = Timestamp())
        withDB(_.topics.transaction(_.upsert(renamed))).map { _ =>
          List(TopicChange(
            kind           = TopicChangeKind.Rename(previousLabel = current.label),
            newLabel       = proposedLabel,
            newSummary     = proposedSummary,
            participantId  = caller,
            conversationId = conversation._id,
            topicId        = current._id
          ))
        }
    }

  /** Persist the agent's per-turn keyword push (from `RespondInput.keywords`)
    * onto the conversation as `currentKeywords`. The non-critical memory
    * retriever reads this on the next turn — no event is emitted because
    * the keywords are turn-state, not durable history. Empty input is a
    * no-op so the agent isn't forced to push a list it doesn't have.
    *
    * Called from both [[sigil.tool.core.RespondTool]] and
    * [[sigil.orchestrator.Orchestrator]]'s streaming-respond branch so
    * the keyword side effect fires regardless of which respond path
    * materialised. */
  def updateConversationKeywords(conversationId: Id[Conversation],
                                 keywords: List[String]): Task[Unit] = {
    val cleaned = keywords.iterator.map(_.trim).filter(_.nonEmpty).toVector.distinct
    if (cleaned.isEmpty) Task.unit
    else withDB(_.conversations.transaction(_.modify(conversationId) {
      case Some(c) => Task.pure(Some(c.copy(currentKeywords = cleaned, modified = Timestamp())))
      case None    => Task.pure(None)
    })).unit
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
                      parentConversationId: Option[Id[Conversation]] = None,
                      pinnedComplexity: Option[sigil.provider.Complexity] = None,
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
      parentConversationId = parentConversationId,
      pinnedComplexity = pinnedComplexity,
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
      //
      // Sigil #350 — never greet in a worker/delegated sub-conversation. Its
      // opener is the brief (`delegate_task` posts it), and there is no user
      // to greet. A greeting there makes the supervisor run "how can I help?"
      // turns AND poisons the worker's context (the worker mirrors the
      // greeting). Suppress whenever the conversation has a parent.
      _      <- if (stored.parentConversationId.isDefined) Task.unit
                else Task.sequence(stored.participants.collect {
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
   * (Sage's per-conversation project, an app's project record)
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
   * Workspace for a conversation, walking the `parentConversationId`
   * chain when the conversation itself has no binding. A worker or
   * staging conversation (spawned by `delegate_task` / import staging)
   * starts with no workspace of its own — the app only bound a
   * workspace to the user-facing parent. Without this fallthrough a
   * delegated worker can discover `grep` / `read_file` but has no
   * project root to run them against, so it spins to its iteration
   * cap reporting "no workspace path" (sigil #325).
   *
   * Resolution: `workspaceFor(conversationId)` first; on `None`, load
   * the conversation and recurse into its `parentConversationId`.
   * Bounded depth guards against a malformed parent cycle. Reuses the
   * app's own `workspaceFor` override at every level — apps wire the
   * workspace once on the parent and every descendant inherits it.
   */
  def resolvedWorkspaceFor(conversationId: Id[Conversation]): Task[Option[java.nio.file.Path]] =
    resolvedWorkspaceFor(conversationId, depth = 8)

  private def resolvedWorkspaceFor(conversationId: Id[Conversation], depth: Int): Task[Option[java.nio.file.Path]] =
    workspaceFor(conversationId).flatMap {
      case found @ Some(_)    => Task.pure(found)
      case None if depth <= 0 => Task.pure(None)
      case None =>
        withDB(_.conversations.transaction(_.get(conversationId))).flatMap {
          case Some(conv) =>
            conv.parentConversationId match {
              case Some(parentId) => resolvedWorkspaceFor(parentId, depth - 1)
              case None           => Task.pure(None)
            }
          case None => Task.pure(None)
        }
    }

  /**
   * Resolve the [[FileSystemContext]] a conversation's tools operate
   * against — the seam the framework writes large tool output through.
   * When a tool result overflows [[inlineContentThreshold]], the result
   * is written to a file under this context's root
   * (`.sigil/output/<convId>/<tool>-<callId>.txt`) and the agent recovers
   * it with the filesystem tools it already has (`grep` / `read_file`),
   * rather than navigating a bespoke reference handle. Because the write
   * goes through the SAME context the agent's `grep`/`read_file` use, the
   * file lands where those tools run — including the ProxyTool remote-fs
   * case (the file is on the user's machine, where its grep runs).
   *
   * Default: wrap [[resolvedWorkspaceFor]] in a [[LocalFileSystemContext]]
   * rooted at the workspace; `None` when no workspace is bound (the
   * overflow path then falls back to inline truncate-and-tell). Apps that
   * route filesystem work through a remote/proxied backend override this
   * to return the matching context.
   */
  def fileSystemContextFor(conversationId: Id[Conversation]): Task[Option[FileSystemContext]] =
    resolvedWorkspaceFor(conversationId).map(_.map(path => new LocalFileSystemContext(Some(path))))

  /**
   * Maximum delegation depth — how deep a worker→sub-worker chain may go
   * (sigil #348). A top-level agent → worker is depth 1; that worker →
   * sub-worker is depth 2; a `delegate_task` that would exceed this is
   * refused. This still lets a worker modularize (fan out genuinely
   * separable sub-tasks at the allowed depth) while making runaway
   * worker→worker→worker recursion structurally impossible even when the
   * doer-framing prompt doesn't fully hold on a weak model. Apps override
   * to widen or tighten.
   */
  def maxDelegationDepth: Int = 2

  /**
   * Delegation depth of a conversation — the number of
   * `parentConversationId` hops to the root. A top-level (user-facing)
   * conversation is depth 0; a worker sub-conversation is depth 1; a
   * sub-worker's is depth 2; etc. Bounded walk (mirrors
   * [[resolvedWorkspaceFor]]) so a malformed parent cycle can't loop.
   */
  def delegationDepth(conversationId: Id[Conversation]): Task[Int] =
    delegationDepth(conversationId, depth = 0, fuel = 16)

  private def delegationDepth(conversationId: Id[Conversation], depth: Int, fuel: Int): Task[Int] =
    if (fuel <= 0) Task.pure(depth)
    else withDB(_.conversations.transaction(_.get(conversationId))).flatMap {
      case Some(conv) =>
        conv.parentConversationId match {
          case Some(parentId) => delegationDepth(parentId, depth + 1, fuel - 1)
          case None           => Task.pure(depth)
        }
      case None => Task.pure(depth)
    }

  /**
   * Open a staging conversation that buffers events for a
   * long-running import workflow. The staging conv is a regular
   * [[Conversation]] row with `stagingFor = Some(target)` —
   * persisted records (events, memories, summaries) addressed to
   * its id are durable but logically isolated from the target
   * conversation until [[mergeStagingIntoMain]] flips the
   * conversationId references. On cancel / crash, the staging
   * conv plus its records are reaped by
   * [[OrphanStagingConversationSweep]] (or proactively cleaned via
   * [[deleteStagingConversation]]).
   *
   * Idempotent on `_id`. Apps typically construct the staging id
   * up front (e.g. `s"import-staging-${Unique()}"`) so the
   * workflow's run state can reference it.
   */
  def createStagingConversation(stagingId: Id[Conversation],
                                stagingFor: Id[Conversation]): Task[Conversation] = {
    val staging = Conversation(
      _id        = stagingId,
      topics     = Nil,
      stagingFor = Some(stagingFor)
    )
    withDB(_.conversations.transaction(_.upsert(staging)))
  }

  /**
   * Atomically commit a staging conversation's records into its
   * target. All [[sigil.event.Event]]s, [[sigil.conversation.ContextMemory]]s,
   * and [[sigil.conversation.ContextSummary]]s with
   * `conversationId = staging` are rewritten to reference `target`,
   * the staging conversation row is deleted, and one
   * [[sigil.signal.ConversationHistoryImported]] Notice fires
   * against `target` so consumers do their single refresh.
   *
   * Uses lightdb's `tx.upsert(stream)` for each record type — one
   * call per store handles batching, indices, and the WAL. Returns
   * the number of events flipped (informational; memories /
   * summaries also moved but aren't reflected in the count).
   */
  def mergeStagingIntoMain(staging: Id[Conversation],
                           target: Id[Conversation]): Task[Int] = {
    val rewriteEvents: Task[Int] = withDB(_.events.transaction { tx =>
      val rewritten = tx.query.filter(_.conversationId === staging.value).stream
        .map(e => e.withConversationId(target))
      tx.upsert(rewritten)
    })
    val rewriteMemories: Task[Int] = withDB(_.memories.transaction { tx =>
      val rewritten = tx.query.filter(_.conversationId === Some(staging)).stream
        .map(m => m.copy(conversationId = Some(target)))
      tx.upsert(rewritten)
    })
    val rewriteSummaries: Task[Int] = withDB(_.summaries.transaction { tx =>
      val rewritten = tx.query.filter(_.conversationId === staging).stream
        .map(s => s.copy(conversationId = target))
      tx.upsert(rewritten)
    })
    for {
      eventCount <- rewriteEvents
      _          <- rewriteMemories
      _          <- rewriteSummaries
      _          <- withDB(_.conversations.transaction(_.delete(staging)))
      _          <- notifyHistoryImported(target, eventCount)
    } yield eventCount
  }

  /**
   * Drop a staging conversation and every record that references
   * it, without merging into a target. Used by explicit cancel
   * paths and by [[OrphanStagingConversationSweep]] for crash
   * recovery. Deletes events, memories, and summaries addressed
   * to the staging id, then drops the conversation row. Vector-
   * index entries for deleted memories are NOT explicitly evicted
   * — the next embed/search cycle drops stale points by id-misses
   * (matches existing `deleteConversation` semantics).
   *
   * No Notice fires — the staging conv was never visible to
   * subscribers, so there's nothing to refresh.
   */
  def deleteStagingConversation(staging: Id[Conversation]): Task[Unit] =
    for {
      _ <- withDB { db =>
             db.events.transaction { tx =>
               val ids = tx.query.filter(_.conversationId === staging.value).stream.map(_._id)
               ids.evalMap(id => tx.delete(id)).drain
             }
           }
      _ <- withDB { db =>
             db.memories.transaction { tx =>
               val ids = tx.query.filter(_.conversationId === Some(staging)).stream.map(_._id)
               ids.evalMap(id => tx.delete(id)).drain
             }
           }
      _ <- withDB { db =>
             db.summaries.transaction { tx =>
               val ids = tx.query.filter(_.conversationId === staging).stream.map(_._id)
               ids.evalMap(id => tx.delete(id)).drain
             }
           }
      _ <- withDB(_.conversations.transaction(_.delete(staging)))
    } yield ()

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
               tx.query.filter(_.conversationId === conversationId.value).stream
                 .map(_._id)
                 .evalMap(id => tx.delete(id))
                 .drain
             }
           }
      _ <- withDB { db =>
             db.participantProjections.transaction { tx =>
               tx.query.filter(_.conversationId === conversationId).toList.flatMap { projections =>
                 Task.sequence(projections.map(p => tx.delete(p._id))).unit
               }
             }
           }
      _ <- withDB { db =>
             db.encodedContexts.transaction { tx =>
               tx.query.filter(_.conversationId === conversationId).toList.flatMap { caches =>
                 Task.sequence(caches.map(c => tx.delete(c._id))).unit
               }
             }
           }
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
   * [[Conversation]] record; the curator's `framesFor` query honors
   * the watermark by filtering out events at or before it. The
   * events themselves stay in [[sigil.db.SigilDB.events]] for audit
   * — this is a soft clear, not a hard delete.
   *
   * After clearing:
   *   - [[Sigil.framesFor]] returns no frames at-or-before the watermark.
   *   - Per-participant projections (suggested tools, recent tools)
   *     are deleted from `db.participantProjections`.
   *   - Encoded-context caches for the conversation are evicted so
   *     the next turn rebuilds against the post-clear event range.
   *   - New events added after the clear flow through normally.
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
          _ <- withDB { db =>
                 db.participantProjections.transaction { tx =>
                   tx.query.filter(_.conversationId === conversationId).toList.flatMap { projections =>
                     Task.sequence(projections.map(p => tx.delete(p._id))).unit
                   }
                 }
               }
          _ <- withDB { db =>
                 db.encodedContexts.transaction { tx =>
                   tx.query.filter(_.conversationId === conversationId).toList.flatMap { caches =>
                     Task.sequence(caches.map(c => tx.delete(c._id))).unit
                   }
                 }
               }
          _ <- publish(sigil.signal.ConversationCleared(
                 conversationId = conversationId,
                 clearedAt      = now,
                 clearedBy      = clearedBy
               ))
        } yield ()
    }
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

  /**
   * Whether `event` should wake `agent` via the cross-participant fan-out
   * in `conv`. Normal conversations use [[TriggerFilter.isTriggerFor]].
   *
   * #352 — a DIRECTED worker conversation is stricter: an agent is woken
   * ONLY by an addressed `Standard` Message from another participant (the
   * brief, a relay, an answer). The worker's own Tool-role results and
   * broadcast chatter must NOT cross-wake the supervisor into running the
   * task itself — `TriggerFilter` fires Tool-role for every participant
   * ahead of the addressee check, which had the supervisor spinning up its
   * own coding loop on every worker tool call. Each agent's OWN
   * continuation is driven by its self-loop (which still uses the full
   * `isTriggerFor`), not fan-out, so the worker keeps iterating on its
   * tool results; the supervisor simply stops waking on them and acts only
   * when the worker actually addresses it (the [[WorkerConversationAddressingTransform]]
   * addresses an agent's reply to the other agent).
   */
  private def shouldWake(agent: AgentParticipant, event: Event, conv: Conversation): Boolean =
    if (!isDirectedWorkerConversation(conv)) TriggerFilter.isTriggerFor(agent, event)
    else event match {
      case m: Message
        if m.role == MessageRole.Standard && m.participantId != agent.id &&
          m.addressees.exists(_.contains(agent.id)) => true
      case _ => false
    }

  private final def fanOut(event: Event): Task[Unit] =
    withDB(_.conversations.transaction(_.get(event.conversationId))).flatMap {
      case None       => Task.unit
      case Some(conv) =>
        val fire: Task[Unit] = {
          val tasks: List[Task[Unit]] = conv.participants.collect {
            case agent: AgentParticipant if shouldWake(agent, event, conv) =>
              tryFire(agent, conv)
          }
          Task.sequence(tasks).unit
        }
        // #327 — a directed worker conversation (linked to a parent,
        // ≥2 agents) terminates by the supervisor relaying up and not
        // re-addressing the worker. Backstop a non-terminating
        // supervisor↔worker exchange with a hard turn budget.
        val agentIds: Set[ParticipantId] = conv.participants.collect { case a: AgentParticipant => (a.id: ParticipantId) }.toSet
        if (!isDirectedWorkerConversation(conv)) fire
        else withDB(_.eventsTransaction(conv._id)(_.list)).flatMap { evs =>
          val agentMessages = evs.count {
            case m: Message => agentIds.contains(m.participantId)
            case _          => false
          }
          if (agentMessages < workerConversationTurnBudget) fire
          else Task {
            scribe.warn(
              s"Worker conversation ${conv._id.value} reached its turn budget " +
                s"($workerConversationTurnBudget agent messages); not firing further agent turns."
            )
          }
        }
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
    * claim. Generous default — the primary stuck-detection mechanism is the
    * delta-based progress checkpoint (see [[progressCheckpointInterval]] +
    * [[consecutiveNoProgressLimit]]), which fires well before this ceiling
    * for any real loop. The cap exists as a runaway-cost safety net for
    * pathological cases where the checkpoint itself misbehaves; reaching
    * it raises [[AgentRunawayException]] in the runAgent fiber after
    * releasing the AgentState claim. Apps tighten or relax per their
    * cost / latency tolerance. */
  protected def maxAgentIterations: Int = 200

  /** Hard backstop on the number of agent-authored messages in a
    * *directed worker conversation* (sigil #327 — a sub-conversation
    * linked to a parent carrying two or more agent participants). The
    * supervised bridge is meant to terminate by the supervisor relaying
    * its result to the parent and stopping (see
    * [[sigil.pipeline.WorkerConversationAddressingTransform]]); this cap
    * guarantees termination even if a model keeps the worker↔supervisor
    * exchange going. Once the conversation holds this many agent
    * messages, `fanOut` stops firing further agent turns in it. Apps
    * tune per their cost tolerance. */
  protected def workerConversationTurnBudget: Int = 40

  /** True when `conv` is a *directed worker sub-conversation* (sigil
    * #327): linked to a parent and carrying two or more agent
    * participants (a delegating supervisor + at least one worker).
    * Several worker-specific behaviors key off this — addressing-driven
    * termination's silent rest, the worker turn budget, and skipping the
    * redundant/misfiring progress reflection (#330). The supervisor is
    * the worker's progress monitor in this model, so the framework's
    * automatic reflection (which anchors on a *user* message that a
    * worker conversation doesn't have) neither helps nor applies.
    * Public so apps / UIs can ask the same question. */
  final def isDirectedWorkerConversation(conv: Conversation): Boolean =
    conv.parentConversationId.isDefined &&
      conv.participants.count { case _: AgentParticipant => true; case _ => false } >= 2

  /** Iterations between progress checkpoints. Every Nth iteration the
    * framework runs an out-of-band reflection turn that compares the
    * current task state against the prior checkpoint's status and
    * decides whether to continue / intervene / ask the user. Default
    * 15 — long enough to amortise the extra LLM call across real
    * work, short enough to catch sustained loops within ~30
    * iterations. Set to 0 to disable checkpointing. */
  protected def progressCheckpointInterval: Int = 15

  /** #321 — model for the out-of-band progress reflection. The reflection
    * can cancel an in-flight workflow (its `shouldAskUser` / stall verdict
    * becomes an orchestrator intervention), so it must NOT run on the
    * cheapest routed candidate — a bad "stuck / ask the user" call throws
    * away an entire in-progress task. Defaults to the agent's own model
    * (the tier already judged adequate for the work). Apps override to
    * impose a different floor. */
  def progressReflectionModelFor(agent: AgentParticipant): Id[Model] = agent.modelId

  /** Number of consecutive `meaningfulProgress = false` checkpoints
    * required before the framework intervenes with a synthetic
    * respond asking the user for guidance. Default 2. Setting to 1
    * is aggressive (any single "no progress" report stops the
    * loop); higher values give the agent more rope. */
  protected def consecutiveNoProgressLimit: Int = 2

  /** Sigil #257 / #273 — how many times the agent loop retries with the
    * FULL tool roster when a turn emits ZERO `tool_use` blocks (genuine
    * empty response) before falling back to the respond-only forced
    * synthesis and ultimately raising [[AgentRunawayException]]. A
    * no-tool-call response is usually a transient hiccup — reasoning
    * models in particular drop the tool call after their reasoning
    * block — and a plain re-prompt with the roster intact usually
    * self-corrects.
    *
    * This counter is incremented ONLY when the model produced no
    * `tool_use` at all. Parse failures, unknown tool names, and other
    * "tool called, just failed" outcomes flow through the orchestrator's
    * Tool-role Failure pairing and re-trigger the loop normally — they
    * burn iterations against [[maxAgentIterations]] (which protects
    * cost) but do NOT advance toward the runaway throw (which protects
    * signal — "the model is ignoring tool_choice").
    *
    * Default 3 (sigil #273 bump from 1). Setting to 0 restores the
    * pre-#257 behavior of stripping the roster on the first miss — one
    * hiccup becomes a guaranteed non-answer. Higher values tolerate
    * flakier models. */
  protected def noToolCallRetryLimit: Int = 3

  /** Size of the per-participant `recentToolInvocations` rolling
    * window. Older entries fall off the tail. Drives the prompt's
    * "Recently used tools" + "Repeated tool calls" surfaces and the
    * Layer-3 identical-call cap. Default 20 — covers a single agent
    * loop comfortably without bloating the per-participant
    * projection record. */
  def recentToolInvocationsLimit: Int = 20

  /** Hard cap on identical (tool name + canonical args) dispatches in
    * the [[recentToolInvocationsLimit]] window. When set to a positive
    * value N, the orchestrator REFUSES to dispatch a tool whose
    * (toolName, argsHash) already appears in the projection's recent
    * invocations at least N-1 times — i.e. the Nth identical call is
    * the one rejected. The refusal emits a Tool-role Failure Message
    * paired to the originating ToolInvoke describing the count and
    * suggesting alternatives. Set to `0` or a negative value to
    * disable the cap entirely (the prompt-level warning remains).
    * Default 3. */
  def maxIdenticalToolCallsInWindow: Int = 3

  /** Cap on `discoveredCapabilities` entries surfaced in the
    * agent's prompt — keeps the prompt bounded even within a long
    * agent loop that issues many distinct `find_capability` queries.
    * The cap is over the *map* (one entry per distinct query); each
    * entry's matches list is already bounded by `find_capability`'s
    * page size. Apps override to tune the prompt budget. */
  def discoveredCapabilitiesPromptCap: Int = 25

  /** Wipe the agent's [[sigil.conversation.ParticipantProjection.recentToolInvocations]]
    * rolling window for a conversation. Sigil #304 — the framework no
    * longer auto-clears this at the start of every `runAgent`; the
    * orchestrator's duplicate-call cap now scopes its count via
    * [[sigil.provider.ConversationRequest.turnStartedAt]], so the
    * window can persist across turns to feed
    * [[narrowRosterByRecentUse]] without inflating the dedupe count
    * for legitimate cross-turn retries. Apps that want an explicit
    * "reset agent state" UX action still call this directly; typical
    * conversation flow does not need to. */
  def clearDedupeForTurn(convId: Id[Conversation],
                         agentId: ParticipantId): Task[Unit] =
    updateProjection(convId, agentId)(_.copy(recentToolInvocations = Nil))

  private final def runAgent(agent: AgentParticipant,
                             conv: Conversation,
                             claimed: AgentState,
                             greeting: Boolean = false): Task[Unit] = {
    // Top-level operational wrap. The agent loop's existing
    // `handleError` chain still publishes the Failure Message and
    // releases the AgentState claim — this wrap layers a
    // `FrameworkWorkflowNotice` (workflowType = "agent-loop") on top
    // so operational observers (activity bars, latency traces) get an
    // explicit terminal pulse when the loop ends. Closes the gap
    // adjacent to bug #312 where operational observers had no signal
    // for an agent loop that crashed mid-stream.
    //
    // Sigil #313 — both terminal phases fire. `AgentStateDelta(Idle)`
    // is a different channel keyed by agent/conversation, not the
    // workflowId; consumers tracking framework workflows by workflowId
    // can't correlate it, so a missing `Completed` left the Started
    // open forever (activity-bar rows ticking after idle). The wrap
    // must self-terminate: every Started gets a Completed or Failed.
    given sigilGiven: Sigil = this
    val unit = new RunUnit[Unit] {
      override val label = s"agent loop ${agent.id.value} / ${conv._id.value}"
      override val workflowType = "agent-loop"
      override val conversationId = Some(conv._id)
      override val run: Task[Unit] = runAgentLoopForUnit(agent, conv, claimed, greeting)
    }
    // `RunUnit.execute` re-throws the underlying exception after the
    // Failed Notice fires — matches the prior behaviour where
    // `runAgentLoop` itself rethrew via `Task.error(t)`. The fiber
    // boundary from `startUnit()` still logs the failure.
    RunUnit.execute(unit)
  }

  /** Inner body of [[runAgent]] — keeps the existing iteration
    * scaffolding intact. Split out so the top-level operational
    * [[RunUnit]] wrap can layer over it without disturbing the
    * lifecycle scaffolding the agent loop relies on
    * (`userVisibleSeen` / `turnExtractorFired` / `failurePublished`
    * / `discoveredCapabilitiesRef`). */
  private final def runAgentLoopForUnit(agent: AgentParticipant,
                                        conv: Conversation,
                                        claimed: AgentState,
                                        greeting: Boolean): Task[Unit] =
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
      userVisibleSeen = new java.util.concurrent.atomic.AtomicBoolean(false),
      // Bug #149 — the per-turn memory extractor must fire exactly
      // once per user turn (not once per agent-loop iteration). This
      // flag is threaded through every recursion so a CAS at the
      // terminate path guarantees a single fire across the whole
      // loop.
      turnExtractorFired = new java.util.concurrent.atomic.AtomicBoolean(false),
      // Sigil bug #200 — the post-error `publishFailureMessage` also
      // needs fire-once semantics. Without this gate, an exception
      // raised inside an inner recursion level propagates up through
      // every parent level's `.handleError`, each of which publishes
      // its own Failure Message — surfacing N identical failure
      // bubbles in the chat for one failure. CAS-gated like
      // `turnExtractorFired`.
      failurePublished = new java.util.concurrent.atomic.AtomicBoolean(false),
      // Sigil bug #226 — per-loop `find_capability` cache. Shared
      // across every iteration of THIS loop so the agent doesn't
      // re-discover within the same task; a fresh AtomicReference
      // per `runAgent` call means the next user turn starts with an
      // empty cache, preventing prompt pollution from prior turns'
      // discoveries.
      discoveredCapabilitiesRef = new AtomicReference(Map.empty),
      // Sigil #313 — one heal per turn. CAS-gated; a healed retry
      // that ALSO fails is treated as Exhausted, NOT retried again.
      healedThisTurn = new java.util.concurrent.atomic.AtomicBoolean(false),
      // Sigil #313 — correlation id shared across the durable triple
      // (CorruptionDetected → Healed → Exhausted) for THIS turn.
      healCorrelationId = new AtomicReference(None)
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
                                 userVisibleSeen: java.util.concurrent.atomic.AtomicBoolean,
                                 /** Bug #149 — single-shot fire-gate for the
                                   * per-turn memory extractor. Shared across
                                   * every iteration of the loop so the
                                   * extractor runs exactly once per user
                                   * turn at the terminate boundary, not
                                   * once per iteration. */
                                 turnExtractorFired: java.util.concurrent.atomic.AtomicBoolean,
                                 /** Sigil bug #200 — single-shot fire-gate for
                                   * `publishFailureMessage`. Threaded through
                                   * every recursion so the inner-most handler
                                   * publishes once and outer-level handlers
                                   * skip the duplicate publish on re-throw. */
                                 failurePublished: java.util.concurrent.atomic.AtomicBoolean,
                                 /** Sigil bug #125 — when `true`, this is the
                                   * forced-synthesis turn invoked by the
                                   * cap-hit soft-stop. The loop runs ONE
                                   * iteration with `tool_choice: respond` and
                                   * exits regardless of `shouldIterate`. A
                                   * subsequent cap-hit while this flag is
                                   * already true falls back to the hard
                                   * [[AgentRunawayException]] throw — at that
                                   * point the soft path has genuinely
                                   * exhausted. */
                                 forceResponseSynthesis: Boolean = false,
                                 /** Sigil bug #198 — which condition triggered
                                   * the forced-synthesis turn. Threaded so the
                                   * [[AgentRunawayException]] message describes
                                   * the actual cause (cap-hit vs no-tool-call
                                   * vs stall) instead of misattributing every
                                   * forced-synthesis failure as cap exhaustion. */
                                 forcedReason: Option[ForcedSynthesisReason] = None,
                                 /** Sigil #257 — count of consecutive
                                   * full-roster retries already spent on
                                   * no-tool-call responses this turn. Any
                                   * iteration that makes real progress resets
                                   * it to 0 (the normal continuation simply
                                   * doesn't pass it); bounded by
                                   * [[noToolCallRetryLimit]]. */
                                 noToolCallRetries: Int = 0,
                                 /** Sigil bug #226 — the per-agent-loop
                                   * `find_capability` cache. Shared across
                                   * every iteration of THIS loop so the agent
                                   * doesn't re-discover within the same task;
                                   * cleared at loop release by virtue of the
                                   * reference going out of scope, so a new
                                   * `runAgent` call starts with a fresh empty
                                   * map. */
                                 discoveredCapabilitiesRef: AtomicReference[Map[String, sigil.conversation.DiscoveredCapability]],
                                 /** Sigil #313 — single-shot fire-gate for the
                                   * reactive self-heal. Threaded through every
                                   * recursion so a healed retry that ALSO
                                   * fails does NOT trigger a second heal —
                                   * `HealingExhausted` publishes and the
                                   * standard failure path runs. The flag is
                                   * shared across the whole agent loop (one
                                   * heal per user turn). */
                                 healedThisTurn: java.util.concurrent.atomic.AtomicBoolean,
                                 /** Sigil #313 — correlation id shared between
                                   * the durable
                                   * [[sigil.event.ConversationCorruptionDetected]],
                                   * [[sigil.event.ConversationHealed]],
                                   * [[sigil.event.HealingExhausted]] triple
                                   * for a single turn's heal arc. Threaded so
                                   * the retry's audit pulse joins the same
                                   * arc. */
                                 healCorrelationId: AtomicReference[Option[String]]
                                   ): Task[Unit] = Task.defer {
    // Bug #149 — release the agent's claim AND fire the per-turn
    // memory extractor exactly once. The CAS-guard guarantees a
    // single extraction across every terminal exit path of the
    // loop (Stop, max-iterations, cap-hit, checkpoint intervention,
    // post-stream error). The extractor itself runs on a fiber
    // so the release path isn't blocked by its LLM round-trip.
    def terminate(skipFallback: Boolean = false): Task[Unit] = {
      if (turnExtractorFired.compareAndSet(false, true)) {
        firePostTurnExtraction(agent, convId, claimed.timestamp).startUnit()
      }
      // Sigil bug #282 — defensive guarantee: never release the agent's
      // claim without something user-visible reaching the conversation.
      // The normal terminal paths set `userVisibleSeen` (respond family
      // settles, orchestrator silent-turn placeholder, …); the
      // handleError + Stop paths bypass the fallback explicitly because
      // they publish their own user-visible content (Failure Message,
      // user-initiated stop). Any other path that reaches here without
      // a user-visible reply gets a synthetic fallback Message composed
      // from the most recent ProgressCheckpoint's status so the UI
      // doesn't spin indefinitely on a silent turn end.
      val fallback: Task[Unit] =
        if (skipFallback || userVisibleSeen.get()) Task.unit
        else synthesizeFallbackRespond(agent, convId)
      // Sigil #301 — projection.suggestedTools is NOT cleared at turn
      // end. Discoveries from `find_capability` persist across turn
      // boundaries (replaced only by the next find_capability call) so
      // a multi-turn task that branches through a respond_options
      // clarification still has its discovered action-tool roster on
      // the follow-up turn. Conversation-boundary isolation is
      // preserved by projections being per-conversation. Replaces the
      // bug #169 per-turn clear that drove the change_mode-loop
      // failure mode (Sage wire log 2026-05-28 10:33:53 → 10:34:04).
      fallback.flatMap(_ => releaseClaim(claimed))
    }
    // Snapshot the start of THIS iteration. The next iteration uses this as
    // its own `sinceTimestamp`, so events emitted during this iteration
    // (including self-emitted non-terminal tool results the agent acted on)
    // don't re-appear as triggers next time.
    val thisIterationStart = Timestamp(Nowish())
    // The cap-hit, no-tool-call, and stall-intervention branches each
    // recover by running ONE more iteration with `forceResponseSynthesis`
    // pinning `tool_choice` to the respond family. The recursive call is
    // identical across all three apart from `forcedReason`; this captures
    // the common shape so the three sites can't drift.
    def recurseForced(reason: ForcedSynthesisReason): Task[Unit] =
      runAgentLoop(
        agent                     = agent,
        convId                    = convId,
        claimed                   = claimed,
        iteration                 = iteration + 1,
        sinceTimestamp            = thisIterationStart,
        greeting                  = false,
        userVisibleSeen           = userVisibleSeen,
        turnExtractorFired        = turnExtractorFired,
        failurePublished          = failurePublished,
        forceResponseSynthesis    = true,
        forcedReason              = Some(reason),
        discoveredCapabilitiesRef = discoveredCapabilitiesRef,
        healedThisTurn            = healedThisTurn,
        healCorrelationId         = healCorrelationId
      )
    // Sigil #257 — recovery for a no-tool-call response: run ONE more
    // iteration with the FULL roster + normal `tool_choice` intact (NOT
    // forced synthesis). A no-tool-call turn is usually a transient
    // reasoning-model hiccup; a plain re-prompt self-corrects far more
    // often than stripping the roster to the respond family does — the
    // latter guarantees a non-answer for any turn that needed tools.
    // `noToolCallRetries` increments so the loop strips to respond-only
    // only after [[noToolCallRetryLimit]] retries also miss.
    def recurseFullRosterRetry: Task[Unit] =
      runAgentLoop(
        agent                     = agent,
        convId                    = convId,
        claimed                   = claimed,
        iteration                 = iteration + 1,
        sinceTimestamp            = thisIterationStart,
        greeting                  = false,
        userVisibleSeen           = userVisibleSeen,
        turnExtractorFired        = turnExtractorFired,
        failurePublished          = failurePublished,
        forceResponseSynthesis    = false,
        forcedReason              = None,
        noToolCallRetries         = noToolCallRetries + 1,
        discoveredCapabilitiesRef = discoveredCapabilitiesRef,
        healedThisTurn            = healedThisTurn,
        healCorrelationId         = healCorrelationId
      )
    val stopFlag = Option(stopFlags.get(claimed._id))
    // Bug #74 — flips when a `respond` settles with `endsTurn = false`
    // (a progress / status update). The post-drain decision below
    // iterates the loop without waiting for new triggers, so the
    // agent picks up its own respond Message in the next iteration's
    // history and continues working. Per-iteration scope (the next
    // iteration starts with its own fresh AtomicBoolean).
    val agentRequestedContinue = new java.util.concurrent.atomic.AtomicBoolean(false)
    // Flips when a user-visible terminal tool (`respond` with
    // `endsTurn = true`, `no_response`, the other `respond_*` family
    // members) settles this iteration. Every tool now emits a
    // `ToolResults` (role = Tool) which `TriggerFilter` counts as a
    // re-trigger; without this flag the agent's OWN terminal-tool
    // result would keep `newTriggersExist` true and the loop would
    // never end. When set, the post-drain check only continues for a
    // genuine *external* trigger (a message from someone else that
    // landed mid-turn), never for the turn's own emitted events.
    val terminalToolSettled = new java.util.concurrent.atomic.AtomicBoolean(false)
    // Set when the orchestrator emits a `_refusal_challenge` this
    // iteration — the agent's `respond` was suppressed and replaced
    // with a diagnostic it must read and act on. The loop MUST run
    // another iteration so the agent re-responds; `terminalToolSettled`
    // (set by the suppressed respond's settle delta) would otherwise
    // end the turn before the challenge is ever acted on.
    val frameworkRequestedContinue = new java.util.concurrent.atomic.AtomicBoolean(false)
    // Sigil #275 — set when the model's response for this iteration
    // contained AT LEAST ONE non-internal `ToolInvoke`. The narrowed
    // runaway counter (sigil #273) MUST only count iterations with
    // genuinely zero `tool_use` blocks emitted; without this flag the
    // post-drain `newTriggersExist` predicate misclassifies successful
    // non-terminal tool calls (record_consent, list_theme_files, etc.)
    // as "no tool call" because their `ToolInvoke` event has the
    // default Standard role + the agent's own participantId, and
    // `TriggerFilter` excludes both. The flag is read inside
    // `shouldIterate` to force `case true =>` for any iteration the
    // agent actually dispatched a real tool on — the loop then runs
    // another iteration to read the tool's result. Framework-synthesised
    // diagnostic invokes (`_provider_error`, `_unknown_tool`,
    // `_cap_reached`, …) carry `internal = true` and don't count;
    // they're not signals that the model is following the protocol.
    val iterationHadToolCall = new java.util.concurrent.atomic.AtomicBoolean(false)
    // Bug #57 — diagnostic logging at iteration boundaries so a
    // future repro of "agent parks at thinking" can be localised
    // by reading the server log for missing exit lines. The cost
    // of these scribe.debug calls is negligible compared to the
    // turn's actual work; volume is ~3 lines per iteration.
    scribe.debug(s"runAgentLoop[${agent.id.value}/${convId.value}] iter=$iteration enter")
    // Batch this iteration's `events` writes into one transaction so
    // the Lucene-indexed event store commits once per iteration
    // instead of once per streamed Delta. `iterationStep` is a
    // `Task[Task[Unit]]`: the outer Task does this iteration's work
    // (publishes, tool dispatch) inside the batched scope; the inner
    // Task it yields is the continuation — the NEXT iteration's
    // `runAgentLoop` call, or the terminal release (`terminate()`)
    // for a terminal exit. The continuation runs AFTER
    // `withBatchedEvents` commits, so iteration N+1's reads see
    // iteration N's committed data, and the terminal
    // `AgentStateDelta(Idle, Complete)` is broadcast only once the
    // turn's events are durable — never racing the commit.
    val iterationStep: Task[Task[Unit]] =
    // A Stop may have landed before this iteration even starts; short-
    // circuit if so (graceful = "don't start another iteration"; force
    // = "same, plus the in-flight stream below won't run"). Either way,
    // release and exit.
    if (stopFlag.exists(_.requested)) Task(terminate(skipFallback = true))
    else
    // Reload the conversation each iteration — materialized projections
    // (currentMode, modified, etc.) update as Events flow through `publish`,
    // so the conversation we hand to the agent must reflect the latest state.
    withDB(_.conversations.transaction(_.get(convId))).flatMap {
      case None =>
        // Conversation deleted mid-turn — release the lock and exit cleanly.
        // Extractor isn't fired here — no conversation = nothing to extract.
        // Yielded as the continuation so the release runs post-commit.
        Task(releaseClaim(claimed))
      case Some(conv) =>
        // Sigil bug #169 — overlay persists across iterations within the
        // same user turn. Prerequisite calls (`record_consent`, etc.) and
        // multi-invocation flows (`create_workflow` → `add_workflow_step` 5×)
        // keep their discovered tools in scope until the loop terminates
        // (handled in `terminate()` above) or a new `find_capability` /
        // suggestion-emitting tool result replaces the list.
        {
          scribe.debug(s"runAgentLoop[${agent.id.value}/${convId.value}] iter=$iteration buildContext start")
          buildContext(agent, conv, sinceTimestamp = sinceTimestamp, claimedId = claimed._id, claimedTimestamp = claimed.timestamp, isGreeting = greeting && iteration == 1, discoveredCapabilitiesRef = discoveredCapabilitiesRef, healedThisTurn = Some(healedThisTurn)).flatMap {
            case (rawCtx, triggers) =>
              // Sigil bug #125 — propagate the cap-hit soft-stop flag
              // through the TurnContext so runAgentTurn → ConversationRequest →
              // Provider's tool_choice all reflect it.
              val ctx = if (forceResponseSynthesis) rawCtx.copy(forceResponseSynthesis = true) else rawCtx
              scribe.debug(s"runAgentLoop[${agent.id.value}/${convId.value}] iter=$iteration buildContext done; dispatching agent.process")
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
              // Map invoke id → tool name so we can distinguish `respond`
              // from the other user-visible-terminal names when the
              // settle delta lands (bug #74's `endsTurn` lever applies
              // to `respond` specifically).
              val activeUserVisibleInvokes = new java.util.concurrent.ConcurrentHashMap[Id[Event], String]()
              interruptible
                .evalTap {
                  case ti: ToolInvoke if Orchestrator.UserVisibleTerminalTools.contains(ti.toolName.value) =>
                    Task {
                      activeUserVisibleInvokes.put(ti._id, ti.toolName.value)
                      // Sigil #275 — respond-family invokes count as "model
                      // emitted a tool_use block". Set the flag here too;
                      // the terminalToolSettled path takes over for the
                      // continuation decision but the runaway counter has
                      // already been told this wasn't an empty response.
                      if (!ti.internal) iterationHadToolCall.set(true)
                      ()
                    }
                  case ti: ToolInvoke if ti.toolName.value == "_refusal_challenge" =>
                    Task { frameworkRequestedContinue.set(true); () }
                  case ti: ToolInvoke if !ti.internal =>
                    // Sigil #275 — record that this iteration's response
                    // contained at least one real tool_use block. Filters
                    // out framework-synthesised diagnostic invokes
                    // (`internal = true`) so the flag tracks the model's
                    // actual behaviour, not the framework's bookkeeping.
                    Task { iterationHadToolCall.set(true); () }
                  // Silent-turn placeholder emitted by the orchestrator
                  // when Usage arrives with no target. Marked via
                  // `source = "orchestrator-silent-turn"` so the loop
                  // recognises it as a user-visible reply without
                  // matching every agent Standard Message.
                  case m: sigil.event.Message
                    if m.source.contains("orchestrator-silent-turn") && m.participantId == agent.id =>
                    Task { userVisibleSeen.set(true); () }
                  case td: ToolDelta if td.state.contains(EventState.Complete)
                                     && activeUserVisibleInvokes.containsKey(td.target) =>
                    Task {
                      userVisibleSeen.set(true)
                      // Bug #74 — `respond(endsTurn = false)` keeps the
                      // turn open. The settled delta carries the parsed
                      // input; flip the continue flag when it's a
                      // RespondInput with endsTurn = false. Every other
                      // user-visible terminal tool (`respond` with
                      // endsTurn = true, `no_response`, the other
                      // `respond_*` family) ENDS the turn — flip
                      // `terminalToolSettled` so the post-drain check
                      // doesn't mistake the turn's own emitted
                      // `ToolResults` for a fresh trigger.
                      //
                      // Bug #226 — also drop the per-loop
                      // `find_capability` cache on respond(endsTurn =
                      // true). Covers the streaming-respond path
                      // (orchestrator settles the in-flight Message via
                      // `MessageDelta` and never calls
                      // `RespondTool.executeResult`); the atomic-respond
                      // path's clear is idempotent with this one.
                      val toolName = activeUserVisibleInvokes.get(td.target)
                      val keepsTurnOpen = toolName == "respond" && (td.input match {
                        case Some(r: sigil.tool.model.RespondInput) => !r.endsTurn
                        case _                                      => false
                      })
                      if (keepsTurnOpen) agentRequestedContinue.set(true)
                      else {
                        terminalToolSettled.set(true)
                        discoveredCapabilitiesRef.set(Map.empty)
                      }
                      ()
                    }
                  case _ => Task.unit
                }
                .evalTap(publish)
                .drain
          }
        }.flatMap { _ =>
          scribe.debug(s"runAgentLoop[${agent.id.value}/${convId.value}] iter=$iteration drain done")
          // After the iteration drains, check stop flags before anything
          // else — a Stop that fired mid-stream means exit now, don't
          // continue looping even if there are new triggers.
          if (stopFlag.exists(_.requested))
            Task(terminate(skipFallback = true))
          else if (forceResponseSynthesis) {
            // Sigil bug #125 — the cap-hit soft-stop ran. With
            // `tool_choice: respond` the model SHOULD have called
            // respond on this iteration. If it did
            // (`userVisibleSeen = true`), release the claim and
            // exit cleanly. If it didn't (very weak / non-
            // instruction-following local models), the soft path
            // has genuinely exhausted — raise the hard throw so the
            // calling fiber's failure handler sees it.
            if (userVisibleSeen.get())
              Task(terminate())
            else
              // Routed through the handleError below — it owns the
              // failure publish + the post-commit terminal release.
              Task.error(buildRunawayException(
                agent, conv, iteration, maxAgentIterations, forcedReason))
          }
          else {
            // Bug #74 — `respond(endsTurn = false)` continues the
            // loop without waiting for an external trigger. The
            // agent's own progress respond IS the signal to keep
            // going; the next iteration will see it in history and
            // proceed with the announced work.
            val shouldIterate: Task[Boolean] =
              if (agentRequestedContinue.get() || frameworkRequestedContinue.get()) Task.pure(true)
              else if (terminalToolSettled.get())
                // The agent ended the turn with a user-visible terminal
                // tool. Continue ONLY for a genuine external trigger (a
                // message from someone else that landed mid-turn) — never
                // for the turn's own `ToolResults` / reply Message.
                externalTriggersExist(agent, conv, sinceTimestamp = thisIterationStart)
              else if (iterationHadToolCall.get())
                // Sigil #275 — the model dispatched a non-terminal tool
                // this iteration (record_consent, list_theme_files, …).
                // Its `ToolInvoke` has the default `Standard` role + the
                // agent's own participantId, so `TriggerFilter` excludes
                // it from `newTriggersExist`. But the tool call IS the
                // continuation signal — the next iteration sees the
                // tool's result in the conversation context and decides
                // what to do. `maxAgentIterations` caps runaway tool-
                // calling spirals; this branch keeps the loop alive long
                // enough for the cap to bound cost without misclassifying
                // a productive turn as "no tool call".
                Task.pure(true)
              else newTriggersExist(agent, conv, sinceTimestamp = thisIterationStart)
            shouldIterate
              // #355 — instrument the post-drain decision so a silent stall
              // is diagnosable. The trace "drain done" → "shouldIterate=X" →
              // "committed; running continuation" → "iter N+1 enter" pinpoints
              // WHERE a hang sits: no `shouldIterate` log = hung in the trigger
              // query (the in-batch decision); `shouldIterate` but no
              // `running continuation` = hung committing the batch; `running
              // continuation` but no next `iter enter` = hung in the
              // continuation (recurse / intra-turn compaction).
              .flatMap { si =>
                scribe.debug(s"runAgentLoop[${agent.id.value}/${convId.value}] iter=$iteration " +
                  s"shouldIterate=$si (iteration<max=${iteration < maxAgentIterations}, " +
                  s"forceResponseSynthesis=$forceResponseSynthesis)")
                Task.pure(si)
              }
              .flatMap {
            case true if iteration < maxAgentIterations =>
              // Bug #54 / #349 — un-stick the consumer's state at the
              // iteration boundary. Without a pulse, a multi-iteration
              // loop pins the consumer at `typing` (or whatever the last
              // streaming activity was) for the whole outer loop, so
              // clients can't render an accurate Stop button or per-turn
              // UX. #54 originally pulsed `Idle → Thinking`, but `Idle`
              // also means "turn complete" — overloading it made clients
              // reset their per-turn UI (timer, thinking buffer, Stop
              // button) on EVERY tool call (#349). Emit only the next
              // iteration's real activity (`Thinking`): the
              // `Typing → Thinking` delta is itself the visible change #54
              // needed, and `Idle` now fires solely at the genuine turn
              // end — restoring the invariant `Idle` ⇔ turn complete.
              //
              // The pulse doesn't change the AgentState event's `state`
              // (still Active — claim still held) — it mutates `activity`
              // only, so the framework's claim-lock semantics are
              // preserved. The next iteration runs in the same outer fiber.
              publish(AgentStateDelta(
                target = claimed._id,
                conversationId = convId,
                activity = Some(AgentActivity.Thinking)
              )).flatMap { _ =>
                // Run the progress checkpoint at the boundary if this
                // is a checkpoint iteration. The helper returns
                // Some(message) when the agent reports being stuck
                // for `consecutiveNoProgressLimit` consecutive
                // checkpoints OR when it explicitly asks the user
                // for guidance — in either case we publish the
                // synthetic respond and end the loop instead of
                // recursing.
                val nextIteration = iteration + 1
                val checkpointTask: Task[Option[CheckpointIntervention]] =
                  // The checkpoint runs in every conversation, workers
                  // included (#332, amending #330). It bundles two
                  // mechanisms: a mechanical stall detector
                  // (repeated-identical-call / no-progress streak) that is
                  // universally useful, and an LLM self-assessment that can
                  // ask the user. #330 was right that the user-facing
                  // *escalation* misfires in a worker — the supervisor owns
                  // asking the human — but it suppressed the whole
                  // checkpoint, disabling stall detection and letting a
                  // grinding worker flail to the iteration cap. So we run the
                  // checkpoint everywhere and instead redirect an `askingUser`
                  // intervention to a supervisor handoff inside a worker (the
                  // branch below).
                  if (progressCheckpointInterval > 0 && nextIteration % progressCheckpointInterval == 0)
                    runProgressCheckpoint(agent, convId, claimed, nextIteration)
                  else
                    Task.pure(None)
                checkpointTask.flatMap {
                  case Some(intervention) =>
                    // Bug #133 / #332 / #353 — a checkpoint intervention
                    // (stall streak OR "needs user input") ALWAYS routes the
                    // directive to the AGENT, never a framework-authored
                    // user-facing Message in the agent's voice followed by an
                    // idle dead-end (#353: the old `askingUser` main-conversation
                    // arm did exactly that — the user saw "I need clarification"
                    // they couldn't act on, and control never returned to the
                    // agent). Publish the directive as Tool-role (Agents
                    // visibility) under a synthetic `_stall_detected` invoke,
                    // then force ONE more iteration so the agent decides what to
                    // do — continue, or ask the user ITSELF via `respond` /
                    // `respond_options`. The directive is tailored per case:
                    val syntheticInvoke = sigil.orchestrator.SyntheticDiagnostic
                      .invoke("_stall_detected", agent.id, convId, conv.currentTopicId)
                    val directiveContent =
                      if (intervention.askingUser && isDirectedWorkerConversation(conv))
                        // Worker: can't ask the human directly — report up to
                        // the supervisor, who decides whether to escalate.
                        Vector(_root_.sigil.tool.model.ResponseContent.Text(
                          "You can't ask the user directly from here — your supervisor owns this task. " +
                            "Stop gathering and call `respond` now to report what you've found and what you're blocked on."
                        ))
                      else if (intervention.askingUser)
                        // Main conversation: leave the decision AND the
                        // user-facing wording to the agent. The framework no
                        // longer impersonates the agent toward the user (#353).
                        // Note the false-positive guard: if the agent's recent
                        // tool calls actually completed (e.g. externalized image
                        // results), it can just continue.
                        Vector(_root_.sigil.tool.model.ResponseContent.Text(
                          "You appear blocked waiting on input. First check whether your recent tool calls actually " +
                            "completed (their results may be large and externalized rather than inline) — if so, just " +
                            "continue. If you genuinely need the user, ask them directly NOW via `respond_options` " +
                            "(clickable choices, preferred) or `respond`, phrasing the question yourself."
                        ))
                      else intervention.message.content
                    val taggedDirective = intervention.message.copy(
                      role       = MessageRole.Tool,
                      visibility = MessageVisibility.Agents,
                      origin     = Some(syntheticInvoke._id),
                      content    = directiveContent
                    )
                    publish(syntheticInvoke)
                      .flatMap(_ => publish(taggedDirective))
                      .map(_ => recurseForced(ForcedSynthesisReason.StallIntervention))
                  case None =>
                    // Sigil #285 — consult the intra-turn compactor
                    // before the next iteration. When budget pressure
                    // or a natural boundary fires, the framework folds
                    // older this-turn events into a ContextSummary so
                    // the next iteration's wire prompt is smaller. The
                    // helper is a no-op when no compaction is needed.
                    Task.pure(
                      maybeIntraTurnCompact(agent, convId, claimed)
                        .flatMap(_ => runAgentLoop(agent, convId, claimed, nextIteration, thisIterationStart,
                          userVisibleSeen = userVisibleSeen,
                          turnExtractorFired = turnExtractorFired,
                          failurePublished = failurePublished,
                          discoveredCapabilitiesRef = discoveredCapabilitiesRef,
                          healedThisTurn = healedThisTurn,
                          healCorrelationId = healCorrelationId))
                    )
                }
              }
            case true if !forceResponseSynthesis =>
              // Sigil bug #125 — cap hit on a normal iteration. Instead of
              // throwing AgentRunawayException and discarding whatever
              // context the agent has gathered, inject a Tool-role
              // "cap reached, respond NOW" diagnostic and run ONE more
              // forced-synthesis iteration with `tool_choice: respond`.
              // The agent synthesises a reply from the conversation it
              // already built up. Only fall through to the hard throw
              // if THAT iteration also fails (`case true if
              // forceResponseSynthesis` below).
              // Synthetic ToolInvoke parent so the Tool-role diagnostic
              // satisfies the framework's "every Tool-role event MUST
              // carry origin" invariant. Marked `internal = true` so
              // client UIs filter it out of the user-facing chip
              // stream — this is framework-internal model nudging.
              val capInvoke = sigil.orchestrator.SyntheticDiagnostic
                .invoke("_cap_reached", agent.id, convId, conv.currentTopicId)
              val capDiagnostic = Message(
                participantId  = agent.id,
                conversationId = convId,
                topicId        = conv.currentTopicId,
                content        = Vector(_root_.sigil.tool.model.ResponseContent.Text(
                  s"You've reached the iteration cap ($maxAgentIterations turns) for this user request. " +
                    "Synthesize a response NOW from what you've gathered so far — call `respond` with " +
                    "your findings. Do not call any more discovery / read / search tools."
                )),
                state          = EventState.Complete,
                role           = MessageRole.Tool,
                visibility     = MessageVisibility.Agents,
                origin         = Some(capInvoke._id)
              )
              publish(capInvoke).flatMap(_ => publish(capDiagnostic)).flatMap { _ =>
                // Bug #128 composition — when `escalateOnCapHit` is on,
                // bump the cached complexity tier one step up before
                // the forced-synthesis turn. The recovery attempt then
                // resolves to whichever model in the chain supports
                // the elevated tier. No-op when the flag is off.
                escalateForCapHit(convId).map(_ =>
                  recurseForced(ForcedSynthesisReason.CapHit))
              }
            case true =>
              // Cap hit on the forced-synthesis iteration too. The model
              // failed to call `respond` despite `tool_choice` pinning it
              // (very weak / non-instruction-following local models, or
              // a buggy provider). Soft path exhausted — surface the hard
              // failure so the calling fiber's error boundary logs it.
              // Routed through the handleError below for the failure
              // publish + post-commit terminal release.
              Task.error(buildRunawayException(
                agent, conv, iteration, maxAgentIterations, forcedReason))
            case false =>
              // No more triggers to chase, no continue requested. If the
              // agent already spoke this turn we're done. Otherwise the
              // turn ended with no tool call — a transient hiccup most
              // of the time (reasoning models drop the tool call after
              // their reasoning block).
              //
              // Sigil #257 — recover in two stages. First, retry up to
              // `noToolCallRetryLimit` times with the FULL roster +
              // normal `tool_choice` intact: a plain re-prompt usually
              // self-corrects. Only once those retries are exhausted do
              // we force ONE iteration with the roster restricted to the
              // respond family so the model MUST emit a real reply
              // (respond / respond_options / … / no_response). If THAT
              // forced iteration also fails to call respond, the
              // `forceResponseSynthesis` branch raises
              // AgentRunawayException — model is broken; surface the
              // hard failure instead of papering over it.
              if (userVisibleSeen.get()) Task(terminate())
              else if (forceResponseSynthesis)
                // Routed through the handleError below for the failure
                // publish + post-commit terminal release.
                Task.error(buildRunawayException(
                  agent, conv, iteration, maxAgentIterations, forcedReason))
              else if (noToolCallRetries < noToolCallRetryLimit) {
                // Recover a transient reasoning-model tool-call drop with a
                // plain re-prompt — in BOTH user-facing and worker
                // conversations. A worker-conv agent that genuinely meant
                // to reply (e.g. a supervisor answering the worker) but
                // dropped the call must get these retries, or the task
                // stalls silently.
                scribe.warn(
                  s"runAgentLoop[${agent.id.value}/${convId.value}] iter=$iteration returned no " +
                    s"tool call; retrying with the full roster (retry ${noToolCallRetries + 1}/$noToolCallRetryLimit)"
                )
                Task.pure(recurseFullRosterRetry)
              }
              else if (isDirectedWorkerConversation(conv)) {
                // #327 chat-fidelity — once the drop-recovery retries are
                // exhausted in a directed worker sub-conversation (linked to
                // a parent, two+ agents), a woken agent that still has
                // nothing to add simply RESTS, exactly like a user who
                // doesn't reply. Replacing forced synthesis with rest is the
                // natural termination: the supervisor relays its result up
                // to the parent and, with nothing left for the worker,
                // settles silently here; the worker (un-addressed) is never
                // re-woken. (User-facing conversations force a reply below,
                // where the agent owes the user an answer.) skipFallback —
                // resting must NOT synthesize the #282 "turn ended without a
                // reply" placeholder; rest is the intended outcome.
                scribe.debug(
                  s"runAgentLoop[${agent.id.value}/${convId.value}] no tool call after " +
                    s"$noToolCallRetries retries in a worker sub-conversation; settling silently (chat-fidelity rest)"
                )
                Task(terminate(skipFallback = true))
              }
              else
                Task.pure(recurseForced(ForcedSynthesisReason.NoToolCall))
            }
          }
        }
    }.handleError { t =>
      // Sigil #313 — reactive self-heal: BEFORE the standard failure
      // surface fires, walk `healingStrategies` looking for a match.
      // First match wins. In `HealingMode.Recover` (production
      // default), the strategy publishes its corrections, the audit
      // triple (CorruptionDetected → Healed) lands, and we recurse
      // the iteration ONCE. In `HealingMode.Strict` (TestSigil
      // default, dev/CI), the corruption is recorded but the heal
      // does NOT run and the original error re-throws — so the
      // developer hits the failure and gets a chance to fix the
      // underlying cause rather than rely on the patch.
      val tryHeal: Task[Option[Task[Unit]]] = tryHealAgentLoopError(
        agent             = agent,
        convId            = convId,
        claimed           = claimed,
        thrown            = t,
        healedThisTurn    = healedThisTurn,
        healCorrelationId = healCorrelationId
      )
      tryHeal.flatMap {
        case Some(retryTask) =>
          // Heal applied; recurse the iteration. The recurse runs as
          // the post-commit continuation so the heal's published
          // events are durable before the retry reads them. Wrap in
          // Task.pure so it becomes the iterationStep's continuation
          // rather than executing inline.
          Task.pure(retryTask)
        case None =>
          // No heal applied (no match, healing exhausted, or strict
          // refusal). Fall through to the standard failure path.
          //
          // Any unhandled failure mid-turn — surface the failure to the
          // user so the chat doesn't go silent (Bug #6), then release the
          // lock so the agent isn't stuck Active forever, then re-raise
          // so the fiber's error boundary logs it. Each step is
          // independently best-effort: a downstream failure (DB
          // unavailable, hub closed, missing topic, etc.) doesn't mask
          // the original error.
          //
          // Sigil bug #200 — `publishFailureMessage` is CAS-gated so an
          // exception that propagates up through N recursion levels only
          // surfaces ONE Failure Message in the chat instead of N
          // identical bubbles. The inner-most handler wins the publish;
          // outer handlers re-throw silently. `scribe.error` stays per-
          // level (stack-trace shape differs per recursion depth and is
          // diagnostically useful in operator logs); `terminate()` stays
          // per-level (already idempotent). `Task.error(t)` stays
          // per-level so the failure still propagates to the fiber's
          // error boundary.
          scribe.error(s"runAgent failed for ${agent.id.value} in ${convId.value}", t)
          val publishOnce: Task[Unit] =
            if (failurePublished.compareAndSet(false, true))
              publishFailureMessage(agent, convId, t).handleError(_ => Task.unit)
            else Task.unit
          // The Failure Message persists inside the batched transaction;
          // the terminal release + re-raise run as the post-commit
          // continuation so the Idle/Complete signal never races the commit.
          publishOnce.map(_ =>
            terminate(skipFallback = true).handleError(_ => Task.unit).flatMap(_ => Task.error(t)))
      }
    }
    // Hold one `events` transaction open across this iteration's
    // work — every `publish` → `apply` and every event read routed
    // through `eventsTransaction(convId)` joins it — then commit
    // once at scope exit. `iterationStep` yields the continuation
    // Task (the next iteration, or the terminal `terminate()`);
    // running it AFTER `withBatchedEvents` returns means the next
    // iteration — or the terminal release — starts only once this
    // iteration's writes are committed, so independent reads see the
    // durable data.
    withDB(_.withBatchedEvents(convId)(iterationStep)).flatMap { continuation =>
      // #355 — the iteration's events are committed here; the continuation
      // (next iteration's runAgentLoop, or the terminal release) runs next.
      // Logging the handoff distinguishes a commit hang (this line never
      // prints after `shouldIterate=…`) from a continuation hang (this line
      // prints but the next `iter … enter` / terminal release never does).
      scribe.debug(s"runAgentLoop[${agent.id.value}/${convId.value}] iter=$iteration committed; running continuation")
      continuation
    }
  }

  /** Sigil bug #198 — assemble an [[AgentRunawayException]] whose
    * message describes the actual failure mode rather than always
    * misattributing to "hit maxAgentIterations". Reason carries the
    * trigger condition (`CapHit` / `NoToolCall` / `StallIntervention`);
    * `iteration` is the actual loop counter at throw time. */
  private final def buildRunawayException(agent: AgentParticipant,
                                          conv: Conversation,
                                          iteration: Int,
                                          maxIter: Int,
                                          reasonOpt: Option[ForcedSynthesisReason]): AgentRunawayException = {
    val reason = reasonOpt.getOrElse(ForcedSynthesisReason.CapHit)
    val convPart = s"in conversation ${conv._id.value}"
    val cause = reason match {
      case ForcedSynthesisReason.CapHit =>
        s"hit maxAgentIterations ($maxIter) and the forced-synthesis turn at iteration $iteration " +
          s"also failed to call `respond`. Check LLM behavior or raise the cap."
      case ForcedSynthesisReason.NoToolCall =>
        // Sigil #273 — the narrowed signal: model emitted zero `tool_use`
        // blocks for `noToolCallRetryLimit + 1` consecutive iterations
        // despite `tool_choice: required`, and the forced respond-only
        // retry also produced no tool call. Parse failures / unknown tool
        // names land elsewhere (Tool-role Failure pairing → normal retry)
        // and do not trip this path.
        s"emitted zero `tool_use` blocks across $iteration consecutive iterations (cap $maxIter) despite " +
          s"`tool_choice: required`, and the forced respond-only recovery turn also produced no tool call. " +
          "Model is not following the tool-use protocol — verify provider plumbing, `tool_choice` wiring, " +
          "or downgrade to a model that honors the contract."
      case ForcedSynthesisReason.StallIntervention =>
        s"stalled at iteration $iteration of cap $maxIter (progress-checkpoint intervention) and " +
          s"the forced-synthesis recovery turn also failed to call `respond`. Check LLM behavior."
    }
    new AgentRunawayException(s"Agent ${agent.id.value} $cause $convPart", reason)
  }

  /** Bug #149 — assemble the per-turn extractor's `(userMessage,
    * agentResponse)` arguments from the conversation's events since
    * the turn started, and fire `memoryExtractor.extract`. Runs once
    * per user turn at the agent loop's terminate boundary (see
    * `terminate()` inside `runAgentLoop`). Background fiber —
    * failures are logged + swallowed; the agent's settle path never
    * blocks on extraction. */
  private final def firePostTurnExtraction(agent: AgentParticipant,
                                           convId: Id[Conversation],
                                           turnStartTimestamp: Timestamp): Task[Unit] =
    withDB(_.conversationEvents(convId)).flatMap { all =>
      val convEvents = all.iterator
        .filter(_.conversationId == convId)
        .filter(_.state == EventState.Complete)
        .toVector
        .sortBy(_.timestamp.value)
      // Agent response: text frames the agent authored DURING this
      // turn (events at or after `claimed.timestamp`).
      val agentResponse = convEvents.iterator
        .filter(_.timestamp.value >= turnStartTimestamp.value)
        .collect {
          case m: Message if m.participantId == agent.id && m.role == MessageRole.Standard =>
            m.content.collect { case sigil.tool.model.ResponseContent.Text(t) => t }.mkString("")
        }
        .mkString("\n")
        .trim
      // User message: the most recent user-authored Message in the
      // entire conversation. The triggering message PRECEDES the
      // agent's claim timestamp (it's what woke the agent), so
      // turn-window filtering would miss it.
      val userMessage = convEvents.reverseIterator
        .collectFirst {
          case m: Message if !m.participantId.isInstanceOf[sigil.participant.AgentParticipantId] && m.role == MessageRole.Standard =>
            m.content.collect { case sigil.tool.model.ResponseContent.Text(t) => t }.mkString("")
        }
        .getOrElse("")
        .trim
      if (userMessage.isEmpty && agentResponse.isEmpty) Task.unit
      else memoryExtractor
        .extract(
          sigil          = this,
          conversationId = convId,
          modelId        = agent.modelId,
          chain          = List(agent.id),
          userMessage    = userMessage,
          agentResponse  = agentResponse
        )
        .unit
        .handleError { e =>
          Task(scribe.warn(s"MemoryExtractor failed for conversation ${convId.value}: ${e.getMessage}"))
        }
    }

  /** Outcome of a progress checkpoint dispatch. `None` means continue
    * the agent loop normally; `Some(message)` means terminate the loop
    * after publishing this respond Message (the framework intervened
    * because the agent reported being stuck or asked the user for
    * guidance). */
  /** Bug #133 — outcome envelope for a checkpoint's intervention.
    * Distinguishes the two recoverable shapes the framework can hit:
    *
    *   - [[CheckpointIntervention]] with `askingUser = false` — stall
    *     detector trip, no-progress streak, or any other "agent should
    *     now do something different" case. The intervention text is
    *     a directive to the AGENT. Caller publishes as Tool-role +
    *     runs one forced-synthesis iteration so the agent actually
    *     gets to act on the guidance (parallel to #125's cap-hit).
    *   - [[CheckpointIntervention]] with `askingUser = true` — the
    *     reflector self-reported `shouldAskUser`. Genuine "I need
    *     user input to proceed" — caller publishes user-visible and
    *     releases the claim.
    *
    * The previous return shape (`Option[Message]`) collapsed both
    * cases into one path and unconditionally terminated the loop;
    * the agent never got to act on stall directives. */
  private final case class CheckpointIntervention(message: Message, askingUser: Boolean)

  /** Sigil #285 — consult [[intraTurnCompactor]] at an iteration
    * boundary and, if it fires, run [[intraTurnCompressor.compressCovering]]
    * to fold this turn's eligible events into a [[ContextSummary]]
    * tagged with their event ids. The next iteration's curator picks
    * the summary up and filters those events out of the wire prompt,
    * shrinking the per-iteration cost without touching the durable
    * event log.
    *
    * Best-effort: a failure inside the compactor or compressor is
    * logged at WARN and swallowed — the agent loop continues with
    * the un-folded history (degraded but functional). The compactor
    * predicate is cheap; the compress call only fires when the
    * predicate returns true AND there's foldable content. */
  private final def maybeIntraTurnCompact(agent: AgentParticipant,
                                          convId: Id[Conversation],
                                          claimed: AgentState): Task[Unit] = Task.defer {
    val compactor = intraTurnCompactor
    eventsFor(convId, minTimestamp = Some(claimed.timestamp)).flatMap { page =>
      // Sort oldest-first so selectFoldable's "drop the oldest" logic
      // matches the conversation's natural order. eventsFor returns
      // newest-first.
      val turnEvents = page.events.toVector.reverse
      if (turnEvents.isEmpty) Task.unit
      else {
        val estimated = turnEvents.iterator
          .map(e => sigil.tokenize.HeuristicTokenizer.count(eventTextForHeuristic(e)))
          .sum
          .toLong
        val threshold = compressionTriggerTokens(agent.modelId)
        if (!compactor.shouldCompact(turnEvents, estimated, threshold)) Task.unit
        else {
          val ctx = _root_.sigil.conversation.compression.TurnEventsContext(
            conversationId = convId,
            claimedAt      = Some(claimed.timestamp),
            agentId        = Some(agent.id)
          )
          val coverIds = compactor.selectFoldable(turnEvents, ctx).toSet
          if (coverIds.isEmpty) Task.unit
          else framesFor(convId).flatMap { allFrames =>
            val coveredFrames = allFrames.filter(f => coverIds.contains(f.sourceEventId))
            if (coveredFrames.isEmpty) Task.unit
            else intraTurnCompressor
              .compressCovering(
                sigil           = this,
                callerModelId   = agent.modelId,
                chain           = List(agent.id),
                frames          = coveredFrames,
                conversationId  = convId,
                coversEventIds  = coverIds.toList
              )
              .map(_ => ())
              .handleError(t => Task {
                scribe.warn(s"Sigil #285 — intra-turn compaction failed for ${agent.id.value}/${convId.value}: ${t.getMessage}")
              })
          }
        }
      }
    }
  }

  /** Heuristic text rendering for an Event purely for the
    * intra-turn-compactor's size estimation. Doesn't need to match
    * any provider's exact wire shape — only stable enough that a
    * vector of these is a fair proxy for cumulative cost. */
  private def eventTextForHeuristic(e: Event): String = e match {
    case m: Message =>
      m.content.iterator.map {
        case t: sigil.tool.model.ResponseContent.Text => t.text
        case other => other.toString
      }.mkString(" ")
    case ti: ToolInvoke =>
      s"${ti.toolName.value} ${ti.input.fold("")(_.toString)}"
    case other => other.toString
  }

  private final def runProgressCheckpoint(agent: AgentParticipant,
                                          convId: Id[Conversation],
                                          claimed: AgentState,
                                          iteration: Int): Task[Option[CheckpointIntervention]] = Task.defer {
    if (progressCheckpointInterval <= 0) Task.pure(None)
    else {
      val state = checkpointStates.computeIfAbsent(claimed._id,
        _ => CheckpointState(lastStatus = None, noProgressStreak = 0))
      val priorStatus = state.lastStatus
      val stallTask = evaluateStall(convId, agent.id)
      loadProgressContext(convId, agent.id).flatMap { ctx =>
        val systemPrompt =
          """You are reflecting on the agent's progress on a specific user task. Given the
            |user's request, the tool history since that request, and the prior checkpoint
            |status, assess whether meaningful progress has been made. Be honest: if your
            |current status looks identical to the prior status, set meaningfulProgress = false
            |so the framework can intervene.""".stripMargin
        val userPrompt = renderCheckpointPrompt(ctx, priorStatus, iteration)
        // #357 — the reflection normally judges on the agent's own model
        // (#320/#321). When `pinCoversAuxiliaryCalls` is set and the
        // conversation is pinned, the pin wins; otherwise the default
        // path is untouched (no conversation read).
        val resolveCheckpointModel: Task[Id[Model]] =
          if (!pinCoversAuxiliaryCalls) Task.pure(progressReflectionModelFor(agent))
          else withDB(_.conversations.transaction(_.get(convId)))
            .map(_.flatMap(_.pinnedModelId).getOrElse(progressReflectionModelFor(agent)))
        resolveCheckpointModel.flatMap { checkpointModelId =>
        sigil.tool.consult.ConsultTool.invoke[sigil.tool.consult.ProgressReflectionInput](
          sigil = this,
          modelId = checkpointModelId,
          chain = List(agent.id),
          systemPrompt = systemPrompt,
          userPrompt = userPrompt,
          tool = sigil.tool.consult.ProgressReflectionTool,
          generationSettings = sigil.tool.consult.ProgressReflectionTool.consultSettings
        ).flatMap {
        case None         => Task.pure(None)  // checkpoint-call failed; let the loop continue
        case Some(report) =>
          // Persist the checkpoint event so the chain is replayable.
          stallTask.flatMap { stall =>
            withDB(_.conversations.transaction(_.get(convId))).flatMap { convOpt =>
              val topicId = convOpt.flatMap(_.topics.lastOption.map(_.id))
                .getOrElse(_root_.sigil.conversation.Topic.id("__no_topic__"))
              // Sigil bug #124 — fold the objective stall signal into the
              // reflector's self-assessment. The agent's `meaningfulProgress`
              // self-report is necessary but not sufficient; if the
              // StallDetector spots an identical-call streak or empty-
              // payload streak, the persisted checkpoint records
              // `meaningfulProgress = false` regardless of what the agent
              // said, and `stuckOn` carries the detector's reason so the
              // intervention message names the loop concretely.
              val effectiveMeaningful = report.meaningfulProgress && !stall.detected
              val effectiveStuckOn    = stall.reason.orElse(report.stuckOn)
              val checkpoint = sigil.event.ProgressCheckpoint(
                participantId        = agent.id,
                conversationId       = convId,
                topicId              = topicId,
                iterationCount       = iteration,
                prevCheckpointStatus = priorStatus,
                currentStatus        = report.currentStatus,
                meaningfulProgress   = effectiveMeaningful,
                remainingSteps       = report.remainingSteps,
                stuckOn              = effectiveStuckOn,
                shouldAskUser        = report.shouldAskUser
              )
              publish(checkpoint).flatMap { _ =>
                // Update side-state for the next checkpoint comparison.
                state.lastStatus = Some(report.currentStatus)
                if (!effectiveMeaningful) {
                  state.noProgressStreak = state.noProgressStreak + 1
                } else {
                  state.noProgressStreak = 0
                }
                val stuck = state.noProgressStreak >= consecutiveNoProgressLimit
                if (report.shouldAskUser || stuck || stall.detected) {
                  val reason =
                    if (report.shouldAskUser)
                      s"I need clarification before I can continue. ${effectiveStuckOn.getOrElse("")}".trim
                    else if (stall.detected)
                      // Stall-detector hit on the current checkpoint —
                      // intervene immediately rather than waiting for
                      // `consecutiveNoProgressLimit` streaks to stack.
                      stall.reason.getOrElse(
                        s"I've made the same kind of call repeatedly without new information. How would you like me to proceed?"
                      )
                    else
                      s"I've been working on this for $iteration turns and haven't made meaningful " +
                        s"progress since: \"${priorStatus.getOrElse(report.currentStatus)}\". " +
                        s"${effectiveStuckOn.map(s => s"I'm stuck on: $s. ").getOrElse("")}" +
                        "How would you like me to proceed?"
                  // Bug #133 — distinguish "ask the user" (genuine
                  // terminal — needs human input) from "agent should
                  // act differently now" (directive — agent gets one
                  // more iteration). The caller in `runAgentLoop`
                  // routes each to the right shape. Constructing the
                  // Message with Standard role here is fine: the
                  // caller rewrites it to Tool-role + Agents
                  // visibility for the directive case.
                  Task.pure(Some(CheckpointIntervention(
                    message = Message(
                      participantId  = agent.id,
                      conversationId = convId,
                      topicId        = topicId,
                      content        = Vector(_root_.sigil.tool.model.ResponseContent.Text(reason)),
                      state          = EventState.Complete,
                      role           = MessageRole.Standard
                    ),
                    askingUser = report.shouldAskUser
                  )))
                } else Task.pure(None)
              }
            }
          }
      }.handleError { e =>
        Task(scribe.warn(s"runProgressCheckpoint failed for ${agent.id.value}/${convId.value} iter=$iteration: ${e.getMessage}"))
          .map(_ => None)
      }
      }
      }
    }
  }

  /** Load the context the reflection prompt needs: the user's most
    * recent substantive Message + the agent's tool-call history
    * since that message. Best-effort — failures fall through to
    * empty context rather than aborting the checkpoint. */
  private final def loadProgressContext(convId: Id[Conversation],
                                        agentId: ParticipantId): Task[ProgressContext] =
    withDB(_.conversationEvents(convId)).map { all =>
      val convEvents = all.iterator
        .collect { case e: Event if e.conversationId == convId => e }
        .toList
        .sortBy(_.timestamp.value)
      // #320 — the objective is the most-recent substantive (non-
      // continuation) user message, NOT the latest turn. A bare
      // "Proceed" advances the task; it isn't the task. Render the
      // continuation alongside so the reflector sees what was just
      // asked while judging progress against the real goal.
      val userMsgs = convEvents.collect {
        case m: Message
          if !m.participantId.isInstanceOf[sigil.participant.AgentParticipantId] &&
             m.role == MessageRole.Standard &&
             m.content.nonEmpty =>
          m
      }
      val (substantiveUser, directive) =
        sigil.conversation.ProgressTaskSelector.select(userMsgs, m => textOfContent(m.content))
      // #330 (defense in depth, same family as #320) — re-anchor on the
      // active objective when there's no substantive user message to judge
      // against (agent-initiated turns, and any non-user-driven loop). Use
      // the earliest substantive Standard message in the conversation as
      // the objective rather than treating "no user message" as evidence
      // of idle cycling and asking for clarification.
      val substantive = substantiveUser.orElse(
        convEvents.collect {
          case m: Message if m.role == MessageRole.Standard && m.content.nonEmpty => m
        }.minByOption(_.timestamp.value)
      )
      val task: Option[String]            = substantive.map(m => textOfContent(m.content))
      val latestDirective: Option[String] = directive.map(m => textOfContent(m.content))
      // Tool calls + agent responds since the OBJECTIVE (not the
      // continuation), so the history covers the whole arc of work.
      val cutoff = substantive.map(_.timestamp.value).getOrElse(0L)
      val historyEntries = scala.collection.mutable.ListBuffer.empty[String]
      // Sigil #265 — each tool transaction lives on a single stateful
      // ToolInvoke; the post-settle outcome is on the invoke itself,
      // so per-invoke rendering reads directly off the row.
      val invokesById = convEvents.collect {
        case ti: sigil.event.ToolInvoke if ti.timestamp.value > cutoff && ti.participantId == agentId => ti
      }
      val sortedInvokes = invokesById.sortBy(_.timestamp.value).take(20)  // cap the history
      sortedInvokes.foreach { ti =>
        val tail = ti.outcome match {
          case sigil.event.ToolOutcome.Success         => "OK"
          case sigil.event.ToolOutcome.Failure(_, _)   => "FAIL"
          case sigil.event.ToolOutcome.Pending         => "(no result yet)"
        }
        historyEntries += s"${ti.toolName.value} → $tail"
      }
      // Agent's own respond Messages count too — they're the "let me X" drafts.
      val agentResponds = convEvents.collect {
        case m: Message
          if m.timestamp.value > cutoff &&
             m.participantId == agentId &&
             m.role == MessageRole.Standard &&
             m.content.nonEmpty =>
          textOfContent(m.content)
      }
      if (agentResponds.size >= 2)
        historyEntries += s"respond × ${agentResponds.size} (latest: \"${snippet(agentResponds.last, 80)}\")"
      else
        agentResponds.foreach(r => historyEntries += s"respond → \"${snippet(r, 80)}\"")
      ProgressContext(userTask = task, toolHistory = historyEntries.toList, latestDirective = latestDirective)
    }.handleError(_ => Task.pure(ProgressContext(None, Nil)))

  /** Evaluate the agent's recent tool-call tail for objective stall
    * signals — identical-call streaks and empty-payload streaks.
    * Folds into the progress checkpoint's `meaningfulProgress`
    * computation. Best-effort: failures fall through to the empty
    * signal rather than aborting the checkpoint. */
  private final def evaluateStall(convId: Id[Conversation],
                                  agentId: ParticipantId): Task[sigil.conversation.compression.StallDetector.Signal] =
    withDB(_.conversationEvents(convId)).map { all =>
      val convEvents = all.iterator
        .collect { case e: Event if e.conversationId == convId => e }
        .toList
        .sortBy(_.timestamp.value)
      // Resolve the prior-checkpoint timestamp as the lower bound,
      // falling back to the most recent user Message when no prior
      // checkpoint exists, falling back to 0 otherwise.
      val priorCheckpointAt = convEvents.reverseIterator.collectFirst {
        case c: sigil.event.ProgressCheckpoint
          if c.participantId == agentId &&
             c.state == EventState.Complete =>
          c.timestamp.value
      }
      val cutoff = priorCheckpointAt.orElse {
        convEvents.reverseIterator.collectFirst {
          case m: Message
            if !m.participantId.isInstanceOf[sigil.participant.AgentParticipantId] &&
               m.role == MessageRole.Standard &&
               m.content.nonEmpty =>
            m.timestamp.value
        }
      }.getOrElse(0L)

      val invokes = convEvents.collect {
        case ti: sigil.event.ToolInvoke
          if ti.timestamp.value > cutoff &&
             ti.participantId == agentId &&
             !ti.internal => ti
      }
      val messagesByOrigin = convEvents.collect {
        case m: Message if m.role == MessageRole.Tool && m.origin.isDefined => m.origin.get -> m
      }.toMap
      val records = invokes.sortBy(_.timestamp.value).map { ti =>
        sigil.conversation.compression.StallDetector.CallRecord(
          invoke        = ti,
          resultMessage = messagesByOrigin.get(ti._id)
        )
      }
      sigil.conversation.compression.StallDetector.evaluate(records)
    }.handleError(_ => Task.pure(sigil.conversation.compression.StallDetector.Signal.Empty))

  /** Concatenate textual ResponseContent blocks; used to derive a
    * one-line view of a Message for the reflection prompt. */
  private final def textOfContent(blocks: Vector[_root_.sigil.tool.model.ResponseContent]): String =
    blocks.collect {
      case _root_.sigil.tool.model.ResponseContent.Text(t)     => t
      case _root_.sigil.tool.model.ResponseContent.Markdown(t) => t
    }.mkString(" ").trim

  private final def snippet(s: String, maxLen: Int): String =
    if (s.length <= maxLen) s else s.take(maxLen) + "…"

  /** Stitch the user task + tool history + prior checkpoint status
    * into the reflection prompt. Pure helper — useful to apps that
    * want to surface the same context shape to a custom reflection
    * tool, and to specs verifying the prompt structure. */
  def renderCheckpointPrompt(ctx: ProgressContext,
                             priorStatus: Option[String],
                             iteration: Int): String = {
    val taskBlock = ctx.userTask match {
      case Some(t) =>
        val directiveLine = ctx.latestDirective match {
          case Some(d) => s"The user has since said \"$d\" to continue this objective.\n\n"
          case None    => "\n"
        }
        s"The user's request:\n\"$t\"\n\n" + directiveLine
      case None    => "The user's request: (no recent substantive user message found)\n\n"
    }
    val historyBlock = ctx.toolHistory match {
      case Nil => "What you've done since: (no tool calls yet)\n\n"
      case list =>
        val numbered = list.zipWithIndex.map { case (line, i) => s"  ${i + 1}. $line" }.mkString("\n")
        s"What you've done since:\n$numbered\n\n"
    }
    val priorBlock = priorStatus match {
      case Some(s) => s"Prior checkpoint status: \"$s\"\n\n"
      case None    => "Prior checkpoint status: (first checkpoint)\n\n"
    }
    val ask =
      s"You are at iteration $iteration. " +
        s"Pick a one-line currentStatus describing where things stand RIGHT NOW. Set " +
        s"meaningfulProgress = true ONLY when you're substantively further than the prior status. " +
        s"One-line remainingSteps for what's left. Empty stuckOn unless you genuinely can't proceed. " +
        // #353 — a call shown as "OK" has COMPLETED; large results (images,
        // big reads) are stored out-of-line and won't appear inline. Only
        // "(no result yet)" is genuinely pending. Do not treat completed
        // calls as pending/processing — that false premise was stranding
        // turns behind a bogus clarification request.
        s"A tool call listed as \"OK\" SUCCEEDED — its result exists even if large and not shown here; " +
        s"only \"(no result yet)\" is still pending. Never set shouldAskUser because completed calls " +
        s"look resultless. shouldAskUser = true ONLY if the user must genuinely clarify something."
    taskBlock + historyBlock + priorBlock + ask
  }

  /** Publish a `Failure`-content Message into the conversation when
    * `runAgentLoop` crashes mid-turn. Lets clients render a red error
    * bubble in place of the frozen "still typing" indicator the
    * activity-state delta from `releaseClaim` would leave on its own.
    *
    * Best-effort: degenerate states (conversation gone, no topics) skip
    * publication rather than fabricate a topic id; the caller's
    * `releaseClaim` still flips the agent state to Idle/Complete. */
  /** Sigil bug #282 — publish a synthetic user-visible Message when
    * the agent loop terminates without any respond having fired.
    * Composes its body from the most recent [[sigil.event.ProgressCheckpoint]]
    * the agent persisted this turn (if any) so the user sees the
    * agent's own stated status / stuck-on reason instead of a blank
    * spinner. Falls back to a generic placeholder when no checkpoint
    * exists for this turn.
    *
    * Idempotent in spirit — callers should only invoke when
    * `userVisibleSeen` is false; the helper itself does NOT re-check
    * to keep the contract surfaced at the call site. */
  private final def synthesizeFallbackRespond(agent: AgentParticipant,
                                              convId: Id[Conversation]): Task[Unit] =
    latestCheckpointStatus(agent.id, convId).flatMap { checkpoint =>
      val body = checkpoint match {
        case Some(status) =>
          s"I wasn't able to complete the request before the turn ended. Status: $status"
        case None =>
          "The agent's turn ended without producing a reply. Please re-prompt or clarify the request."
      }
      withDB(_.conversations.transaction(_.get(convId))).flatMap {
        case None       => Task.unit
        case Some(conv) => conv.topics.headOption match {
          case None        => Task.unit
          case Some(topic) =>
            publish(Message(
              participantId  = agent.id,
              conversationId = convId,
              topicId        = topic.id,
              content        = Vector(ResponseContent.Text(body)),
              disposition    = sigil.event.MessageDisposition.Failure(recoverable = true),
              state          = EventState.Complete,
              role           = MessageRole.Standard,
              // Sigil bug #284 — stamp `source` so consumers render the
              // framework-synthesised fallback distinctly from a real
              // agent reply.
              source         = Some("orchestrator-fallback-respond")
            )).map(_ => ())
        }
      }
    }.handleError { e =>
      Task(scribe.warn(
        s"synthesizeFallbackRespond failed for ${agent.id.value}/${convId.value}: ${e.getMessage}"
      ))
    }

  /** Sigil #313 — reactive self-heal entry point invoked from the
    * agent loop's `handleError`.
    *
    * Returns `Some(retryTask)` when the heal applied and the agent
    * loop should run one more iteration (the retry); `None` when the
    * standard failure path should take over (no strategy matched, or
    * we've already healed this turn, or strict mode refused).
    *
    * Publishes a durable triple — [[sigil.event.ConversationCorruptionDetected]]
    * before the strategy runs, [[sigil.event.ConversationHealed]] /
    * [[sigil.event.HealingExhausted]] after — plus operational
    * [[sigil.signal.HealingActivityNotice]] pulses so log aggregators
    * see both the audit (durable) and alerting (transient) channels.
    *
    * Best-effort by design: a heal-pipeline DB / hub failure must
    * not mask the original error. Every publish runs with
    * `.handleError(_ => Task.unit)` so the worst case is "we lost
    * the audit pulse, the original failure still surfaces". */
  private final def tryHealAgentLoopError(agent: AgentParticipant,
                                          convId: Id[Conversation],
                                          claimed: AgentState,
                                          thrown: Throwable,
                                          healedThisTurn: java.util.concurrent.atomic.AtomicBoolean,
                                          healCorrelationId: AtomicReference[Option[String]]): Task[Option[Task[Unit]]] = {
    val matchedOpt: Option[sigil.heal.HealingStrategy] = healingStrategies.find(_.matches(thrown))
    matchedOpt match {
      case None => Task.pure(None)
      case Some(strategy) =>
        val mode = healingMode
        // `scribe.error` the original error with full payload BEFORE
        // any audit publishes — log aggregators see the upstream
        // failure regardless of what the audit pipeline does next.
        scribe.error(
          s"heal[${strategy.name}] caught ${thrown.getClass.getSimpleName} on " +
            s"${agent.id.value}/${convId.value}: ${Option(thrown.getMessage).getOrElse("(no message)")}",
          thrown
        )
        val evidence = strategy.detect(thrown)
        val errorEvidence = sigil.event.ErrorEvidence.of(thrown)
        val correlation: String = healCorrelationId.updateAndGet { current =>
          current.orElse(Some(TurnContext.freshCorrelationId()))
        }.getOrElse(TurnContext.freshCorrelationId())
        // Always publish CorruptionDetected — both Recover and Strict
        // paths record the corruption. Strict refuses the heal but
        // the durable trail must show the failure was seen.
        val detectorSource: String = thrown match {
          case _: sigil.heal.BrokenHistoryException => "renderFrames-invariant"
          case _                                    => "provider-call"
        }
        val publishDetected: Task[Unit] = withDB(_.conversations.transaction(_.get(convId))).flatMap {
          case None       => Task.unit
          case Some(conv) =>
            conv.topics.headOption match {
              case None        => Task.unit
              case Some(topic) =>
                publish(sigil.event.ConversationCorruptionDetected(
                  conversationId     = convId,
                  topicId            = topic.id,
                  detectorSource     = detectorSource,
                  originalError      = errorEvidence,
                  correlationId      = correlation,
                  modelId            = Some(agent.modelId),
                  detectedCorruption = evidence,
                  participantId      = agent.id
                )).map(_ => ())
            }
        }.handleError { e =>
          scribe.warn(s"heal[${strategy.name}] publishDetected failed for ${convId.value}: " +
            s"${e.getClass.getSimpleName}: ${Option(e.getMessage).getOrElse("")}")
          Task.unit
        }

        mode match {
          case sigil.heal.HealingMode.Strict =>
            // Record corruption, fire StrictRefused notice, fall
            // through to the existing failure path (the original
            // throwable re-raises through the agent loop's standard
            // path).
            val notify: Task[Unit] = Task {
              hub.emit(sigil.signal.HealingActivityNotice(
                conversationId     = convId,
                strategyName       = strategy.name,
                detectedCorruption = evidence,
                outcome            = sigil.heal.HealingOutcome.StrictRefused
              ))
              ()
            }.handleError(_ => Task.unit)
            publishDetected.flatMap(_ => notify).map(_ => None)
          case sigil.heal.HealingMode.Recover =>
            // Publish a terminal `HealingExhausted` event for this
            // turn. Shared by the retry-also-failed path and the
            // no-op-heal path (Sigil #314) — both are terminal "the
            // heal couldn't recover this turn" outcomes that must
            // fall through to the standard failure path.
            val publishExhausted: Task[Unit] = withDB(_.conversations.transaction(_.get(convId))).flatMap {
              case None       => Task.unit
              case Some(conv) =>
                conv.topics.headOption match {
                  case None        => Task.unit
                  case Some(topic) =>
                    publish(sigil.event.HealingExhausted(
                      conversationId = convId,
                      topicId        = topic.id,
                      correlationId  = correlation,
                      strategyName   = strategy.name,
                      retryError     = errorEvidence,
                      participantId  = agent.id
                    )).map(_ => ())
                }
            }.handleError(_ => Task.unit)
            def notifyOutcome(outcome: sigil.heal.HealingOutcome): Task[Unit] = Task {
              hub.emit(sigil.signal.HealingActivityNotice(
                conversationId     = convId,
                strategyName       = strategy.name,
                detectedCorruption = evidence,
                outcome            = outcome
              ))
              ()
            }.handleError(_ => Task.unit)
            if (!healedThisTurn.compareAndSet(false, true)) {
              // We already healed this turn AND the retry failed.
              // Publish HealingExhausted + Exhausted notice and fall
              // through. NO second heal.
              publishExhausted.flatMap(_ => notifyOutcome(sigil.heal.HealingOutcome.Exhausted)).map(_ => None)
            } else {
              // First heal this turn — publish Detected, run strategy,
              // publish Healed + Healed notice, return retry task.
              val runStrategy: Task[sigil.heal.HealResult] =
                strategy.apply(evidence, convId, this).handleError { e =>
                  scribe.error(s"heal[${strategy.name}] strategy.apply threw on " +
                    s"${agent.id.value}/${convId.value}", e)
                  Task.pure(sigil.heal.HealResult(
                    corrections     = Nil,
                    remainingIssues = List(s"strategy.apply threw ${e.getClass.getSimpleName}: ${Option(e.getMessage).getOrElse("")}")
                  ))
                }
              val publishHealed: sigil.heal.HealResult => Task[Unit] = result =>
                withDB(_.conversations.transaction(_.get(convId))).flatMap {
                  case None       => Task.unit
                  case Some(conv) =>
                    conv.topics.headOption match {
                      case None        => Task.unit
                      case Some(topic) =>
                        publish(sigil.event.ConversationHealed(
                          conversationId  = convId,
                          topicId         = topic.id,
                          correlationId   = correlation,
                          strategyName    = strategy.name,
                          corrections     = result.corrections,
                          remainingIssues = result.remainingIssues,
                          participantId   = agent.id
                        )).map(_ => ())
                    }
                }.handleError(_ => Task.unit)
              // Build the retry — same iteration recurse so the
              // healed-history check runs fresh. We deliberately
              // re-use the SAME claimed (the claim hasn't released);
              // iteration count keeps advancing.
              val retryTask: Task[Unit] = runAgentLoop(
                agent                     = agent,
                convId                    = convId,
                claimed                   = claimed,
                iteration                 = 1,
                sinceTimestamp            = claimed.timestamp,
                greeting                  = false,
                userVisibleSeen           = new java.util.concurrent.atomic.AtomicBoolean(false),
                turnExtractorFired        = new java.util.concurrent.atomic.AtomicBoolean(false),
                failurePublished          = new java.util.concurrent.atomic.AtomicBoolean(false),
                discoveredCapabilitiesRef = new AtomicReference(Map.empty),
                healedThisTurn            = healedThisTurn,
                healCorrelationId         = healCorrelationId
              )
              val pipeline: Task[Option[Task[Unit]]] = for {
                _      <- publishDetected
                result <- runStrategy
                out    <- if (evidence.nonEmpty && result.corrections.isEmpty) {
                            // Sigil #314 — no-op heal: the strategy
                            // matched and ran but settled NOTHING
                            // against non-empty corruption evidence.
                            // Retrying would replay the identical
                            // broken frames and exhaust on the second
                            // pass — a heal masquerading as a fix.
                            // Don't publish `ConversationHealed`;
                            // mark the turn terminally exhausted, fire
                            // a `Failed` notice, give back the turn's
                            // allowance, and fall through to the
                            // standard failure path so the user sees a
                            // Failure bubble instead of a silent stall.
                            scribe.error(
                              s"heal[${strategy.name}] resolved ZERO corrections against ${evidence.size} " +
                                s"corruption row(s) for ${agent.id.value}/${convId.value} — treating as a failed " +
                                s"heal (no retry). remainingIssues=[${result.remainingIssues.mkString("; ")}]"
                            )
                            healedThisTurn.set(false)
                            publishExhausted
                              .flatMap(_ => notifyOutcome(sigil.heal.HealingOutcome.Failed))
                              .map(_ => Option.empty[Task[Unit]])
                          } else {
                            publishHealed(result)
                              .flatMap(_ => notifyOutcome(sigil.heal.HealingOutcome.Healed))
                              .map(_ => Some(retryTask))
                          }
              } yield out
              pipeline
            }
        }
    }
  }

  private final def publishFailureMessage(agent: AgentParticipant,
                                          convId: Id[Conversation],
                                          t: Throwable): Task[Unit] =
    withDB(_.conversations.transaction(_.get(convId))).flatMap {
      case None => Task.unit
      case Some(conv) => conv.topics.headOption match {
        case None        => Task.unit
        case Some(topic) =>
          val reason = Option(t.getMessage).filter(_.nonEmpty)
            .map(m => s"${t.getClass.getSimpleName}: $m")
            .getOrElse(t.getClass.getSimpleName)
          val ec = sigil.event.ErrorContext.classify(t)
          // Sigil bug #282 — enrich the user-facing failure body with
          // the agent's most recent self-reported status (from a
          // ProgressCheckpoint event) when available. The agent
          // typically knows what it was working on / stuck on; surfacing
          // that text alongside the exception class makes the failure
          // bubble actionable instead of just technical.
          latestCheckpointStatus(agent.id, convId).flatMap { checkpoint =>
            val body = checkpoint match {
              case Some(status) => s"$reason\n\nLast reported status: $status"
              case None         => reason
            }
            publish(Message(
              participantId  = agent.id,
              conversationId = convId,
              topicId        = topic.id,
              content        = Vector(sigil.tool.model.ResponseContent.Text(body)),
              disposition    = sigil.event.MessageDisposition.Failure(
                recoverable  = false,
                errorContext = Some(ec)
              ),
              state          = EventState.Complete,
              role           = MessageRole.Standard
            )).map(_ => ())
          }
      }
    }

  /** Sigil bug #282 — return the agent's most recent
    * [[sigil.event.ProgressCheckpoint]] status text for this
    * conversation, formatted with stuck-on + remaining-steps when
    * present. Best-effort — failures fall through to None rather than
    * aborting the failure-publish path. */
  private final def latestCheckpointStatus(agentId: ParticipantId,
                                           convId: Id[Conversation]): Task[Option[String]] =
    withDB(_.conversationEvents(convId)).map { events =>
      events.collect {
        case cp: sigil.event.ProgressCheckpoint if cp.participantId == agentId => cp
      }.maxByOption(_.timestamp.value).map { cp =>
        val statusLine = if (cp.currentStatus.nonEmpty) cp.currentStatus else "(no status text)"
        val stuckLine  = cp.stuckOn.filter(_.nonEmpty).map(s => s"\n\nStuck on: $s").getOrElse("")
        val nextLine   = if (cp.remainingSteps.nonEmpty) s"\n\nRemaining: ${cp.remainingSteps}" else ""
        s"$statusLine$stuckLine$nextLine"
      }
    }.handleError(_ => Task.pure(None))

  private final def releaseClaim(claimed: AgentState): Task[Unit] = {
    // Always-run cleanup — the flag must never leak even if the
    // Idle/Complete publish fails (broadcaster error, DB error, etc.).
    // A leaked flag is a minor map entry, but across many failures it'd
    // accumulate and match future Stop events against a non-existent
    // claim.
    val cleanup = Task {
      stopFlags.remove(claimed._id)
      checkpointStates.remove(claimed._id)
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
                                 claimedId: Id[Event],
                                 claimedTimestamp: Timestamp,
                                 isGreeting: Boolean = false,
                                 discoveredCapabilitiesRef: AtomicReference[Map[String, sigil.conversation.DiscoveredCapability]] =
                                   new AtomicReference(Map.empty),
                                 healedThisTurn: Option[java.util.concurrent.atomic.AtomicBoolean] = None): Task[(TurnContext, Stream[Event])] =
    for {
      triggerEvents <- withDB(_.eventsTransaction(conv._id)(_.list)).map { all =>
        all.view
          .filter(e => e.conversationId == conv._id
                    && e.timestamp.value > sinceTimestamp.value
                    && TriggerFilter.isTriggerFor(agent, e)
                    && visibilityAllows(e.visibility, agent.id))
          .toList
      }
      _ = {
        // Sigil #313 — reset the heal allowance when a fresh user-
        // authored Standard Message drains as a trigger. The agent's
        // claim can survive across multiple user messages (the #307
        // scenario — new user msg arriving mid-loop joins the
        // existing claim), so the heal boundary aligns with the
        // human notion of "turn" (one user message → one heal
        // chance) rather than the wider claim arc. Predicate matches
        // [[sigil.conversation.compression.CompactionInvariant.CurrentUserTaskMessage]]'s
        // rule so the heal and curator boundaries are derived from
        // the same source.
        healedThisTurn.foreach { flag =>
          val freshUserTurn = triggerEvents.exists {
            case m: Message
              if m.role == MessageRole.Standard
              && !m.participantId.isInstanceOf[AgentParticipantId] => true
            case _ => false
          }
          if (freshUserTurn) flag.set(false)
        }
      }
      chain = buildChain(triggerEvents, agent)
      // Sigil bug #205 — resolve the actually-routed model up-front so
      // the curator's budget gate sees the right context window. The
      // agent's nominal `modelId` is frequently a small-context default
      // (e.g. a local llama loaded at boot from
      // `provider.models.headOption`), but per-turn routing escalates
      // to a frontier candidate via `classifyForRoute`. Budgeting against
      // `agent.modelId` triggers aggressive compression that strips
      // context the routed model would have comfortably accepted.
      routedModelId <- resolveRoutedModelId(agent, conv, chain, claimedId)
      // Sigil #277 — resolve the routed id to a registered Model record.
      // Cache miss throws UnregisteredModelException at the boundary
      // (instead of silently falling through per-fact defaults later).
      routedModel = cache.find(routedModelId).getOrElse(
                      throw new sigil.provider.UnregisteredModelException(routedModelId, cache.all.map(_._id))
                    )
      input         <- curate(conv._id, routedModelId, chain)
    } yield {
      val triggers: Stream[Event] = Stream.emits(triggerEvents)
      val ctx = TurnContext(
        sigil               = this,
        chain               = chain,
        conversation        = conv,
        turnInput           = input,
        model               = routedModel,
        currentAgentStateId = Some(claimedId),
        turnStartedAt       = Some(claimedTimestamp),
        isGreeting          = isGreeting,
        discoveredCapabilitiesRef = discoveredCapabilitiesRef
      )
      (ctx, triggers)
    }

  /** Sigil bug #205 — resolve the model id this turn will route to,
    * before [[curate]] runs. Delegates to the shared [[resolveRouting]]
    * helper (same logic `runAgentTurn` uses), but passes a stub
    * TurnContext for the classifier callbacks since the full one (with
    * curated turnInput) isn't yet available.
    *
    * `classifyForRoute` memoizes on the user message id, so the
    * duplicate classifier call inside `runAgentTurn` later in the turn
    * hits the cache and adds no LLM round-trip cost. */
  private final def resolveRoutedModelId(agent: AgentParticipant,
                                         conv: Conversation,
                                         chain: List[ParticipantId],
                                         claimedId: Id[Event]): Task[Id[Model]] = {
    // Stub TurnContext for classifier callbacks. `turnInput` is empty
    // because curate hasn't run yet — classifiers that need full
    // context override the strategy's classifier implementation
    // entirely; the default classifiers only read userText + the
    // conversation reachable through the stub.
    //
    // Sigil #277 — the stub's `model` is the agent's nominal Model
    // (resolved at the boundary). The routing might escalate to a
    // different model after the classifier runs, but the nominal is a
    // sane placeholder for the pre-route phase.
    val nominalModel = cache.find(agent.modelId).getOrElse(
      throw new sigil.provider.UnregisteredModelException(agent.modelId, cache.all.map(_._id))
    )
    val stubCtx = TurnContext(
      sigil               = this,
      chain               = chain,
      conversation        = conv,
      turnInput           = sigil.conversation.TurnInput(sigil.conversation.ConversationView(conversationId = conv._id)),
      model               = nominalModel,
      currentAgentStateId = Some(claimedId)
    )
    resolveRouting(agent, conv, stubCtx).map(_.modelId)
  }

  private final def newTriggersExist(agent: AgentParticipant,
                                     conv: Conversation,
                                     sinceTimestamp: Timestamp): Task[Boolean] =
    withDB(_.eventsTransaction(conv._id)(_.list)).map { all =>
      all.exists(e => e.conversationId == conv._id
                   && e.timestamp.value > sinceTimestamp.value
                   && TriggerFilter.isTriggerFor(agent, e))
    }

  /** Like [[newTriggersExist]] but only counts triggers authored by
    * someone OTHER than the running agent. Used after a turn the agent
    * ended with a user-visible terminal tool: the turn's own emitted
    * events (the reply `Message`, the terminal tool's `ToolResults`)
    * must not keep the loop alive — only a genuine external message
    * that landed mid-turn should. */
  private final def externalTriggersExist(agent: AgentParticipant,
                                          conv: Conversation,
                                          sinceTimestamp: Timestamp): Task[Boolean] =
    withDB(_.eventsTransaction(conv._id)(_.list)).map { all =>
      all.exists(e => e.conversationId == conv._id
                   && e.timestamp.value > sinceTimestamp.value
                   && e.participantId != agent.id
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
      _ = ParticipantId.register((summon[RW[sigil.participant.WorkerParticipantId]] :: participantIds).distinctBy(_.definition.className)*)
      _ = Mode.register((ConversationMode :: modes).distinct.map(m => RW.static(m))*)
      _ = sigil.provider.WorkType.register(workTypes.map(w => RW.static(w))*)
      // Mixin hook — runs AFTER the framework leaf polytypes (SpaceId,
      // ParticipantId, Mode, WorkType, …) register but BEFORE any aggregate
      // that walks tool / participant / signal Definitions. A mixin polytype
      // referenced by a tool input (e.g. a `WorkflowStepInput` in
      // `create_workflow`'s `steps` schema) MUST be registered before the
      // `ToolInput.register` below forces those input Definitions via
      // `.distinctBy(_.definition.className)` — accessing `.definition`
      // freezes the lazy-val snapshot, so a subtype registered afterward never
      // appears in the rendered schema (the field collapses to `array<string>`
      // and no agent can fill it). WorkflowSigil registers `WorkflowTrigger`
      // first, then `WorkflowStepInput` (which references it), so the leaf-poly
      // ordering #18 guards against is preserved here too.
      _ <- mixinPolymorphicRegistrations
      // Bug #53 — `toolInputRegistrations` is the mixin extension
      // point for non-static tools whose `inputRW` isn't reachable
      // through the static-roster scan (notably `JsonInput`, used by
      // `ScriptTool` and `McpTool`). Without including it here,
      // `ScriptSigil` / `McpSigil` apps would crash at the first
      // runtime tool's `ToolInvoke` persistence with `Type not found
      // [JsonInput]`.
      _ = ToolInput.register((CoreTools.inputRWs ++ findTools.toolInputRWs ++ toolInputRegistrations).distinctBy(_.definition.className)*)
      // Sigil #265 — `ToolOutput` is a polymorphic discriminator on
      // `ToolInvoke.output` (the field that replaces the pre-#265
      // separate `ToolResults` event). Register the framework-shipped
      // `Pending` / `Progress` cases, every `staticTools` output RW
      // (auto-discovery — each `Tool` carries `outputRW`), and any
      // app-defined output subtypes so `ToolInvoke` RW round-trips
      // cleanly through persistence and the wire.
      staticOutputRWs = staticTools.map(_.outputRW.asInstanceOf[RW[? <: sigil.tool.ToolOutput]])
      _ = sigil.tool.ToolOutput.register(
            (sigil.tool.ToolOutput.frameworkOutputRWs ++ staticOutputRWs ++ toolOutputRegistrations)
              .distinctBy(_.definition.className)*
          )
      _ = sigil.viewer.ViewerStatePayload.register(viewerStatePayloadRegistrations.distinct*)
      // Heal-pipeline polytypes. The framework-shipped CorruptionEvidence
      // subtypes are registered here; apps with their own evidence
      // shapes register through `corruptionEvidenceRegistrations`.
      _ = sigil.heal.CorruptionEvidence.register(
            (List[RW[? <: sigil.heal.CorruptionEvidence]](
              summon[RW[sigil.heal.CorruptionEvidence.MissingToolResult]],
              summon[RW[sigil.heal.CorruptionEvidence.DanglingToolResultOrigin]],
              summon[RW[sigil.heal.CorruptionEvidence.OrphanSummaryCoverage]]
            ) ++ corruptionEvidenceRegistrations).distinct*
          )
      // Aggregates after leaves + mixins.
      _ = Participant.register((summon[RW[DefaultAgentParticipant]] :: participants)*)
      _ = sigil.tool.Tool.register((staticTools.map(t => RW.static(t)) ++ toolRegistrations).distinct*)
      _ = sigil.skill.Skill.register((staticSkills.map(s => RW.static(s)) ++ skillRegistrations).distinct*)
      _ = Signal.register((allEventRWs ++ allDeltaRWs ++ allNoticeRWs ++ signalRegistrations)*)
    } yield ()
  }.singleton

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
        appUpgrades = List(
          // Sigil #294 — rescue boot for pre-#265 databases by
          // nulling `Message.contextFrame` rows that still carry
          // the retired `ToolResult` discriminator. Runs first so
          // the static-tool / static-skill upgrades (which stream
          // their own poly-typed records) can't trip the same
          // dead-discriminator path on a downstream collection.
          new sigil.upgrade.ContextFrameToolResultMigrationUpgrade,
          new sigil.tool.StaticToolSyncUpgrade(staticTools),
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

  /** Sigil bug #172 — at every boot, reconcile any `Event` left at
    * `state = Active` in `db.events`. A process exit mid-turn (crash,
    * OOM, SIGKILL, container eviction) strands the in-flight event:
    * UIs render Messages stuck Active as forever-loading bubbles;
    * ToolInvokes left Active block subsequent agent logic that
    * checks "is the agent busy?".
    *
    * Bug #171 fixed the in-flight orphan case forward (parse-failure
    * settle). This is the catch-up for orphans from prior process
    * exits AND for future hard-crash orphans that bypass #171's
    * reconciliation point.
    *
    * Reconciliation rules:
    *   - `Message` → state Complete, disposition Failure(recoverable
    *     = false) with an ErrorContext explaining "stale from prior
    *     session". Content preserved (whatever partial streamed text
    *     was persisted) so the user can see what was lost.
    *   - All other Event types → state Complete via `.withState`.
    *
    * Runs synchronously before WS / Notice ingress opens (placed
    * between `db.init` and the model-refresh / maintenance-task
    * fibers), so there are no live subscribers to confuse with the
    * recovery writes. One bulk transaction per bug #170's pattern. */
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
            case other => other.withState(sigil.signal.EventState.Complete)
          }
          Task.sequence(reconciled.map(tx.upsert)).map { _ =>
            scribe.info(s"reconcileStaleActiveEvents: closed ${reconciled.size} stale Active event(s)")
          }
        }
      }
    }

  /** Per-mode share of the smallest registered model's context window
    * a Mode's bundled skill content is allowed to consume. Default
    * 10% — a mode skill that exceeds this at startup fails the
    * `Sigil.instance` task with `IllegalStateException` so the app
    * can't ship a configuration that pre-emptively crowds the budget.
    * Distinct from [[pinnedShareLimit]]: mode skills are app-shipped
    * config (a config bug should fail-loud at startup); pinned
    * memories are runtime-authored (a soft warning fits better there). */
  def modeSkillShareLimit: Double = 0.10

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
   * Sigil #277 — boot-time model-catalog load + refresh.
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
      _           <- if (stored.list.nonEmpty) cache.replace(stored.list) else Task.unit
      isFresh     = stored.list.nonEmpty &&
                      (Timestamp().value - stored.refreshed.value) < modelRefreshInterval.toMillis
      _           <- if (isFresh) Task.unit else blockingRefresh(db, hadPriorCache = stored.list.nonEmpty)
      // Re-read after the (possibly-just-run) blocking refresh so the
      // schedule's first sleep aligns with the latest stamp.
      latest      <- db.models.get()
      _           <- scheduleNextRefresh(db, latest.refreshed)
    } yield ()

  /** One-shot blocking refresh from OpenRouter. Delegates to the
    * boot-safe (sigil, db) overload of [[OpenRouter.refreshModels]] so
    * the boot fiber doesn't re-enter [[withDB]] — that would await the
    * in-flight `Sigil.instance.singleton` against itself and deadlock
    * (sigil bug #281). Post-boot callers use the public 1-arg overload
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
   * Apps and the framework's own subsystems plug periodic
   * housekeeping work in here — TTL sweeps, cache rotations,
   * schema-upgrade rechecks, etc. Each [[sigil.maintenance.MaintenanceTask]]
   * gets its own background fiber managed by [[startMaintenanceTasks]].
   *
   * Default: empty. The framework's own maintenance tasks
   * ([[sigil.maintenance.StoredFileExpirationSweep]] for Bug #9's
   * tool-output retention) plug in here as they ship.
   *
   * Apps override and concatenate to add their own tasks:
   *
   * {{{
   *   override def maintenanceTasks: List[MaintenanceTask] =
   *     super.maintenanceTasks ++ List(MyAppCacheRotation, MyAppMetricsFlush)
   * }}}
   */
  def maintenanceTasks: List[sigil.maintenance.MaintenanceTask] =
    List(
      sigil.maintenance.StoredFileExpirationSweep(storedFileExpirationInterval),
      sigil.maintenance.OrphanStagingConversationSweep(orphanStagingSweepInterval, orphanStagingCutoff)
    )

  /** Cadence for [[sigil.maintenance.OrphanStagingConversationSweep]] —
    * how often the framework reaps abandoned staging conversations
    * left behind by crashed / killed import workflows. Default: 1
    * hour. */
  def orphanStagingSweepInterval: scala.concurrent.duration.FiniteDuration =
    scala.concurrent.duration.DurationInt(1).hour

  /** Age threshold a staging conversation must exceed before the
    * orphan sweep deletes it. Generous default (24h) so legit
    * long-running imports finish without false-reaping; apps
    * running unusually long imports override. */
  def orphanStagingCutoff: scala.concurrent.duration.FiniteDuration =
    scala.concurrent.duration.DurationInt(24).hours

  /** Cadence for [[sigil.maintenance.StoredFileExpirationSweep]] —
    * how often the framework reclaims expired
    * [[sigil.storage.StoredFile]] records (TTL'd user attachments
    * and externalized message-content blocks past their retention
    * window). Default: 1 hour. Apps with stricter retention or
    * larger volumes override. */
  def storedFileExpirationInterval: scala.concurrent.duration.FiniteDuration =
    scala.concurrent.duration.DurationInt(1).hour

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
    * deadlocks silently (sigil bug #281). Boot-path code receives the
    * `db` as a parameter — pass it through directly. See
    * [[OpenRouter.refreshModels]]'s `(sigil, db)` overload for the
    * canonical pattern. */
  def withDB[Return](f: DB => Task[Return]): Task[Return] = instance.flatMap(sigil => f(sigil.db))

  // -- active tasks --

  /**
   * In-flight tasks (worker delegations + scheduled / running workflows)
   * tied to `conversationId`. Default returns Nil for apps that don't mix
   * in the workflow runtime; [[sigil.workflow.WorkflowSigil]] overrides
   * with the live query against `db.workflows`.
   *
   * UI surfaces use this for the per-conversation "what's running"
   * panel — sticky cards that hang around until the underlying
   * workflow run settles. Apps fetching the conversation should
   * pair this with `Conversation` itself; the projection is computed
   * on demand rather than persisted on the record so workflow-state
   * changes don't have to ripple through a denormalization step.
   */
  def activeTasksFor(conversationId: Id[Conversation]): Task[List[sigil.conversation.ConversationTask]] =
    withDB(_.conversations.transaction(
      // Query the `parentConversationId` index (sigil #289) rather than
      // scanning every conversation — a panel refresh shouldn't be O(all
      // conversations).
      _.query.filter(_.parentConversationId === Some(conversationId)).toList
    )).flatMap { children =>
      val workers = children.filter(!_.archived)
      Task.sequence(workers.map(workerTaskFor)).map(_.flatten)
    }

  /** Project a worker sub-conversation into a [[sigil.conversation.ConversationTask]],
    * reading the worker agent's live [[AgentState]] to mark it
    * Running (mid-turn) vs Waiting (yielded). `None` when the
    * conversation carries no worker agent. */
  protected final def workerTaskFor(conv: Conversation): Task[Option[sigil.conversation.ConversationTask]] =
    conv.participants.collectFirst {
      case a: AgentParticipant if a.id.isInstanceOf[sigil.participant.WorkerParticipantId] => a.id
    } match {
      case None => Task.pure(None)
      case Some(workerId) =>
        withDB(_.events.transaction(_.get(agentStateLockId(workerId, conv._id)))).map { st =>
          val active = st.exists {
            case s: AgentState => s.state == EventState.Active
            case _             => false
          }
          Some(sigil.conversation.ConversationTask.fromWorkerConversation(conv, active))
        }
    }

  /**
   * Global view across every conversation `viewer` can see. Default
   * returns Nil; [[sigil.workflow.WorkflowSigil]] overrides with a
   * `viewer`-scoped query.
   *
   * Lets UIs render a "what am I currently running, anywhere?"
   * sidebar without forcing the user to remember which conversation
   * spawned which task. Visibility is intentionally
   * conversation-membership based by default — viewers see tasks in
   * conversations they're a participant in. Apps with custom
   * authorization layer (admin viewers, multi-tenant scoping)
   * override the WorkflowSigil-side filter or wrap this method.
   */
  def activeTasks(viewer: ParticipantId): Task[List[sigil.conversation.ConversationTask]] =
    // Narrow to worker sub-conversations via the index first
    // (participant membership isn't indexed, so the viewer filter still
    // runs in memory — but only over the worker set, not all conversations).
    withDB(_.conversations.transaction(_.query.filter(_.parentConversationId !== None).toList)).flatMap { children =>
      val workers = children.filter(c => !c.archived && c.participants.exists(_.id == viewer))
      Task.sequence(workers.map(workerTaskFor))
        .map(_.flatten.sortBy(_.modifiedAt.value)(using Ordering.Long.reverse))
    }

  /**
   * Sub-conversation cost rollup. Returns `conversationId.cost` plus
   * the recursively-summed cost of every conversation that lists
   * `conversationId` (transitively) as its `parentConversationId`.
   *
   * Worker delegation creates a hierarchy — user-facing conv → worker
   * conv → potentially sub-worker convs — and apps showing total
   * cost for a top-level conversation want the inclusive figure. Each
   * conversation's own `cost` field is incremented by the framework
   * on settled provider calls (see [[sigil.signal.ConversationCostUpdated]]);
   * this method walks the tree at query time.
   *
   * Returns 0 if `conversationId` doesn't exist. Cycles in the parent
   * relationship would loop forever — the framework's spawn surface
   * doesn't create cycles, but apps with hand-rolled hierarchies
   * should ensure they don't either.
   */
  def totalCostFor(conversationId: Id[Conversation]): Task[BigDecimal] =
    withDB(_.conversations.transaction(_.list)).flatMap { allConvs =>
      val byParent: Map[Id[Conversation], List[Conversation]] =
        allConvs.groupBy(_.parentConversationId.getOrElse(Id[Conversation]("")))
          .filter(_._1.value.nonEmpty)

      def sum(id: Id[Conversation]): BigDecimal = {
        val self = allConvs.find(_._id == id).map(_.cost).getOrElse(BigDecimal(0))
        val children = byParent.getOrElse(id, Nil)
        self + children.map(c => sum(c._id)).sum
      }

      Task.pure(sum(conversationId))
    }

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

  /**
   * Sigil #277 — load the OpenRouter model catalog at startup? Default
   * `true`. The framework's invariant ([[sigil.provider.UnregisteredModelException]])
   * requires every model used at runtime to be in the in-memory
   * [[sigil.cache.ModelRegistry]]; OpenRouter is the only complete
   * programmatic source for the public Anthropic / OpenAI / Gemini
   * namespace, so loading it at boot is the canonical registration path.
   *
   * Apps that don't need the public catalog (LlamaCpp-only setups,
   * tests with pre-registered synthetic models, custom-catalog
   * deployments) override to `false` — `Sigil.instance` then skips
   * the catalog read + refresh + schedule entirely. Those apps
   * populate the registry themselves via `sigil.cache.merge(...)`,
   * `LlamaCppProvider`'s on-construction merge, or app-side seeding.
   */
  def loadOpenRouterModels: Boolean = true

  /**
   * Sigil #277 — how stale a `models.refreshed` stamp can be before
   * `Sigil.instance` blocks on a fresh `OpenRouter.refreshModels` at
   * boot, and how long the background refresh task sleeps between
   * runs once started. Default 8 hours.
   *
   * Boot semantics: if the persisted `db.models.refreshed` is older
   * than this AND the list is non-empty, the boot proceeds with the
   * stale catalog (the refresh runs in the background). If the list
   * is empty (never refreshed), the boot blocks on the refresh; on
   * failure with no prior cache, `Sigil.instance` errors — models
   * are required.
   */
  def modelRefreshInterval: FiniteDuration = 8.hours

  /**
   * How often the framework hard-deletes expired memories
   * (`expiresAt` set and not in the future). Default `None` —
   * expired records simply stay invisible to retrieval (filtered
   * per turn by [[StandardMemoryRetriever.isExpired]]) but the rows
   * persist forever. Apps that want hard eviction (DB rows + vector
   * index points) opt in by overriding to e.g. `Some(1.day)`. Apps
   * that want a different cadence override; apps that want a custom
   * sweep shape override [[sweepExpiredMemories]] directly.
   */
  def expiredMemorySweepInterval: Option[FiniteDuration] = None

  /**
   * In-memory model registry — the canonical source of catalog
   * lookups. `Provider.models` and `isImageOnlyModel`-style hot paths
   * read it synchronously (single `AtomicReference` deref, no DB
   * round-trip). Populated from disk on init and refreshed in the
   * background per [[modelRefreshInterval]].
   */
  final lazy val cache: ModelRegistry = new ModelRegistry

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

object Sigil {

  /** Sigil #290 — USD cost of a settled provider call from its
    * [[TokenUsage]] and the model's [[sigil.db.ModelPricing]].
    *
    * `usage.promptTokens` is the SUM of every billed input token —
    * fresh + cache writes + cache reads — per the [[TokenUsage]]
    * "cache fields are subsets" contract. This method breaks the
    * sum apart and applies each rate independently:
    *
    *   - fresh  =  promptTokens − cacheReadTokens − cacheCreationTokens
    *   - reads  ×  inputCacheRead  (fallback `prompt × 0.10` — Anthropic's
    *                                documented cache-hit discount)
    *   - writes ×  inputCacheWrite (fallback `prompt × 1.25` — Anthropic's
    *                                documented cache-creation premium)
    *   - completion × output tokens
    *
    * Providers without prompt caching (LlamaCpp etc.) report both
    * cache fields as `0` so the fallback multipliers never apply
    * and the math collapses to the prior fresh-only formula.
    *
    * Pre-fix (Sigil #290), the cost site used
    * `prompt × promptTokens + completion × completionTokens` only,
    * which silently dropped the cache buckets on Anthropic — a
    * 50K-token cached prefix would shave ~99% of the input billing
    * off the surfaced number. */
  def costFor(pricing: sigil.db.ModelPricing, usage: sigil.provider.TokenUsage): BigDecimal = {
    val freshPrompt = math.max(0, usage.promptTokens - usage.cacheReadTokens - usage.cacheCreationTokens)
    val cacheReadRate  = pricing.inputCacheRead.getOrElse(pricing.prompt * BigDecimal("0.10"))
    val cacheWriteRate = pricing.inputCacheWrite.getOrElse(pricing.prompt * BigDecimal("1.25"))
    pricing.prompt     * BigDecimal(freshPrompt) +
      cacheReadRate    * BigDecimal(usage.cacheReadTokens) +
      cacheWriteRate   * BigDecimal(usage.cacheCreationTokens) +
      pricing.completion * BigDecimal(usage.completionTokens)
  }

  /** Default extraction system prompt for [[Sigil.initializeMemories]].
    * Apps that want a domain-specific extraction shape (e.g. medical
    * intake, onboarding survey) override the parameter directly. */
  val DefaultInitializationSystemPrompt: String =
    """You convert a list of declarative user statements into durable memories.
      |
      |For each statement, emit one memory with:
      |  - `key`: a stable, dot-separated identifier rooted at the user's identity
      |    (e.g. "user.first_name", "user.last_name", "user.email", "user.age",
      |    "user.timezone"). Same identity slot across statements MUST share a
      |    key so future updates can version it rather than duplicating.
      |  - `label`: a short human-readable name for the slot (e.g. "First name").
      |  - `content`: the canonical fact, self-contained and third-person.
      |    Convert "I'm 46 years old" into "User is 46 years old."
      |  - `tags`: optional retrieval tokens (e.g. ["identity", "name"]).
      |
      |One statement maps to one memory. Do not split, merge, or infer beyond
      |what the statements explicitly say.""".stripMargin

  /** JVM-wide registry of in-flight framework workflows, keyed by
    * workflow id. `runAsFrameworkWorkflow` puts on Start and
    * removes on Complete/Failed; `cancelFrameworkWorkflow` reads
    * from here. Concurrent so multiple turns racing on different
    * Sigil instances in the same JVM don't corrupt each other.
    * Bug #51. */
  private[sigil] val activeFrameworkWorkflows: java.util.concurrent.ConcurrentHashMap[String, ActiveFrameworkWorkflow] =
    new java.util.concurrent.ConcurrentHashMap()
}
