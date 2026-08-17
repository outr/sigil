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
import sigil.governor.{BudgetDirective, BudgetGovernor, CheckpointIntervention, DuplicateRefusalGovernor, GovernorContext}
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
import sigil.provider.{ContextFeature, ContextFeatures, ContextSection, ContextSections, FeatureId, InstructionTier, ModelProfile, PromptShape, Reliability, ResolvedReferences}
import sigil.service.Service
import sigil.signal.{AgentActivity, AgentStateDelta, CoreSignals, Delta, EventState, LocationDelta, Notice, ServiceLogSignal, ServiceStatusSignal, Signal, ToolDelta, TopicDelta}
import sigil.spatial.{Geocoder, NoOpGeocoder, Place}
import sigil.tool.Tool
import sigil.tool.fs.{FileSystemContext, LocalFileSystemContext}
import sigil.tool.core.{CoreTools, FindCapabilityTool}
import sigil.conversation.{ReplySuggestionContext, ReplySuggestionsConfig}
import sigil.tool.model.ResponseContent
import sigil.tool.{ToolFinder, ToolInput}
import sigil.vector.{NoOpVectorIndex, VectorIndex, VectorPoint, VectorPointId, VectorSearchResult}

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

trait Sigil extends ProviderConfigStore with MemoryOps with ViewerStateOps with CheckpointOps with HealingOps
  with DirectiveOps with RoutingOps with DiscoveryOps with AgentLoopOps with TopicOps with ConversationOps
  with PublishOps with ProjectionOps with RetrievalOps with RegistrationOps with LifecycleOps
  with TurnPhaseOps {

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
   * The registered [[sigil.provider.ContextFeature]]s — the packaging
   * layer over [[contextSections]] for context that is computed per
   * turn, may consult a live source, and ships toggleable by an open
   * id. Framework features come first; apps append their own:
   *
   * {{{
   * override def contextFeatures: List[ContextFeature] =
   *   super.contextFeatures ++ List(ErpConnectivityFeature(erp))
   * }}}
   *
   * Enabled features are evaluated exactly once per request and compile
   * into sections appended to [[contextSections]], so the renderer, the
   * profiler, the shed cascade, and `context_breakdown` see them as
   * sections like any other.
   */
  def contextFeatures: List[ContextFeature] = ContextFeatures.all

  /**
   * Feature ids that must not contribute. A disabled feature compiles
   * to no section at all, so the request is byte-for-byte what it was
   * before the feature existed rather than carrying an empty one.
   */
  def disabledFeatures: Set[FeatureId] = Set.empty

  /**
   * Whether a registered feature contributes. The default honours the
   * feature's own `defaultEnabled` unless [[disabledFeatures]] names
   * it; apps override to switch on a module's opt-in feature, or to
   * gate one on their own configuration.
   */
  def featureEnabled(feature: ContextFeature): Boolean =
    feature.defaultEnabled && !disabledFeatures.contains(feature.id)

  /** The features actually contributing, in registration order. */
  final def enabledContextFeatures: List[ContextFeature] = contextFeatures.filter(featureEnabled)

  /**
   * [[contextSections]] plus the sections the enabled features compile
   * to — the list every consumer reads. `contextSections` stays the
   * declaration of the prompt's fixed layout; features append to it, so
   * an app that replaces that layout wholesale still gets its
   * registered features.
   */
  final def resolvedContextSections: List[ContextSection] =
    contextSections ++ ContextFeatures.sections(enabledContextFeatures)

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
      withDB(_.conversationEventsConsistent(conv._id)).map { evs =>
        evs.iterator
          .collect { case m: sigil.event.Message => m }
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
                 withDB(_.conversationEventsConsistent(context.conversation._id)).map { evs =>
                   val userTs = userMsg.map(_.timestamp.value).getOrElse(0L)
                   evs.exists {
                     case m: sigil.event.Message =>
                       m.source.contains("routing-fallback") && m.timestamp.value >= userTs
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
          agent, context.conversation.currentMode, suggested, overlays.map(_.policy), recentlyUsed,
          clientToolNames = clientTools.toolsFor(context.conversation.id).map(_.name)
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
    withDB(_.conversationEventsConsistent(conversationId)).flatMap { events =>
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

  /**
   * Framework-generated reply suggestions — OFF by default. When set,
   * every turn that settles with a user-visible reply fires a cheap
   * background consult predicting what the user is likely to say next,
   * and the framework publishes the result as a transient
   * [[sigil.signal.SuggestedReplies]] notice. `None` dispatches
   * nothing and costs nothing.
   *
   * Clients render `suggestions.headOption` as inline type-ahead in
   * the composer, or the whole list as tappable chips when the config
   * asks for more than one.
   */
  def replySuggestions: Option[ReplySuggestionsConfig] = None

  /** How many trailing [[ContextFrame]]s the reply-suggestion consult
    * renders as its "earlier in the conversation" excerpt, on top of
    * the settled reply and the triggering user message it always
    * carries. */
  def replySuggestionFrameTail: Int = 6


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

  /** How many times ONE (tool, canonical args) group may be refused by the
    * duplicate-call cap in a single turn before the framework stops refusing
    * and ends the turn through forced synthesis.
    *
    * A refusal is a Tool-role Failure, so it re-triggers the agent loop: a
    * model that re-issues the same call regardless spends every remaining
    * iteration collecting refusals. The cap stays the detector — this is the
    * bound on how long detection alone is allowed to run before the turn is
    * wrapped up with whatever the agent has. Small by design: the first
    * refusal carries the corrective note and the tier escalation, the second
    * proves the model isn't reading it. 0 (or negative) disables the
    * termination, restoring refuse-forever. */
  protected[sigil] def duplicateRefusalLimit: Int = 2

  private lazy val budgetGovernor: BudgetGovernor = new BudgetGovernor(this)
  private lazy val duplicateRefusalGovernor: DuplicateRefusalGovernor = new DuplicateRefusalGovernor(this)
  private lazy val progressGovernor: ProgressGovernor = new ProgressGovernor(this)

  /** The guards consulted at every agent-loop iteration boundary, in
    * precedence order — the first non-[[GovernorVote.Proceed]] vote wins
    * and later governors are not evaluated at that boundary.
    *
    * The default order puts the spend budget ahead of the progress
    * checkpoint: a dollar-a-minute turn must not wait for a checkpoint
    * interval, and the checkpoint's LLM reflection must not be paid for
    * at a boundary the budget gate already claimed. The refusal-loop
    * backstop goes last: by the time it can fire, the agent has already
    * read two refusals, and every richer guard ahead of it keeps the
    * boundaries it would have claimed anyway.
    *
    * Apps override to append their own guards, drop a built-in, or
    * reorder. Append (`super.turnGovernors :+ mine`) unless preemption
    * is the intent: a governor placed BEFORE the built-ins claims
    * boundaries ahead of every one of them, the hard spend ceiling
    * included. The iteration cap and the orchestrator's mid-stream
    * intercepts are NOT governors — see [[TurnGovernor]] for why. */
  protected def turnGovernors: List[TurnGovernor] =
    List(budgetGovernor, progressGovernor, duplicateRefusalGovernor)

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

  /** Per-mode share of the smallest registered model's context window
    * a Mode's bundled skill content is allowed to consume. Default
    * 10% — a mode skill that exceeds this at startup fails the
    * `Sigil.instance` task with `IllegalStateException` so the app
    * can't ship a configuration that pre-emptively crowds the budget.
    * Distinct from [[pinnedShareLimit]]: mode skills are app-shipped
    * config (a config bug should fail-loud at startup); pinned
    * memories are runtime-authored (a soft warning fits better there). */
  def modeSkillShareLimit: Double = 0.10

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
