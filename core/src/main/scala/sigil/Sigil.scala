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

trait Sigil extends ProviderConfigStore with MemoryOps with ViewerStateOps with CheckpointOps with HealingOps with DirectiveOps with RoutingOps with DiscoveryOps with AgentLoopOps with TopicOps with ConversationOps with PublishOps {

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
   * The ordered system-prompt section list, and the one place the
   * layout is declared. `Provider.renderSystem` concatenates it,
   * [[sigil.diagnostics.RequestProfiler]] counts it, the curator runs
   * its `shed` effects in `shedStage` order, and `context_breakdown`
   * reports the profiler's numbers — so an app that adds, drops,
   * reorders, or re-budgets a section here moves every consumer at
   * once. Overriding on a single Provider would desync the renderer
   * from the shedder, which is why the hook lives on Sigil.
   *
   * Validated at [[instance]] boot: a section declaring a `shedStage`
   * without a `shed` effect fails startup.
   */
  def contextSections: List[ContextSection] = ContextSections.all

  /**
   * Resolve the ids on `TurnInput.criticalMemories` / `.memories` /
   * `.summaries` to full records. Ids that don't resolve are dropped
   * silently. The renderer, the wire profiler, and `context_breakdown`
   * all account against the records this returns.
   */
  private[sigil] final def resolveReferences(turn: sigil.conversation.TurnInput): Task[ResolvedReferences] = {
    // This is the LAST read before the bytes go on the wire, and the ids
    // it hydrates were selected at retrieval time — potentially several
    // iterations earlier. A memory revoked in between (rejected,
    // superseded by a newer version, expired) must not render, so the
    // shared recall gate applies here too rather than trusting the id list.
    val now = lightdb.time.Timestamp()
    val memTask: Task[(List[Option[ContextMemory]], List[Option[ContextMemory]])] =
      if (turn.criticalMemories.isEmpty && turn.memories.isEmpty) Task.pure((Nil, Nil))
      else withDB(_.memories.transaction { tx =>
        def recallable(loaded: List[Option[ContextMemory]]): List[Option[ContextMemory]] =
          loaded.map(_.filter(_.isRecallable(now)))
        for {
          crit    <- Task.sequence(turn.criticalMemories.toList.map(tx.get)).map(recallable)
          regular <- Task.sequence(turn.memories.toList.map(tx.get)).map(recallable)
        } yield (crit, regular)
      })
    val sumTask: Task[List[Option[ContextSummary]]] =
      if (turn.summaries.isEmpty) Task.pure(Nil)
      else withDB(_.summaries.transaction(tx => Task.sequence(turn.summaries.toList.map(tx.get))))
    for {
      (crit, regular) <- memTask
      summaries       <- sumTask
    } yield ResolvedReferences(
      criticalMemories = crit.flatten.toVector,
      memories         = regular.flatten.toVector,
      summaries        = summaries.flatten.toVector
    )
  }

  /**
   * Wall-clock budget (ms) a streaming provider call may spend with NO
   * lines arriving at all — no data, no keepalives, nothing — before
   * the wire layer's silence watchdog cancels the stream and raises a
   * typed transient [[sigil.provider.ProviderStreamException]]
   * (`errorType = upstream_silent`). The retry classifier promotes the
   * typed exception to `Retry` so the framework's transient-retry
   * wrapper re-attempts the call. Set to `0` to disable.
   *
   * Timer-enforced: a watchdog fiber fires within one poll tick of the
   * threshold, whether or not another line ever arrives. Keepalive /
   * comment lines are affirmative liveness — they RESET this clock and
   * never count toward it (a busy-but-alive upstream heartbeating
   * while queued is governed by [[streamingKeepaliveOnlyTimeoutMs]]
   * instead).
   *
   * Default `60_000` (60 seconds). Providers can override per wire
   * via `OpenAIChatCompletions.Config.streamingSilenceTimeoutMs` —
   * e.g. a local single-slot llama.cpp disables the watchdog and
   * relies on the HTTP client's byte-idle timeout, since a queued
   * request may legitimately carry no lines for minutes.
   */
  def streamingSilenceTimeoutMs: Long = 60000L

  /**
   * Sigil #258 — line-silence budget (ms) applied while a stream has
   * NOT yet produced any meaningful content: a "dead on arrival"
   * upstream that has sent nothing since the connection opened. A
   * dead upstream is obvious well before the full
   * [[streamingSilenceTimeoutMs]], so this shorter budget abandons it
   * fast and lets the framework's transient-retry path try a fresh
   * connection (OpenRouter frequently re-routes to a healthy upstream
   * on a retry). Once a stream has produced meaningful content — text,
   * a tool call, OR reasoning — the full `streamingSilenceTimeoutMs`
   * applies instead: a stall after committed work is not retried
   * aggressively.
   *
   * Like the full budget, this measures TRUE line-silence only —
   * keepalive lines reset it. A gateway heartbeating a dead backend
   * is caught by [[streamingKeepaliveOnlyTimeoutMs]].
   *
   * Default `20_000` (20 s). `0` disables the dead-on-arrival budget
   * (the full `streamingSilenceTimeoutMs` then applies throughout).
   * The master switch is `streamingSilenceTimeoutMs` — setting THAT
   * to `0` turns off silence detection entirely, this budget included.
   */
  def streamingDeadOnArrivalTimeoutMs: Long = 20000L

  /**
   * Wall-clock budget (ms) a streaming provider call may spend
   * emitting ONLY keepalive / comment lines — no data chunks — before
   * the wire layer raises a typed transient
   * [[sigil.provider.ProviderStreamException]] (`errorType =
   * upstream_silent`). Set to `0` to disable.
   *
   * Keepalives are affirmative liveness: the connection is up and the
   * server is choosing to heartbeat, which usually means the request
   * is queued or processing behind load — a busy single-slot llama.cpp
   * legitimately heartbeats for many minutes behind batch work, and
   * killing that stream fails a turn whose work would have succeeded.
   * The budget exists for the opposite case: a gateway heartbeating a
   * BACKEND that is dead, where keepalives flow forever and content
   * never comes. Hence the generous default.
   *
   * Timer-enforced by the silence watchdog (fires within one poll tick
   * of the budget whether or not another line ever arrives) and ALSO
   * checked lazily on each arriving keepalive. Data-chunk arrival
   * never trips this check — a stream that just became productive is
   * not killed for the wait that preceded it. Default `600_000`
   * (10 minutes); override per wire via
   * `OpenAIChatCompletions.Config.streamingKeepaliveOnlyTimeoutMs`.
   */
  def streamingKeepaliveOnlyTimeoutMs: Long = 600000L

  /**
   * Keepalive-only span (ms) after which the silence watchdog engages
   * [[sigil.provider.StreamStarvationRelief]] for providers that wire
   * it: the provider's stream-slot gate pauses NEW batch admissions so
   * the backend drains toward a free slot and its scheduler finally
   * serves the starved stream. Cleared as soon as the stream produces
   * meaningful content (or terminates). Set `0` to disable.
   *
   * This is the fix for admitted-stream starvation: the slot gate caps
   * on-wire streams at the backend's capacity, but a backend scheduler
   * can still leapfrog one admitted large request with a stream of
   * fresh small ones indefinitely — observed live as a 51-minute
   * keepalive-only agent stream behind a consult flood. Relief bounds
   * that to roughly this threshold plus one batch-item duration.
   *
   * Default `60_000` (60 seconds). Only meaningful on providers with
   * `gateStreamingCalls` enabled AND a relief wired on their
   * chat-completions Config (llama.cpp does both); a no-relief wire
   * ignores it.
   */
  def streamingKeepaliveReliefMs: Long = 60000L

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
   * The framework reads this override ONCE and memoizes the result
   * ([[resolvedStaticTools]]) — every consumer (registration, sync
   * upgrade, suggestion cascade) sees the same instances, so tools
   * that hold mutable state (e.g.
   * [[sigil.tool.process.ProcessRegistry]]) behave even when
   * constructed inline. Overrides that construct stateful values
   * inline still want them hoisted, so the shared instance is obvious
   * at the call site rather than incidental to the memoization:
   * {{{
   *   private lazy val processRegistry = new ProcessRegistry()
   *   override def staticTools: List[Tool] =
   *     super.staticTools ++ AllShippedTools(fs, MySpace, Some(processRegistry))
   * }}}
   */
  def staticTools: List[sigil.tool.Tool] = sigil.tool.core.CoreTools.all.toList

  private val staticToolsMemo =
    new java.util.concurrent.atomic.AtomicReference[Option[List[sigil.tool.Tool]]](None)

  /** Memoized first read of [[staticTools]] — the framework's single
    * access path to the static roster. */
  final def resolvedStaticTools: List[sigil.tool.Tool] = staticToolsMemo.get() match {
    case Some(list) => list
    case None =>
      val fresh = staticTools
      if (staticToolsMemo.compareAndSet(None, Some(fresh))) fresh
      else staticToolsMemo.get().get
  }

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
   * (driven by [[sigil.tool.Freshness.Volatile]] read declarations) — runs the cheap
   * cleanup pass and the budget guard so a single conversation can't
   * blow the model's context window with accumulated `find_capability`
   * / `change_mode` results.
   */
  def curate(conversationId: Id[Conversation],
             modelId: Id[Model],
             chain: List[ParticipantId]): Task[TurnInput] =
    sigil.conversation.compression.StandardContextCurator(this).curate(conversationId, modelId, chain)

  /**
   * Sigil #100 — re-fit an already-curated [[TurnInput]] to a served
   * model's context window. Used by the agent loop when per-turn
   * routing lands a turn on a model whose window is SMALLER than the
   * one the context was curated for (Opus 1M → Haiku 200K under
   * complexity-based model assignment, a tier degrade, or a credential
   * that doesn't grant the catalog window). Same reduction flow `curate`
   * ends with — it just sizes down to the smaller window — so it
   * inherits the curator's non-lossy-first cascade and never sheds
   * pinned memories. Identity when re-fitting isn't needed (served
   * window ≥ curated window); the caller only invokes it when smaller.
   */
  def refit(turnInput: TurnInput,
            modelId: Id[Model],
            chain: List[ParticipantId],
            capTokens: Option[Int] = None): Task[TurnInput] =
    sigil.conversation.compression.StandardContextCurator(this).refit(turnInput, modelId, chain, capTokens)

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
   * App-specific [[sigil.conversation.ConversationStatus]] subtypes
   * registered into the polymorphic discriminator so `Conversation.status`
   * round-trips through fabric RW (sigil #386). The framework's
   * [[sigil.conversation.ConversationStatus.Open]] default is registered
   * automatically; apps add their own concrete statuses (Saved, Completed,
   * Escalated, …) here.
   */
  protected def conversationStatusRegistrations: List[RW[? <: sigil.conversation.ConversationStatus]] = Nil

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
  private[sigil] final case class RoutingResolution(strategyOpt: Option[ProviderStrategy],
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
  private[sigil] final def resolveRouting(agent: AgentParticipant,
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

  /** Resolved per-turn dispatch inputs. `fallbackIds` is the ordered
    * cross-candidate fallback chain (#397) — the chosen model first, then the
    * tiers at or below it (down-only), ending at the agent's pinned model when
    * the strategy chain covers nothing. `candidateChain` carries each
    * candidate's per-model [[sigil.provider.GenerationSettings]] overlay. */
  private final case class ResolvedTurn(tools: Vector[Tool],
                                        fallbackIds: List[Id[Model]],
                                        candidateChain: List[sigil.provider.ModelCandidate],
                                        strategyOpt: Option[ProviderStrategy],
                                        routedWorkType: WorkType,
                                        roles: List[sigil.role.Role])

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

    val resolved: Task[ResolvedTurn] =
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
        // when every candidate is skipped (typically because an expected
        // provider is unavailable, e.g. an env-var unset took its
        // candidate out of the chain), `chosen` is None and dispatch
        // falls back to `agent.modelId`. RouteResolved records the skip
        // reasons but is a ControlPlaneEvent — it doesn't enter the
        // agent's ContextFrame projection, so the agent has no way to
        // read "the framework wanted to route higher but couldn't." The
        // observed failure mode is an infinite `change_mode` loop: the
        // agent calls `change_mode`, notices the model didn't change,
        // calls it again, and so on until the iteration cap fires.
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
        rawTools    <- Task.sequence(effectiveNames.map(n => resolveToolFor(context.conversation.id, n))).map(_.flatten.toVector)
        // Sigil #378 — `record_consent` is a no-op unless some tool in
        // scope sets `requiresUserConsent`. It's no longer in the default
        // roster (dropped from `CoreTools.all`); keep it only when a
        // consent-gated tool is actually present, so on apps with no
        // consent-gated tools it's never a dead-end attractor the model
        // loops on.
        withConsent  = Sigil.reconcileConsentTool(rawTools)
        // Filter out memory tools when the chain has no accessible
        // spaces — surfacing `save_memory` / `unpin_memory` /
        // `list_memories` to an agent that has nowhere to write
        // would just waste tokens on tool descriptions the agent
        // would fail to use.
        accessible  <- accessibleSpaces(effectiveChain, context.conversation.id)
        t            = if (accessible.isEmpty) withConsent.filterNot(_.requiresAccessibleSpaces)
                        else withConsent
        // Resolve the agent's roles for this turn. Static agents return
        // their declared `roles` field; DB-backed agents (e.g. apps
        // with persona records) consult persistence here. Empty result
        // is treated as a programmer error.
        rolesResolved <- agent.resolveRoles(context).map { rs =>
          require(rs.nonEmpty,
            s"AgentParticipant.resolveRoles must return a non-empty list (id=${agent.id.value})")
          rs
        }
        // #397 — the cross-candidate fallback chain: the chosen model first,
        // then every candidate supporting a tier at or below the inferred
        // complexity (down-only degrade, in chain order, deduped). When the
        // strategy chain covers nothing, fall back to the agent's pinned model
        // (single attempt — preserves pre-#397 behaviour). `genSettings` above
        // resolves the chosen candidate's overlay; later candidates resolve
        // theirs per-attempt below.
        fallbackIds = {
          val ordered = Complexity.atOrBelow(complexity).iterator.flatMap { tier =>
            candidateChain.filter(_.supportedComplexity.contains(tier)).map(_.modelId)
          }.toList.distinct
          if (ordered.nonEmpty) ordered else List(agent.modelId)
        }
      } yield ResolvedTurn(t, fallbackIds, candidateChain, strategyOpt, routedWorkType, rolesResolved)

    Stream.force(resolved.map { rt =>
      val tools          = rt.tools
      val rolesResolved  = rt.roles
      val candidateChain = rt.candidateChain
      // Per-candidate `GenerationSettings` overlay (the chain entry's, or the
      // agent's base when the id isn't a routed candidate — e.g. the pinned
      // fallback). #397 degrade keeps each tier's own settings.
      def settingsFor(modelId: Id[Model]): GenerationSettings =
        candidateChain.find(_.modelId == modelId).map(_.settings).getOrElse(agent.generationSettings)

      // One candidate's turn: resolve provider+model, build the request, run
      // the orchestrator. Wrapped by CandidateFallback so a pre-commit,
      // non-Fatal failure degrades to the next tier instead of killing the turn.
      def attempt(modelId: Id[Model]): Stream[Signal] = {
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
        val genSettings = settingsFor(modelId)
        // Sigil #412 — a per-conversation effort pin (set via `pin_effort`)
        // overlays the resolved candidate settings for the main agent turn.
        // Mirrors `pinnedModelId` / `pinnedComplexity`: it gives a consumer a
        // user-facing effort picker (Low / Medium / High / Max) without
        // touching the deployment-global ProviderStrategy candidate settings.
        // Reasoning is forced On so the effort actually engages on providers
        // whose default is thinking-off (Google 2.5) or `Auto`; the
        // forced-synthesis branch below overrides back to Off when it runs.
        // Scoped to the user-facing agent turn — auxiliary calls (classifier,
        // extractor, summarization) resolve their own settings elsewhere.
        val pinnedSettings = context.conversation.pinnedEffort match {
          case Some(effort) => genSettings.copy(effort = Some(effort), reasoningMode = ReasoningMode.On)
          case None         => genSettings
        }
        // forced-synthesis is the framework's last-resort "make the model
        // respond" turn. Tool-call already narrowed to the respond family
        // at the orchestrator boundary; here we ALSO bound the output
        // budget and force reasoning mode off. Reasoning-template local
        // models (qwen3.5-9b via llama.cpp, DeepSeek-R1 family) otherwise
        // burn the entire context window on `reasoning_content` and emit
        // zero `tool_calls` — observed 4-minute hangs that turn a
        // recoverable hiccup into a permanently failed turn.
        val effectiveSettings =
          if (context.forceResponseSynthesis)
            // Cap aggressively even when the caller didn't — forced-
            // synthesis is supposed to emit ONE respond call, ≤ a few
            // hundred tokens of content. `tightenedTo` preserves a
            // tighter caller-supplied cap (sigil #276).
            pinnedSettings.tightenedTo(2048).copy(
              // Hard override (not orElse) — the narrow tool_choice
              // means there's nothing worth reasoning about anyway.
              reasoningMode = ReasoningMode.Off
            )
          else pinnedSettings
        // Sigil #100 — when per-turn routing lands this candidate on a model
        // whose context window is SMALLER than the one the turn was curated
        // for (Opus 1M → Haiku 200K under complexity-based assignment, a tier
        // degrade, or a credential that doesn't grant the catalog window),
        // re-fit the curated TurnInput to the served window before shipping
        // it. Same non-lossy-first shed `curate` ends with, just sized down —
        // so an over-budget prompt is compacted (recoverable frame elision,
        // dropping only recoverable memories/info) instead of hard-400ing on
        // the wire or getting crudely emergency-shed by the provider gate. A
        // re-fit failure falls back to the un-fitted input (the provider
        // pre-flight gate is still the backstop).
        //
        // Sigil #413 — the context-overflow recovery re-runs the iteration
        // with `emergencyContextFactor` set after the provider hard-rejected
        // the request as over the window. That refit is UNCONDITIONAL and
        // uses an explicit cap of `contextLength × factor` — the wire told
        // us the estimator under-counted, so a budget derived from the same
        // estimate can't be trusted to shed enough.
        val emergencyCap: Option[Int] =
          context.emergencyContextFactor.map(f => math.max(1024, (resolvedModel.contextLength * f).toInt))
        val turnInputTask: Task[TurnInput] = emergencyCap match {
          case Some(cap) =>
            refit(context.turnInput, canonicalModelId, context.chain, capTokens = Some(cap))
              .handleError(_ => Task.pure(context.turnInput))
          case None if resolvedModel.contextLength < context.model.contextLength =>
            refit(context.turnInput, canonicalModelId, context.chain)
              .handleError(_ => Task.pure(context.turnInput))
          case None => Task.pure(context.turnInput)
        }

        Stream.force(turnInputTask.map { fittedTurnInput =>
          val request = ConversationRequest(
            conversationId = context.conversation.id,
            model = resolvedModel,
            instructions = agent.instructions,
            turnInput = fittedTurnInput,
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
            toolResultCacheRef = context.toolResultCacheRef,
            turnStartedAt = context.turnStartedAt,
            cancellation = context.cancellation
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

      // #397 — try the chosen candidate, degrading to the next routed tier on a
      // pre-commit, non-Fatal failure (e.g. an Anthropic `overloaded_error`
      // that exhausted the provider's same-candidate retries). The classifier
      // and cooldown recorder come from the active strategy; a single-candidate
      // chain (pinned model / no strategy) behaves exactly as before.
      val classifier = rt.strategyOpt.map(_.errorClassifier).getOrElse(sigil.provider.ErrorClassifier.Default)
      CandidateFallback.stream(
        candidates    = rt.fallbackIds,
        classifier    = classifier,
        reportFailure = id => rt.strategyOpt.foreach(_.reportFailure(id, rt.routedWorkType)),
        stopRequested = () => stopRequested(context.conversation.id, Some(agent.id))
      )(attempt)
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

  /** Space-scoped memory listing. By default only recallable records
    * are returned — the current version of each slot, `Approved`, and
    * unexpired (see [[ContextMemory.isRecallable]]). Pass
    * `recallableOnly = false` for administrative access to every row,
    * including superseded versions and pending / rejected records. */
  def findMemories(spaces: Set[SpaceId], recallableOnly: Boolean = true): Task[List[ContextMemory]] =
    if (spaces.isEmpty) Task.pure(Nil)
    else withDB(_.memories.transaction { tx =>
      import lightdb.filter.*
      tx.query
        .filter(m => spaces.map(s => m.spaceIdValue === s.value).reduce(_ || _))
        .toList
    }).map { rows =>
      if (recallableOnly) {
        val now = Timestamp()
        rows.filter(_.isRecallable(now))
      } else rows
    }

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
   * Recognises refusal language in an agent's `respond.content`.
   * When the detector fires AND no
   * `find_capability` call exists in the conversation tail since
   * the last user-authored Message, the orchestrator suppresses
   * the respond emission and substitutes a Tool-role `Failure`
   * the agent reads on its next iteration, prompting it to
   * actually consult the catalog before refusing.
   *
   * Default: [[sigil.provider.RefusalDetector.Default]] — a
   * conservative regex set. Apps where refusal is a valid
   * outcome (moderation flows, sandbox executors) override with
   * [[sigil.provider.RefusalDetector.Never]] or a custom
   * implementation.
   */
  def refusalDetector: sigil.provider.RefusalDetector =
    sigil.provider.RefusalDetector.Default

  /**
   * Sigil #410 — scrub provider-identifying text from an error before it is
   * surfaced to users or fed to the model. A raw provider error carries
   * vendor-identifying content — the backend's name and support URLs like
   * `help.openai.com` — which the framework otherwise inserts verbatim into the
   * agent-facing diagnostic (the model can then echo it) AND, on a
   * non-agent-routed failure, into the end user's own reply bubble. Apps that
   * present a provider-agnostic product have no other seam to prevent this: the
   * string originates in the provider's error, not a model response, so a
   * system prompt can't stop it.
   *
   * The default strips known LLM-vendor support URLs/domains (replacing them
   * with a neutral phrase) while preserving the rest of the diagnostic, so an
   * actionable message ("malformed args", "rate limited") still reaches the
   * agent. Apps wanting a FULLY provider-agnostic surface override this to
   * return a generic message (e.g. "the model provider returned an error").
   * The framework always logs the RAW error to `scribe` (server-side) so
   * debugging isn't lost; only the sanitized text becomes surfaced content.
   *
   * @param providerName the resolved provider key (e.g. `"openai"`)
   * @param raw          the provider's raw error string
   */
  def sanitizeProviderError(providerName: String, raw: String): String =
    Sigil.vendorSupportUrlPattern.replaceAllIn(raw, "the provider's support site").trim

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
   * Default
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
  private[sigil] final def updateServiceStatusCache(signal: Signal): Unit = signal match {
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
    modelResolver.resolve(modelId)
      // Sigil #374 — on an exact miss, rescue a registered model whose id
      // differs only by case / provider prefix / `.`-vs-`-` separators
      // (`claude-3-5-sonnet` ↔ `anthropic/claude-3.5-sonnet`) by resolving its
      // canonical registry id. Still throws when the model is genuinely absent,
      // so the #277 fail-loud contract holds for unregistered models.
      .orElse(cache.findTolerant(modelId).flatMap(m => modelResolver.resolve(m._id)))
      .getOrElse(
        throw new sigil.provider.UnregisteredModelException(modelId, cache.all.map(_._id))
      )


  /** In-flight atomic tool dispatches, keyed by invoke id. The
    * orchestrator registers each dispatch just before the tool body
    * starts executing; the entry is removed when the invoke's settling
    * [[ToolDelta]] flows through [[publish]]. On the happy path the
    * invoke's durable persist and its settle both ride the iteration's
    * stream drain — but a force-Stop CANCELS that drain mid-execution,
    * losing both, while the Active invoke was already eagerly broadcast
    * to wire subscribers (clients render a running chip). Entries still
    * present when the loop's stop path runs [[settleDanglingToolInvokes]]
    * are persisted + settled out-of-band there so no invoke is left
    * un-settleable. */
  private[sigil] final val inflightToolDispatches: ConcurrentHashMap[Id[Event], ToolInvoke] = new ConcurrentHashMap()

  /** Record an atomic tool dispatch whose execution is about to start.
    * Called by the orchestrator; paired with automatic removal when the
    * invoke's settling delta reaches [[publish]]. */
  final def registerInflightToolDispatch(invoke: ToolInvoke): Unit = {
    inflightToolDispatches.put(invoke._id, invoke)
    ()
  }

  /** Per-conversation ids of tool invokes whose SETTLED result has been
    * rendered into a prompt the model consumed — the agent loop marks
    * each iteration's frames after the response drain completes.
    * Consulted (via [[sigil.conversation.compression.TurnEventsContext.deliveredToolResults]])
    * by [[sigil.conversation.compression.CompactionInvariant.UndeliveredToolResults]]:
    * an active-turn invoke NOT in this set must survive every
    * compaction / shed / summary cover-set, or the agent never sees the
    * outcome of its own (possibly long-running) work and re-executes
    * it. In-memory; cleared when a fresh user Message starts the next
    * turn. A restart clears delivery state, which over-protects (keeps
    * more frames) for one turn — the safe direction. */
  private[sigil] final val deliveredToolResults: ConcurrentHashMap[Id[Conversation], java.util.Set[Id[Event]]] =
    new ConcurrentHashMap()

  /** Mark `ids` as delivered for `conversationId` — their settled
    * results appeared in a prompt whose response the model produced.
    * Called by the agent loop after each iteration's drain; public so
    * apps with custom turn shapes (`Sigil.process` overrides) can keep
    * the delivery tracking honest for their own prompt builds. */
  final def markToolResultsDelivered(conversationId: Id[Conversation], ids: Iterable[Id[Event]]): Unit =
    if (ids.nonEmpty) {
      val set = deliveredToolResults.computeIfAbsent(conversationId, _ => ConcurrentHashMap.newKeySet[Id[Event]]())
      ids.foreach(set.add)
    }

  /** Snapshot of the delivered-result ids for `conversationId`. */
  final def deliveredToolResultIds(conversationId: Id[Conversation]): Set[Id[Event]] =
    Option(deliveredToolResults.get(conversationId)) match {
      case Some(set) =>
        import scala.jdk.CollectionConverters.*
        set.asScala.toSet
      case None => Set.empty
    }

  // -- detached tool tasks --

  /** How long a [[sigil.tool.Tool.detachable]] tool may run attached
    * before the orchestrator promotes it to a DETACHED background
    * task: the invoke settles with a tracking handle, the turn
    * finishes normally, the work continues on its own fiber, and the
    * real result folds onto the original invoke followed by a
    * Tool-role continuation trigger when it lands. Sub-threshold
    * completions stay fully synchronous. `0` promotes a detachable
    * tool immediately. Non-detachable tools ignore this entirely. */
  def toolDetachThresholdMs: Long = 60000L

  // -- client-registered interaction tools --

  /** Registry of UI-registered interaction tools (see
    * [[sigil.tool.client.ClientToolSpec]]): the frontend registers
    * its screens / panels / actions on conversation load via
    * [[sigil.signal.RegisterClientTools]], they become discoverable
    * through `find_capability` and callable like any server tool, and
    * execution is observed by the UI on its signal stream. In-memory
    * and connection-scoped by design — a client tool is executable
    * only while the registering client is attached, so nothing
    * persists. */
  final lazy val clientTools: sigil.tool.client.ClientToolRegistry =
    new sigil.tool.client.ClientToolRegistry(this)

  /** Maximum client tools registrable per conversation (across all
    * sessions). Registrations past the cap are rejected with a
    * per-name reason in the [[sigil.signal.ClientToolsRegistered]]
    * ack. */
  def clientToolLimit: Int = 32

  /** Cap on a client tool's description length. Client-registered
    * descriptions are client-controlled text injected into the
    * agent's prompt — the cap bounds the injection surface (and the
    * token cost). */
  def clientToolDescriptionMaxChars: Int = 1024

  /** How long a round-trip client tool call (`expectsResult = true`)
    * waits for the UI's [[sigil.signal.ClientToolResult]] before
    * settling a recoverable Failure. UI interactions should answer in
    * user-interface time; a client that is busy, backgrounded, or
    * gone must not park the agent's turn indefinitely. */
  def clientToolResultTimeoutMs: Long = 15000L

  /** Vet a client-tool registration before it lands. `None` rejects
    * the tool (the reason surfaces in the registration ack); `Some`
    * admits — possibly rewritten (trimmed description, adjusted
    * keywords). The default admits everything that passed the
    * framework's own validation (name shape, description presence,
    * server-tool collision, [[clientToolLimit]]). Multi-tenant apps
    * that treat client-registered text as untrusted override this to
    * enforce their own policy. */
  def clientToolFilter(spec: sigil.tool.client.ClientToolSpec): Option[sigil.tool.client.ClientToolSpec] =
    Some(spec)

  /** How many times per user turn the orchestrator challenges a
    * naked-text terminal (a plain-prose `end_turn` with no tool call —
    * it carries no explicit continue-vs-yield decision) before
    * committing the prose as the terminal reply.
    *
    * A single challenge assumes the agent responds by ACTING; an agent
    * that responds by narrating again ("…Starting now.") would then
    * yield the turn with zero work done — the announce-then-stall
    * failure the challenge exists to break, guaranteed by the guard
    * itself. The second bare narration is stronger evidence of a
    * stall than the first, so the challenge re-fires with an
    * escalated directive up to this bound. Any tool call clears the
    * pattern naturally (the intercept only fires on zero-tool-call
    * completions); forced-synthesis and context-pressured turns
    * commit immediately as before. Default `2`. */
  def nakedTextChallengeLimit: Int = 2

  // -- spend budgets --

  /** Soft per-turn spend budget (USD). When a turn's accumulated
    * provider cost crosses it, the loop injects a one-shot check-in
    * directive at the next iteration boundary: summarize where you
    * are and ask the user — via `respond_options` — whether to
    * continue, and at what scope. The turn yields at a summary
    * instead of running the meter while the user is away; the
    * continuation is a fresh turn (fresh budget, fresh complexity
    * classification — the check-in is also the de-escalation point).
    * `None` (default) disables. Per-conversation override via
    * [[sigil.conversation.ConversationBudget]] / the `set_budget`
    * tool. Cost is a first-class failure dimension: every other
    * guard judges progress, and a correctly-progressing turn can
    * still be wrong purely on the bill. */
  def turnCostSoftBudget: Option[BigDecimal] = None

  /** Hard per-turn spend ceiling (USD). Crossing it forces terminal
    * synthesis — the agent wraps up honestly with a spend-and-state
    * report and the turn ends. A ceiling crossed means the soft
    * check-in was ignored or long-ago approved; the wrap-up is the
    * backstop, not the conversation. `None` (default) disables. */
  def turnCostHardCeiling: Option[BigDecimal] = None

  /** Soft whole-conversation spend budget (USD) — same check-in
    * semantics as [[turnCostSoftBudget]], fired when
    * [[sigil.conversation.Conversation.cost]] crosses the threshold
    * during a turn. `None` (default) disables. */
  def conversationCostSoftBudget: Option[BigDecimal] = None

  /** Hard whole-conversation spend ceiling (USD). Crossing it
    * mid-turn forces terminal synthesis; a conversation already past
    * it refuses to START new turns (a user-visible message points at
    * `set_budget`) until the budget is raised. `None` (default)
    * disables. */
  def conversationCostHardCeiling: Option[BigDecimal] = None

  /** Token budget for the prompt's Summaries section — the same
    * governance contract Frames has (budget + elision), applied to
    * persisted [[sigil.conversation.ContextSummary]] records. The
    * curator keeps the newest summaries within this budget (always at
    * least one); older ones drop from the prompt while their content
    * stays durable and reachable via search / `reload_content`.
    * Without a bound, one long turn's rolling compaction stream grew
    * the section 1.7K → 22K tokens (40% of late context) and, because
    * it rendered ahead of the message history, re-cached the whole
    * prompt at creation rates every iteration. */
  def summariesTokenBudget: Int = 4096

  /** Effective budgets for `conv` — the conversation's override
    * field-wise, falling back to the app hooks. */
  final def effectiveBudgetsFor(conv: Conversation): sigil.conversation.ConversationBudget = {
    val o = conv.budget.getOrElse(sigil.conversation.ConversationBudget())
    sigil.conversation.ConversationBudget(
      turnSoft         = o.turnSoft.orElse(turnCostSoftBudget),
      turnHard         = o.turnHard.orElse(turnCostHardCeiling),
      conversationSoft = o.conversationSoft.orElse(conversationCostSoftBudget),
      conversationHard = o.conversationHard.orElse(conversationCostHardCeiling)
    )
  }

  /** Live detachable-tool executions, keyed by invoke id (which
    * doubles as the task handle). Registered at DISPATCH — so a Stop
    * reaches the execution's [[CancellationToken]] in the attached
    * phase too — and promoted in place at detach. In-memory: the
    * durable marker is the invoke row's `detached` flag, which
    * [[reconcileLostDetachedTools]] compares against this registry to
    * settle tasks whose fiber died with the process. */
  private[sigil] final val detachedToolTasks: ConcurrentHashMap[Id[Event], sigil.tool.DetachedToolTask] =
    new ConcurrentHashMap()

  private[sigil] final def registerDetachableDispatch(task: sigil.tool.DetachedToolTask): Unit = {
    detachedToolTasks.put(task.invokeId, task)
    ()
  }

  private[sigil] final def markToolDetached(invokeId: Id[Event]): Unit = {
    detachedToolTasks.computeIfPresent(
      invokeId,
      (_, t) => t.copy(detachedAt = Some(lightdb.time.Timestamp(lightdb.util.Nowish())))
    )
    ()
  }

  private[sigil] final def completeDetachedTool(invokeId: Id[Event]): Unit = {
    detachedToolTasks.remove(invokeId)
    ()
  }

  /** Detached tasks currently running for `conversationId`, projected
    * for the "what's running?" panel. Attached-phase registrations
    * (not yet promoted) are excluded — those are ordinary in-turn tool
    * calls. `WorkflowSigil.activeTasksFor` unions these with workflow
    * runs so detached sweeps appear beside `delegate_task` workers. */
  final def detachedToolTasksFor(conversationId: Id[Conversation]): List[sigil.conversation.ConversationTask] = {
    import scala.jdk.CollectionConverters.*
    detachedToolTasks.values.asScala.iterator
      .filter(t => t.conversationId == conversationId && t.detachedAt.isDefined)
      .map(sigil.conversation.ConversationTask.fromDetachedTool)
      .toList
  }

  /** Settle detached invokes whose background task no longer exists —
    * the process restarted (or the fiber was lost) between detach and
    * completion. Runs once per agent claim: without it, a lost task's
    * invoke reads "running as task X" forever and the continuation the
    * agent is waiting on never comes. Best-effort. */
  private[sigil] final def reconcileLostDetachedTools(conversationId: Id[Conversation]): Task[Unit] =
    withDB(_.eventsTransaction(conversationId)(_.list)).flatMap { events =>
      val lost = events.collect {
        case ti: ToolInvoke
          if ti.detached
            && ti.state == EventState.Complete
            && ti.outcome == sigil.event.ToolOutcome.Pending
            && !detachedToolTasks.containsKey(ti._id) =>
          ti
      }
      lost.foldLeft(Task.unit) { (acc, ti) =>
        val reason = s"Detached tool `${ti.toolName.value}` was lost to a process restart before completing. " +
          "Its partial work may be on disk; re-issue the tool if you still need the result."
        val settle = ToolDelta(
          target         = ti._id,
          conversationId = conversationId,
          state          = Some(EventState.Complete),
          summary        = Some(reason),
          outcome        = Some(sigil.event.ToolOutcome.Failure(reason, recoverable = true))
        )
        acc.flatMap(_ => publish(settle).handleError(_ => Task.unit))
      }
    }.handleError(_ => Task.unit)

  /** If this signal settles a [[ModeChange]] to `Complete`, resolve the
    * Mode-source [[ActiveSkillSlot]] (via [[sigil.provider.Mode.skill]]) and write it into
    * the acting participant's projection on the view. */
  private[sigil] final def maybeApplyModeSkill(signal: Signal): Task[Unit] = signal match {
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
  private[sigil] final def updateView(signal: Signal): Task[Unit] = signal match {
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
          resolvedStaticTools.find(_.name == ti.toolName).map(_.suggestedNextTools).getOrElse(Nil)
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
        // #371 — a recoverable Failure marks a tooling-seam result. The
        // duplicate-call cap reads this to avoid escalating a tier on a loop of
        // identical FAILING calls (a stronger model hits the same failure).
        val failed = ti.outcome match {
          case _: sigil.event.ToolOutcome.Failure => true
          case _                                  => false
        }
        val invocation = ti.input match {
          case Some(in) => sigil.conversation.RecentToolInvocation(
            toolName    = ti.toolName,
            argsHash    = sigil.tool.ToolInputCanonicalizer.argsHash(in),
            argsPreview = sigil.tool.ToolInputCanonicalizer.argsPreview(in),
            invokedAt   = ti.timestamp,
            resulted    = resulted,
            failed      = failed
          )
          case None => sigil.conversation.RecentToolInvocation(
            toolName    = ti.toolName,
            argsHash    = "",
            argsPreview = "",
            invokedAt   = ti.timestamp,
            resulted    = resulted,
            failed      = failed
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
        // the per-loop `find_capability` cache is no longer persisted;
        // `FindCapabilityTool.executeResult` records matches directly
        // onto `TurnContext.discoveredCapabilities` so the cache dies
        // with the agent loop instead of polluting every subsequent turn.
        // The projection update here keeps the `suggestedTools` overlay
        // only — that's a single-turn decay surface that drives the
        // "Suggested tools" prompt section.
        updateProjection(cr.conversationId, cr.participantId) { proj =>
          val toolNames = cr.matches.collect {
            case m if m.capabilityType == sigil.tool.discovery.CapabilityType.Tool => sigil.tool.ToolName.internal(m.name)
          }
          // Sigil #377 / #383 — ACCUMULATE, never replace. The #301 "bounded
          // replace" caused a discovered tool the agent was using (#377) or
          // about to use (#383, create_workflow) to drop out of the roster once
          // a later `find_capability` returned a different match set — and once
          // the per-loop `discoveredCapabilities` cache clears (terminal
          // respond, #6306) this overlay is the only carrier. A discovered
          // capability must stay dispatchable for the rest of the conversation,
          // not just the one search. Bounded by `distinct` + the finite catalog
          // + the curator's own context-limit shedding of the tool roster.
          proj.copy(suggestedTools = (proj.suggestedTools ++ toolNames).distinct)
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
    * conversation-query tools (`search_conversation`, `reload_content`)
    * call this before dispatching a read against
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
    * decision yet. The orchestrator reads this before dispatching a
    * `requiresUserConsent` tool; apps can also call directly to
    * surface "is this tool approved in this conversation?" UX. */
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
      withDB(_.eventsTransaction(conversationId) { tx =>
        tx.list.flatMap { all =>
          val events = all.iterator
            .filter(_.conversationId == conversationId)
            .filter(_.timestamp.value > watermark)
            .filter(_.state == EventState.Complete)
            .toVector
            .sortBy(_.timestamp.value)
          // Self-heal fossilized placeholders: a ToolCall frame rendered
          // while the invoke's outcome was still Pending (`resultPending`)
          // whose row has SINCE settled means the settle-time rewrite was
          // missed — recompute from the row (pure) and persist, so the
          // "result raced past the prompt" marker can never outlive the
          // real result it stood in for.
          val heals = Vector.newBuilder[Event]
          val frames = events.flatMap { ev =>
            ev.contextFrame match {
              case Some(tc: ContextFrame.ToolCall) if tc.resultPending =>
                ev match {
                  case ti: ToolInvoke if ti.outcome != sigil.event.ToolOutcome.Pending =>
                    val fresh = FrameBuilder.computeFrame(ti)
                    heals += ti.withContextFrame(fresh)
                    fresh
                  case _ => ev.contextFrame
                }
              case other => other
            }
          }
          val persistHeals = heals.result().foldLeft(Task.unit) { (acc, ev) =>
            acc.flatMap(_ => tx.upsert(ev).unit)
          }.handleError(_ => Task.unit)
          persistHeals.map(_ => frames)
        }
      })
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
                maxTimestamp: Option[Timestamp] = None,
                /** When set, the page is scoped to what this viewer may
                  * see: events failing [[canSee]] are dropped BEFORE
                  * pagination (so `maxMessages` counts visible messages)
                  * and [[viewerTransforms]] redact the survivors —
                  * identical to the live wire's `signalsFor` semantics.
                  * `None` returns the raw log: framework internals
                  * (compaction, reconciliation, agent prompt builds)
                  * must see everything. ANY path that delivers history
                  * to a client passes the viewer — an Agents-visibility
                  * checkpoint directive reaching a user UI on a hard
                  * refresh is an information-scope leak, not a
                  * cosmetic one. */
                viewer: Option[ParticipantId] = None): Task[EventsPage] = {
    import lightdb.filter.*
    val safePage = math.max(0, page)

    // In-memory filters applied identically to the committed DB rows
    // and to the batched-scope accumulator so both halves of a page-0
    // merge are narrowed the same way.
    def passesFilters(e: Event): Boolean =
      (topicId.forall(t => e.topicId == t) &&
        minTimestamp.forall(min => e.timestamp.value > min.value) &&
        maxTimestamp.forall(max => e.timestamp.value < max.value)) &&
        viewer.forall(v => canSee(e, v))

    def redactFor(e: Event): Event = viewer match {
      case Some(v) => applyViewerTransforms(e, v) match {
        case ev: Event => ev
        case _         => e
      }
      case None => e
    }

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

      eventsForPage(mergedDesc.map(redactFor), safePage, maxMessages)
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
      // before the task still sheds (recoverable via search_conversation,
      // or by reading the overflow file a bounded result named).
      // Explicit conversation-clear sets `clearedAt`
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

  /** Invalidate every conversation's cached retrieval. Fired by the
    * memory write paths that can change what is recallable — reject /
    * approve, forget, a keyed upsert that archives a prior version,
    * an in-place mutation (pin / unpin / move), and consolidation
    * merges. A `ContextMemory` carries no conversation mapping the
    * cache could invert, so the write bumps a global epoch instead;
    * every conversation recomputes its retrieval on the next turn.
    * O(1) — a single atomic increment. */
  def invalidateAllMemoryRetrievals(): Unit =
    memoryRetrievalCache.invalidateAll()

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
    * memory is already approved. An approval re-indexes the record so
    * semantic search sees it again, and drops every cached retrieval
    * so the memory can surface on the next turn. */
  def approveMemory(id: Id[ContextMemory]): Task[Option[ContextMemory]] =
    withDB(_.memories.transaction { tx =>
      tx.get(id).flatMap {
        case None => Task.pure(None)
        case Some(m) if m.status == MemoryStatus.Approved => Task.pure(Some(m))
        case Some(m) =>
          val updated = m.copy(status = MemoryStatus.Approved, modified = Timestamp())
          tx.upsert(updated).map(_ => Some(updated))
      }
    }).flatMap {
      case Some(updated) => reindexMemory(updated).map { indexed =>
        invalidateAllMemoryRetrievals()
        Some(indexed)
      }
      case None => Task.pure(None)
    }

  /** Transition a memory to `Rejected` (kept on disk for lineage, but
    * hidden from retrievers). Removes the record's vector point so a
    * semantic search can't resurrect it, and drops every cached
    * retrieval so a mid-burst rejection takes effect on the next turn.
    * Use [[forgetMemory]] for hard delete. */
  def rejectMemory(id: Id[ContextMemory]): Task[Option[ContextMemory]] =
    withDB(_.memories.transaction { tx =>
      tx.get(id).flatMap {
        case None => Task.pure(None)
        case Some(m) =>
          val updated = m.copy(status = MemoryStatus.Rejected, modified = Timestamp())
          tx.upsert(updated).map(_ => Some(updated))
      }
    }).flatMap {
      case Some(updated) => evictMemoryPoint(updated._id).map { _ =>
        invalidateAllMemoryRetrievals()
        Some(updated)
      }
      case None => Task.pure(None)
    }

  /** Hard-delete every version of a keyed memory in `spaceId`. Returns
    * the number of records removed. Also removes corresponding points
    * from the vector index so semantic search doesn't return stale
    * hits, and drops every cached retrieval so the forgotten memory
    * stops rendering on the next turn. */
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
      }.map { removed =>
        if (removed > 0) invalidateAllMemoryRetrievals()
        removed
      }
    }

  /** Pending `accessCount` / `lastAccessedAt` bumps, accumulated in
    * memory and drained by [[flushMemoryAccesses]]. Retrieval marks
    * access on every fresh compute; writing that straight through
    * would cost a store commit (and, on the default Lucene backend, an
    * fsync) per turn purely for a ranking signal. */
  private val pendingMemoryAccesses: java.util.concurrent.ConcurrentHashMap[Id[ContextMemory], (Int, Long)] =
    new java.util.concurrent.ConcurrentHashMap[Id[ContextMemory], (Int, Long)]()

  /** Bump `accessCount` and `lastAccessedAt` on a memory. Called by
    * retrieval paths (`semantic_search`, MemoryRetriever) so apps can
    * implement LRU-based retention without Sigil needing its own
    * pruner. The bump accumulates in memory and lands on the next
    * [[flushMemoryAccesses]] — see [[recordMemoryAccesses]] for the
    * durability contract. */
  def recordMemoryAccess(id: Id[ContextMemory]): Task[Unit] =
    recordMemoryAccesses(List(id))

  /**
   * Batched [[recordMemoryAccess]] — accumulates the bumps in memory
   * rather than opening a write transaction per retrieval.
   *
   * `accessCount` / `lastAccessedAt` are a retrieval-ranking feedback
   * signal, not conversation state: a store commit per fresh
   * retrieval bought durability nobody needs at the cost of an fsync
   * on the hot path, and the read-modify-write of whole documents
   * raced every concurrent memory mutation (a consolidation archive
   * or a reject landing between the read and the write was silently
   * reverted by the stale snapshot).
   *
   * Accumulated bumps are drained by [[flushMemoryAccesses]] — on the
   * [[sigil.maintenance.MemoryAccessFlushTask]] cadence
   * ([[memoryAccessFlushInterval]], default 60s) and once more during
   * [[shutdown]]. The flush applies each delta with `tx.modify`, so
   * the mutation lands on the FRESH row and touches only the two
   * access fields. A process killed between flushes loses at most one
   * interval's worth of access counts; the memories themselves are
   * unaffected.
   */
  def recordMemoryAccesses(ids: Seq[Id[ContextMemory]]): Task[Unit] =
    if (ids.isEmpty) Task.unit
    else Task {
      val now = Timestamp().value
      ids.distinct.foreach { id =>
        pendingMemoryAccesses.merge(id, (1, now), (a, b) => (a._1 + b._1, math.max(a._2, b._2)))
      }
    }

  /** How often [[sigil.maintenance.MemoryAccessFlushTask]] drains the
    * accumulated `accessCount` / `lastAccessedAt` bumps to the store.
    * Shorter means fresher reinforcement signal at the cost of more
    * commits; longer means a crash loses more counts. Default 60s. */
  def memoryAccessFlushInterval: scala.concurrent.duration.FiniteDuration =
    scala.concurrent.duration.DurationInt(60).seconds

  /** Drain the accumulated access bumps into the store. Returns the
    * number of memories updated. Each delta is applied with
    * `tx.modify` against the current row and touches only
    * `accessCount` / `lastAccessedAt`, so a concurrent archive,
    * reject, or re-scope is never clobbered. Missing ids are dropped.
    * Failures are logged and swallowed — access marking is a feedback
    * signal, never worth failing anything over. */
  def flushMemoryAccesses: Task[Int] = Task.defer {
    val drained = {
      val builder = List.newBuilder[(Id[ContextMemory], (Int, Long))]
      pendingMemoryAccesses.keySet().forEach { id =>
        Option(pendingMemoryAccesses.remove(id)).foreach(delta => builder += (id -> delta))
      }
      builder.result()
    }
    if (drained.isEmpty) Task.pure(0)
    else withDB(_.memories.transaction { tx =>
      Task.sequence(drained.map { case (id, (count, at)) =>
        tx.modify(id) {
          case Some(m) => Task.pure(Some(m.copy(
            accessCount = m.accessCount + count,
            lastAccessedAt = Timestamp(math.max(m.lastAccessedAt.value, at))
          )))
          case None => Task.pure(None)
        }.map(_.fold(0)(_ => 1))
      }).map(_.sum)
    }).handleError { e =>
      Task {
        scribe.warn(s"flushMemoryAccesses failed for ${drained.size} memories: ${e.getMessage}")
        0
      }
    }
  }

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
   * the query and hit the vector index with a `kind=memory` filter
   * plus a `spaceId` any-of clause — the space scope applies inside
   * the index's top-K cut, so a large multi-tenant store can't crowd
   * in-space matches out of the candidate pool — then hydrate ids via
   * [[SigilDB.memories]]. Only recallable records are returned (see
   * [[ContextMemory.isRecallable]]). When not wired, fall back to the
   * space-scoped listing (relevance-unordered — callers that care
   * should override this method).
   */
  def searchMemories(query: String,
                     spaces: Set[SpaceId],
                     limit: Int = 10): Task[List[ContextMemory]] =
    if (!vectorWired) findMemories(spaces).map(_.take(limit))
    else embeddingProvider.embed(query).flatMap { vec =>
      val filter = sigil.vector.VectorQueryFilter(
        exact = Map("kind" -> "memory"),
        anyOf = if (spaces.isEmpty) Map.empty else Map("spaceId" -> spaces.map(_.value))
      )
      vectorIndex.search(vec, limit = limit, filter = filter).flatMap { hits =>
        val ids = hits.flatMap(_.payload.get("memoryId")).map(Id[ContextMemory](_))
        withDB { db =>
          db.memories.transaction { tx =>
            Task.sequence(ids.map(id => tx.get(id))).map { loaded =>
              val now = Timestamp()
              loaded.flatten
                .filter(m => spaces.isEmpty || spaces.contains(m.spaceId))
                .filter(_.isRecallable(now))
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
  private[sigil] final def updateConversationProjection(signal: Signal): Task[Unit] = {
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
        // The pin/unpin tools mutate `pinnedComplexity` themselves;
        // this projection arm keeps the event the source of truth so
        // future emitters (e.g. classifier-driven auto-escalation)
        // flow through the same path without duplicating the
        // conversation modify.
        withDB(_.conversations.transaction(_.modify(cc.conversationId) {
          case Some(conv) if conv.pinnedComplexity != cc.newTier =>
            Task.pure(Some(conv.copy(pinnedComplexity = cc.newTier, modified = Timestamp(Nowish()))))
          case Some(conv) => Task.pure(Some(conv))
          case None       => Task.pure(None)
        })).unit
      case Some(tc: TopicChange) =>
        applyTopicChangeToStack(tc)
      case Some(m: Message) =>
        applyEventCostToConversation(m.conversationId, m._id, m.participantId, m.modelId, m.usage)
      case Some(t: ToolInvoke) =>
        // For tool-call-only turns (change_mode, cancel, find_capability,
        // …) the per-turn usage attaches to the ToolInvoke via
        // [[sigil.signal.ToolDelta]]. Cost projection picks it up off
        // the same `modelId × usage` pair that drives the Message path.
        applyEventCostToConversation(t.conversationId, t._id, t.participantId, t.modelId, t.usage)
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
  /**
   * Sigil #406 — per-turn cost telemetry seam. Called once per charged turn at
   * the point the framework folds the turn's USD `cost` into
   * `Conversation.cost`, for BOTH `Message` and tool-call-only `ToolInvoke`
   * turns. The framework has already resolved the model, computed the delta
   * cost from `Model.pricing` × `usage`, and knows the acting participant and
   * active mode — [[TurnCost]] bundles them so a consumer can persist its own
   * cost-ledger row (itemized by model · mode · participant, project-to-date)
   * without reconstructing any of it or patching the cost pipeline.
   *
   * Default no-op. Runs best-effort — a consumer's failure here is logged and
   * does not break the cost projection. The transient
   * [[sigil.signal.ConversationCostUpdated]] Notice still fires as the live
   * "cost changed" trigger; this hook is the DURABLE seam (the Notice isn't
   * persisted / replayed).
   */
  def onTurnCost(entry: TurnCost): Task[Unit] = Task.unit

  private final def applyEventCostToConversation(
    conversationId: Id[Conversation],
    eventId: Id[Event],
    participantId: ParticipantId,
    modelId: Option[Id[Model]],
    usage: TokenUsage
  ): Task[Unit] = {
    // Bug #91 — `findTolerant` lets an event stamped with a bare id
    // (`gpt-5.5`) match a registry entry indexed by its prefixed id
    // (`openai/gpt-5.5`). Without it, every cost projection on a
    // bare-id event silently misses and the conversation's running
    // total stays at zero.
    // Cache-thrash canary. A healthy multi-turn conversation reads its cached
    // prefix far more than it writes it; sustained cache_creation ≫ cache_read
    // means the cached prefix is unstable across turns (e.g. a breakpoint that
    // moves every turn, or churning content early in the request) and the whole
    // history is being re-created — the dominant cost/latency failure mode.
    // Warn loudly so a regression surfaces in the logs instead of only in a
    // monthly bill. Thresholded so normal first-turn cache fills stay quiet.
    if (usage.cacheCreationTokens > 50000 && usage.cacheCreationTokens > usage.cacheReadTokens * 4)
      scribe.warn(s"[cache-thrash] cache_creation=${usage.cacheCreationTokens} ≫ " +
        s"cache_read=${usage.cacheReadTokens} (model=${modelId.map(_.value).getOrElse("?")}); " +
        "the cached prefix looks unstable across turns — prompt caching is not amortizing.")
    // Capture the resolved model id alongside the delta so the #406 telemetry
    // reports what the turn actually ran on (registry-canonical when known).
    val chargeOpt: Option[(Id[Model], BigDecimal)] = modelId.flatMap { mid =>
      cache.findTolerant(mid).map(model => cache.canonicalIdFor(mid) -> Sigil.costFor(model.pricing, usage))
    }.filter(_._2 > 0)
    chargeOpt match {
      case None => Task.unit
      case Some((resolvedModelId, delta)) =>
        // Mirror the charge onto the live turn's spend accumulator so
        // the budget gate reads per-turn cost without a store query.
        // Cost-bearing events carry the acting agent's participantId,
        // which keys the claim registry.
        participantId match {
          case agentId: sigil.participant.AgentParticipantId =>
            Option(activeClaims.get(agentStateLockId(agentId, conversationId))).foreach(_.addTurnCost(delta))
          case _ => ()
        }
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
            )).flatMap { _ =>
              // Sigil #406 — hand the fully-attributed turn cost to the app's
              // ledger seam. Best-effort: a consumer hiccup mustn't break the
              // cost projection.
              onTurnCost(TurnCost(
                conversationId = updated._id,
                eventId        = eventId,
                participantId  = participantId,
                modelId        = resolvedModelId,
                mode           = updated.currentMode.name,
                cost           = delta,
                usage          = usage,
                timestamp      = Timestamp(Nowish())
              )).handleError(t => Task(scribe.warn(s"onTurnCost hook failed for ${updated._id.value}: ${t.getMessage}")))
            }
          case None => Task.unit
        }
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
    * 8 — the checkpoint is a NON-TERMINAL reflection nudge (sigil #379:
    * it lets the agent continue rather than forcing a respond), so it's
    * cheap to self-evaluate earlier; the second consecutive no-progress
    * checkpoint (~16 iterations) carries the prior status so the agent
    * is forced to evaluate while still allowed to move forward. Set to 0
    * to disable checkpointing. */
  protected def progressCheckpointInterval: Int = 8

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

  /** Sigil #385 — number of consecutive `meaningfulProgress = false`
    * checkpoints after which the framework escalates from a COOPERATIVE
    * nudge to a TERMINAL forced synthesis. The cooperative checkpoint
    * (at [[consecutiveNoProgressLimit]]) only asks the agent to change
    * approach and lets it continue — correct for a brief plateau, but a
    * model that ignores nudge after nudge (observed live: 6 consecutive
    * "no progress" checkpoints over ~50 iterations while reading 40
    * distinct files without acting) needs to be stopped, not nudged
    * again. When the streak reaches this limit the next intervention is
    * `terminal` (forces a `respond` synthesis from what's gathered).
    * Distinct from [[hardStallIdenticalCallLimit]], which only catches
    * BYTE-IDENTICAL repeats; this catches a VARIED-but-unproductive loop
    * that evades the identical-call detector. Must be `>
    * consecutiveNoProgressLimit` so the cooperative nudge fires first.
    * 0 disables (cooperative nudges only). Default 4. */
  protected def hardNoProgressLimit: Int = 4

  /** Sigil #385 — whether a no-progress streak has persisted long enough
    * that the cooperative checkpoint must escalate to a TERMINAL forced
    * synthesis instead of nudging again. Pure over [[hardNoProgressLimit]];
    * extracted as a seam so the escalation boundary is deterministically
    * testable without driving a live reflection loop. */
  private[sigil] def terminalOnPersistentNoProgress(noProgressStreak: Int): Boolean =
    hardNoProgressLimit > 0 && noProgressStreak >= hardNoProgressLimit

  /** Oversight tier for the progress checkpoint. When set, the
    * checkpoint's LLM step consults THIS model as a planner holding an
    * explicit [[TurnPlan]] instead of asking the executor's tier to
    * assess itself — the executor losing the plot is invisible from
    * inside its own loop, and a self-assessment that latches "task
    * completed" can stall-kill a healthy repair turn. The planner call
    * is resolved directly to this id (no work-type routing — the point
    * is an explicit stronger tier). `None` (default) disables the
    * planner entirely; checkpoint behavior is unchanged. */
  protected def plannerModelId: Option[Id[Model]] = None

  /** Iterations between routine planner reviews when [[plannerModelId]]
    * is set. The planner consult is sparse by design: it fires on
    * anomaly signals (stall heuristics, same-target churn, budget
    * check-in), on the first checkpoint of a turn (to create the plan),
    * and otherwise only every this-many iterations. 0 disables the
    * periodic tick — anomaly- and first-plan-driven only. */
  protected def plannerCadence: Int = 24

  /** What a model is behaviorally capable of. Defaults to
    * [[ModelProfile.heuristic]] — an advertised parameter count or a
    * known frontier family, nothing else; every unrecognized model
    * keeps the frontier-tier default and behaves exactly as before.
    * Apps that know their fleet override to declare it outright — the
    * checkpoint cadence, planner arming, discovery roster ceiling,
    * refusal verbosity, and prompt shape all read the result. */
  def modelProfileFor(model: Model): ModelProfile = ModelProfile.heuristic(model)

  /** The profile for a model id, falling back to the frontier default
    * when the id isn't registered. */
  final def modelProfileForId(modelId: Id[Model]): ModelProfile =
    cache.find(modelId).map(modelProfileFor).getOrElse(
      ModelProfile(InstructionTier.Frontier, Reliability.Solid, Int.MaxValue,
        needsOversight = false, promptShape = PromptShape.Full))

  /** [[progressCheckpointInterval]] tightened for the running model's
    * instruction tier. Frontier / Capable models run the configured
    * cadence unchanged; weaker tiers are reviewed proportionally more
    * often, floored at every other iteration. Disabled (0) stays
    * disabled. */
  final def effectiveProgressCheckpointInterval(modelId: Id[Model]): Int = {
    val configured = progressCheckpointInterval
    if (configured <= 0) configured
    else math.max(2, configured / modelProfileForId(modelId).instructionTier.cadenceTightening)
  }

  /** [[plannerCadence]] tightened the same way. */
  final def effectivePlannerCadence(modelId: Id[Model]): Int = {
    val configured = plannerCadence
    if (configured <= 0) configured
    else math.max(2, configured / modelProfileForId(modelId).instructionTier.cadenceTightening)
  }

  /** Sigil #413 — how many context-overflow recoveries a single turn may
    * spend before the overflow surfaces as a clean terminal failure. Each
    * recovery re-runs the failed iteration with an emergency refit to
    * `contextLength × 0.5^attempt` — the halving absorbs any estimator
    * under-count, so two attempts reach a quarter of the window before
    * giving up. 0 disables the recovery (overflow fails the turn
    * immediately, pre-#413 behaviour). */
  protected def maxOverflowCompactions: Int = 2

  /** Sigil #416 — per-conversation count of consecutive curates whose
    * stage-2c frame elision had to fire. Written by
    * [[sigil.conversation.compression.StandardContextCurator.budgetResolve]];
    * when the streak reaches [[elisionPressureEscalationStreak]] the
    * cascade escalates into stage 3's DURABLE shed (summary + clearedAt
    * advance) so chronically-pressured history shrinks for good instead
    * of being ephemerally re-elided on every turn forever. In-memory —
    * a restart resets streaks, which just delays escalation by a few
    * curates. */
  private[sigil] final val elisionPressureStreaks: ConcurrentHashMap[Id[Conversation], java.lang.Integer] =
    new ConcurrentHashMap()

  /** Sigil #416 — consecutive eliding curates after which the shed
    * cascade escalates to the durable stage-3 path even when elision
    * alone would fit. 0 (or negative) disables escalation. */
  protected[sigil] def elisionPressureEscalationStreak: Int = 3

  /** Objective identical-call streak (same tool, same args, within one turn)
    * at which the framework force-ends the turn — MODEL-INDEPENDENTLY — by
    * triggering the forced-synthesis recovery early. Every cooperative stall
    * guard ([[maxIdenticalToolCallsInWindow]], the repeated-query intercept,
    * the progress checkpoint) only NUDGES the model to stop; a model that
    * ignores them all keeps re-emitting the same call until
    * [[maxAgentIterations]] and throws [[AgentRunawayException]]. This is the
    * backstop that detects the pathological repeat and ends the turn in a
    * handful of iterations instead. Higher than the duplicate-call cap and
    * the checkpoint stall threshold so the cooperative nudges get their
    * chance first. 0 disables. */
  protected def hardStallIdenticalCallLimit: Int = 6

  private lazy val budgetGovernor: BudgetGovernor = new BudgetGovernor(this)
  private lazy val progressGovernor: ProgressGovernor = new ProgressGovernor(this)

  /** The guards consulted at every agent-loop iteration boundary, in
    * precedence order — the first non-[[GovernorVote.Proceed]] vote wins
    * and later governors are not evaluated at that boundary.
    *
    * The default order puts the spend budget ahead of the progress
    * checkpoint: a dollar-a-minute turn must not wait for a checkpoint
    * interval, and the checkpoint's LLM reflection must not be paid for
    * at a boundary the budget gate already claimed.
    *
    * Apps override to append their own guards, drop a built-in, or
    * reorder. Append (`super.turnGovernors :+ mine`) unless preemption
    * is the intent: a governor placed BEFORE the built-ins claims
    * boundaries ahead of every one of them, the hard spend ceiling
    * included. The iteration cap and the orchestrator's mid-stream
    * intercepts are NOT governors — see [[TurnGovernor]] for why. */
  protected def turnGovernors: List[TurnGovernor] = List(budgetGovernor, progressGovernor)

  private lazy val plainTextReplyGovernor: PlainTextReplyGovernor = new PlainTextReplyGovernor
  private lazy val degenerateGenerationGovernor: DegenerateGenerationGovernor = new DegenerateGenerationGovernor()
  private lazy val turnDecisionGovernor: TurnDecisionGovernor = new TurnDecisionGovernor

  /** The guards consulted once per iteration, at the moment the provider
    * stream closes. Unlike [[turnGovernors]] every one is evaluated and
    * their emissions concatenate, so list order is the order the turn
    * publishes them in.
    *
    * Apps override to append their own outcome guards, drop a built-in,
    * or reorder. See [[sigil.governor.OutcomeGovernor]] for why these
    * verdicts ride the turn's own stream rather than the boundary
    * after it. */
  def outcomeGovernors: List[OutcomeGovernor] =
    List(plainTextReplyGovernor, degenerateGenerationGovernor, turnDecisionGovernor)

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

  /** Sigil #407 — bound on identical re-issues of a tool whose result keeps
    * RACING past the frame (settled Pending, never delivered — a large/slow
    * result that overflowed to `.sigil/output/`). Those re-issues are excluded
    * from [[maxIdenticalToolCallsInWindow]] (#354) because a transient race is
    * rational to retry; but a PERSISTENT racer would re-issue unboundedly and
    * never progress. When set to a positive N, after N raced identical
    * re-issues in the current turn the orchestrator stops inviting re-issue and
    * refuses the next one with a non-escalating Failure that redirects the
    * agent to the externalized result (read/grep the overflow file). Set to
    * `0` / negative to disable. Default 2. Distinct from the duplicate-call cap:
    * that punishes a spinning agent; this rescues a well-behaved agent from a
    * tool that can't deliver its result inline. */
  def maxRacedReissues: Int = 2

  /** Cap on the number of non-essential (action) tool calls the framework
    * dispatches from a SINGLE model response. A model that fires a whole
    * discovered tool family in one completion (e.g. all 10 `bsp_*` when it
    * needed one) has the excess refused with a corrective note, while
    * legitimate parallelism — a handful of distinct calls, such as reading
    * several files at once — still passes. The respond family, `no_response`,
    * and `stop` are the turn's delivery and never count toward the cap. `0`
    * disables. Distinct from [[maxIdenticalToolCallsInWindow]], which caps
    * REPEATED identical calls across the turn; this caps TOTAL distinct calls
    * within one response. Default 8. */
  def maxToolCallsPerResponse: Int = 8

  /** Cap on `discoveredCapabilities` entries surfaced in the
    * agent's prompt — keeps the prompt bounded even within a long
    * agent loop that issues many distinct `find_capability` queries.
    * The cap is over the *map* (one entry per distinct query); each
    * entry's matches list is already bounded by `find_capability`'s
    * page size. Apps override to tune the prompt budget. */
  def discoveredCapabilitiesPromptCap: Int = 25

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
            (RW.static(sigil.tool.BuiltinKind) :: RW.static(sigil.tool.InternalKind) ::
              RW.static(sigil.tool.consult.ConsultKind) :: toolKindRegistrations).distinct*
          )
      _ = ParticipantId.register((summon[RW[sigil.participant.WorkerParticipantId]] :: participantIds).distinctBy(_.definition.className)*)
      _ = Mode.register((ConversationMode :: modes).distinct.map(m => RW.static(m))*)
      _ = sigil.provider.WorkType.register(workTypes.map(w => RW.static(w))*)
      // Sigil #386 — app-defined conversation status; framework `Open`
      // default auto-registered. Leaf poly (referenced by `Conversation.status`
      // and the `ConversationStatusChanged` notice), so registers here before
      // the aggregate Signal registration below walks the notice definitions.
      _ = sigil.conversation.ConversationStatus.register(
            (RW.static(sigil.conversation.ConversationStatus.Open) :: conversationStatusRegistrations).distinct*
          )
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
      // Input AND output poly-RWs derive symmetrically from the same
      // sources: the memoized static roster's ToolIO, the finder's
      // declared IO contribution (an app override of `findTools`
      // contributes its codecs by construction instead of silently
      // disabling the static channel), and the explicit registration
      // lists for runtime-created records (`ScriptTool` / dynamic
      // tools whose classes have no static instance).
      registeredToolIO = resolvedStaticTools.map(_.io) ++ findTools.toolIO
      staticInputRWs = registeredToolIO.map(_.inputRW.asInstanceOf[RW[? <: sigil.tool.ToolInput]])
      _ = ToolInput.register((CoreTools.inputRWs ++ staticInputRWs ++ toolInputRegistrations).distinctBy(_.definition.className)*)
      // Sigil #265 — `ToolOutput` is a polymorphic discriminator on
      // `ToolInvoke.output`. Register the framework-shipped `Pending` /
      // `Progress` cases, every registered ToolIO's output RW, and any
      // app-defined output subtypes so `ToolInvoke` RW round-trips
      // cleanly through persistence and the wire.
      staticOutputRWs = registeredToolIO.map(_.outputRW.asInstanceOf[RW[? <: sigil.tool.ToolOutput]])
      // A tool whose `Output` is the open `ToolOutput` itself (e.g. an
      // MCP tool that returns text OR an image) carries the base
      // PolyType RW as its `outputRW`. Registering that base RW would
      // re-expand the whole hierarchy and collide every already-listed
      // leaf — so drop it here (no concrete leaf is named `ToolOutput`).
      baseToolOutputClassName = summon[RW[sigil.tool.ToolOutput]].definition.className
      _ = sigil.tool.ToolOutput.register(
            (sigil.tool.ToolOutput.frameworkOutputRWs ++ staticOutputRWs ++ toolOutputRegistrations)
              .filterNot(_.definition.className == baseToolOutputClassName)
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
      _ = sigil.tool.Tool.register((resolvedStaticTools.map(t => RW.static(t)) ++ toolRegistrations).distinct*)
      _ = sigil.skill.Skill.register((staticSkills.map(s => RW.static(s)) ++ skillRegistrations).distinct*)
      _ = Signal.register((allEventRWs ++ allDeltaRWs ++ allNoticeRWs ++ signalRegistrations)*)
      // Boot completeness — every registered tool's probe input/output
      // must round-trip through the polymorphic RWs, names must be
      // roster-wide unique, no two IO types may collide on their simple
      // class name, and suggested-next references must resolve. Needs
      // only the tool list + the registrations above, so codegen-only
      // flows (no DB) run it too.
      _ = sigil.tool.BootCompletenessCheck.run(resolvedStaticTools)
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
      // Fail startup on a section list the curator can't act on.
      _ = ContextSections.shedCascade(contextSections)
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
          // Sigil #374 — rescue boot for databases whose stored
          // `ToolInvoke.output` names a renamed/removed `ToolOutput`
          // subtype: rewrite the orphaned block to `UnknownToolOutput`
          // (lossless) so the typed events read can't abort startup.
          // Runs after the ContextFrame migration so a dead-ToolResult
          // frame on a ToolInvoke row is already nulled.
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
    *   - `AgentState` → state Complete AND activity Idle, so the agent's
    *     stranded turn lock settles to a true terminal and UIs keying the
    *     busy indicator off `activity` don't stay stuck "thinking" (#399).
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
            // Sigil #399 — an AgentState lock carries TWO fields. `.withState`
            // only resets `state`; a crash-stranded lock left
            // (Complete, Thinking) keeps UIs (which key the busy indicator off
            // `activity`) stuck "thinking" with a dead Stop button. Reset both
            // to the true terminal: Complete + Idle.
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
      sigil.maintenance.OrphanStagingConversationSweep(orphanStagingSweepInterval, orphanStagingCutoff),
      sigil.maintenance.MemoryAccessFlushTask(memoryAccessFlushInterval),
      sigil.maintenance.EmbeddingReconcileTask(embeddingReconcileInterval)
    )

  /** Cadence for [[sigil.maintenance.EmbeddingReconcileTask]] — how
    * often the framework checks the memory store against the vector
    * index for drifted points. Default: 1 hour. The check is a single
    * indexed query that matches nothing when the index is in sync, and
    * the task no-ops entirely when vector search isn't wired, so the
    * default costs nothing to leave on. */
  def embeddingReconcileInterval: scala.concurrent.duration.FiniteDuration =
    scala.concurrent.duration.DurationInt(1).hour

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
      Task.sequence(workers.map(workerTaskFor)).map { workerTasks =>
        // Detached tool executions appear beside delegate_task workers —
        // a long sweep the conversation is waiting on IS a running task.
        workerTasks.flatten ++ detachedToolTasksFor(conversationId)
      }
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
      Task.sequence(workers.map(workerTaskFor)).flatMap { workerTasks =>
        // Detached tool executions surface in the global sidebar the
        // same way delegate_task workers do — visibility follows
        // conversation membership.
        import scala.jdk.CollectionConverters.*
        val detached = detachedToolTasks.values.asScala.toList.filter(_.detachedAt.isDefined)
        Task.sequence(detached.map { t =>
          withDB(_.conversations.transaction(_.get(t.conversationId))).map {
            case Some(conv) if conv.participants.exists(_.id == viewer) =>
              Some(sigil.conversation.ConversationTask.fromDetachedTool(t))
            case _ => None
          }
        }).map { detachedTasks =>
          (workerTasks.flatten ++ detachedTasks.flatten)
            .sortBy(_.modifiedAt.value)(using Ordering.Long.reverse)
        }
      }
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

  /** Sigil #393 — max bytes to fetch for an external image before giving up
    * (the original, pre-downscale). Beyond this the image is dropped with a
    * warn rather than buffering an unbounded body. */
  protected def maxExternalImageFetchBytes: Long = 25L * 1024 * 1024

  /** Sigil #393 — timeout for fetching an external image URL. */
  protected def externalImageFetchTimeout: scala.concurrent.duration.FiniteDuration =
    scala.concurrent.duration.FiniteDuration(15, "seconds")

  /**
   * Sigil #393 — fetch the raw bytes (+ media type) of an EXTERNAL image
   * URL so the provider can downscale it to the tool's `ImageQuality` tier
   * and ship base64, rather than passing a raw `{type:url}` the provider
   * fetches at full size (blowing the multimodal ceiling → silent
   * non-render → blind re-view loops). Returns `None` on any failure
   * (unreachable, non-2xx, over [[maxExternalImageFetchBytes]], unparseable
   * URL) — the provider then drops the image block (its caption survives).
   *
   * Apps override to add per-host auth (a private CDN), a allow/deny-list,
   * or to disable external fetching entirely (`Task.pure(None)`).
   */
  def fetchExternalImageBytes(url: String): Task[Option[(Array[Byte], String)]] =
    scala.util.Try(spice.net.URL.parse(url)).toOption match {
      case None => Task.pure(None)
      case Some(u) =>
        spice.http.client.HttpClient
          .url(u)
          .timeout(externalImageFetchTimeout)
          .noFailOnHttpStatus
          .send()
          .flatMap { response =>
            if (response.status.code >= 400) Task.pure(Option.empty[(Array[Byte], String)])
            else response.content match {
              case Some(c) if c.length >= 0 && c.length > maxExternalImageFetchBytes =>
                logger.warn(s"Sigil #393 — external image $url is ${c.length} bytes (> $maxExternalImageFetchBytes cap); dropping")
                Task.pure(None)
              case Some(c) =>
                val ct = c.contentType.outputString
                c match {
                  case b: spice.http.content.BytesContent => Task.pure(Some((b.value, ct)))
                  case other => other.asStream.toList.map(bs => Some((bs.toArray, ct)))
                }
              case None => Task.pure(None)
            }
          }
          .handleError { t =>
            logger.warn(s"Sigil #393 — failed to fetch external image $url: ${Option(t.getMessage).getOrElse(t.getClass.getSimpleName)}")
            Task.pure(None)
          }
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

  /** Sigil #410 — known LLM-vendor support/help URLs & domains whose presence
    * in a raw provider error would leak which backend an app uses. Matched with
    * any leading subdomain, an optional `http(s)://` scheme, and an optional
    * path, case-insensitively; the default [[Sigil.sanitizeProviderError]]
    * replaces each match with a neutral phrase. Covers the vendors sigil ships
    * providers for; apps needing more scrub the rest via the hook. */
  private[sigil] val vendorSupportUrlPattern: scala.util.matching.Regex =
    ("(?i)\\b(?:https?://)?(?:[a-z0-9-]+\\.)*" +
      "(?:openai\\.com|anthropic\\.com|deepseek\\.com|deepinfra\\.com|mistral\\.ai|" +
      "x\\.ai|googleapis\\.com|cloudflare\\.com|digitalocean\\.com)" +
      "(?:/[^\\s)\"']*)?").r

  /** Sigil #376 — an [[AgentRunawayException]] is a STALL terminal (the
    * agent hit the iteration cap or a progress-checkpoint stall and the
    * forced-synthesis recovery still failed), not a crash. Its failure
    * Message is published `recoverable` so a follow-up user message
    * re-engages the agent instead of dead-ending the conversation;
    * genuine crashes (tool throws, projection failures, transform
    * blow-ups, …) stay non-recoverable. */
  def isStallFailure(t: Throwable): Boolean = t.isInstanceOf[AgentRunawayException]

  /** Sigil #378 — `record_consent` is a no-op unless some tool in scope
    * sets `requiresUserConsent`. Keep it in the per-turn roster only when
    * a consent-gated tool is actually present (injecting it if absent),
    * and drop it otherwise — so on apps with no consent-gated tools it's
    * never a dead-end attractor the model loops on. It stays
    * DB-registered (resolvable) regardless; this only governs the
    * per-turn roster surfaced to the model. */
  /** Sigil #380 — decode tool rows from the store leniently: a row whose
    * poly type is no longer registered (a removed tool) is skipped rather
    * than throwing "Type not found" and failing the whole read. So removing
    * a tool is never DB-corrupting on read. Mirrors the orphan-tolerant
    * read in [[sigil.tool.StaticToolSyncUpgrade]]. */
  def decodeToolsLeniently(rows: List[fabric.Json]): List[sigil.tool.Tool] = {
    val toolRW = summon[RW[sigil.tool.Tool]]
    rows.flatMap { json =>
      scala.util.Try(json.as[sigil.tool.Tool](using toolRW)) match {
        case scala.util.Success(tool) => Some(tool)
        case scala.util.Failure(err) =>
          val id = sigil.tool.StaticToolSyncUpgrade.extractOrphanId(json).getOrElse("(no extractable id)")
          val detail = s"${err.getClass.getSimpleName}: ${Option(err.getMessage).getOrElse("")}"
          if (sigil.tool.StaticToolSyncUpgrade.isSpecViolation(err))
            scribe.warn(s"lenient tool read: skipping row id=$id — it decoded but its ToolSpec is invalid ($detail). " +
              "The record is intact; fix the spec so the tool becomes discoverable again.")
          else
            scribe.warn(s"lenient tool read: skipping row id=$id — its polytype is not registered ($detail). " +
              "Register the tool's RW or let the static-tool sync prune the orphan.")
          None
      }
    }
  }

  def reconcileConsentTool(tools: Vector[sigil.tool.Tool]): Vector[sigil.tool.Tool] = {
    val consent = sigil.tool.core.RecordConsentTool
    val needsConsent = tools.exists(t => t.requiresUserConsent && t.schema.name != consent.schema.name)
    if (needsConsent) {
      if (tools.exists(_.schema.name == consent.schema.name)) tools else tools :+ consent
    } else tools.filterNot(_.schema.name == consent.schema.name)
  }

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
