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
import sigil.conversation.{ActiveSkillSlot, ContextFrame, ContextKey, ContextMemory, ContextSummary, Conversation, EncodedContext, FrameBuilder, MemorySource, MemoryStatus, ParticipantProjection, ProgressContext, SkillSource, Topic, TopicEntry, TopicShiftResult, TurnInput, UpsertMemoryResult}
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
import sigil.event.{AgentState, CapabilityResults, Event, Message, MessageRole, MessageVisibility, ModeChange, Stop, ToolInvoke, ToolResults, TopicChange, TopicChangeKind}
import sigil.role.Role
import sigil.orchestrator.Orchestrator
import sigil.provider.{Complexity, ConversationMode, ConversationRequest, Mode, ProviderStrategy, ToolPolicy, WorkType}
import sigil.information.Information
import sigil.participant.{AgentParticipant, AgentParticipantId, DefaultAgentParticipant, Participant, ParticipantId}
import sigil.pipeline.{ContentExternalizationTransform, GeocodingEnrichmentEffect, InboundTransform, LocationCaptureTransform, MemoryCacheInvalidationEffect, MessageIndexingEffect, RedactLocationTransform, RespondOptionsSelectionFramingTransform, SettledEffect, SignalHub, TopicIndexCanonicalizingTransform, ViewerTransform}
import sigil.render.{ContentRenderer, HtmlRenderer, MarkdownRenderer, PlainTextRenderer, SlackMrkdwnRenderer}
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

  /** Mixin hook for polytype registrations that need the framework's leaf
    * polytypes (Mode, WorkType, SpaceId, ...) populated before the mixin
    * subtypes' RW Definitions are eagerly evaluated. Runs inside
    * [[polymorphicRegistrations]] after the framework leaves and before
    * the aggregates (Participant, Tool, Signal). Default `Task.unit`. */
  protected def mixinPolymorphicRegistrations: rapid.Task[Unit] = rapid.Task.unit

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

  /** Soft check on a proposed pinned-memory write — never fails the
    * task. Apps that want hard rejection (e.g. regulated industries
    * where blowing the inviolable share is a real problem) override
    * this hook to fail with their own exception based on the same
    * [[sigil.conversation.CoreContextValidator]] estimates the
    * framework uses for warnings. Default: no-op for any memory,
    * pinned or not. */
  protected def validateCoreContextCap(proposed: ContextMemory): Task[Unit] =
    Task.unit

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
        val score = needles.foldLeft(0.0) { (acc, kw) =>
          val curated = if (curatedKeywords.contains(kw)) 8.0 else 0.0
          val words = haystack.split("\\W+").toSet
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
          // Sigil bug #170 — N deletes share one assignments transaction.
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
    withDB(_.events.transaction(_.list)).map { events =>
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
                                         effectiveWorkType: WorkType,
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
                     reservedOutputTokens: Long = 1024L): Task[Id[Model]] = {
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

    accessibleSpaces(chain, convId).flatMap { spaces =>
      val ordered = spaces.toList
      def loop(remaining: List[SpaceId]): Task[Option[Id[Model]]] = remaining match {
        case Nil => Task.pure(None)
        case space :: rest =>
          resolveProviderStrategy(space).flatMap {
            case None => loop(rest)
            case Some(strategy) =>
              strategy.availableCandidates(workType).find(fits).map(_.modelId) match {
                case Some(modelId) => Task.pure(Some(modelId))
                case None          => loop(rest)
              }
          }
      }
      loop(ordered).map(_.getOrElse(fallback))
    }
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

  /**
   * Bulk variant of [[putInformation]]. The framework's
   * [[sigil.conversation.compression.StandardBlockExtractor]] hands
   * the entire batch in one call so apps backed by transactional
   * stores (LightDB + Lucene, RocksDB, Postgres) can amortise commit
   * / fsync / segment-flush overhead across the whole batch.
   *
   * Default = `N` calls to `putInformation` — preserves the
   * per-record contract for apps that don't override. Apps with a
   * transactional store override to a single-transaction multi-upsert.
   * Bulk-import flows (50K+ events) drop from `N` commits to one when
   * the override lands.
   */
  def putInformations(informations: Vector[Information]): Task[Unit] =
    Task.sequence(informations.toList.map(putInformation)).unit

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
                         suggested: List[sigil.tool.ToolName],
                         overlays: List[ToolPolicy] = Nil): List[sigil.tool.ToolName] = {
    import sigil.tool.core.{
      CancelTool, ChangeModeTool, FindCapabilityTool, NoResponseTool, RespondTool,
      RespondFailureTool, RespondFieldTool, RespondOptionsTool
    }
    import sigil.tool.skill.ActivateSkillTool
    // Reply surface: `respond` (markdown + Field-callout + H2-Card +
    // disposition) for telling; `respond_options` (typed) for asking.
    // The standalone `respond_field` / `respond_failure` /
    // `respond_card` tools are opt-in (not essentials) — markdown
    // callouts and disposition cover their cases in `respond`.
    // `no_response` dropped from defaults in sigil bug #156.
    val fullEssentials = List(
      RespondTool, RespondOptionsTool, CancelTool
    ).map(_.schema.name)
    val pureDiscoveryEssentials = List(CancelTool.schema.name)

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

    // Bug #97 — fold conversation overlays last so they're additive
    // on top of the agent + mode policies. `Active(names)` from
    // `start_metals` adds those names; `Exclusive` / `None` from a
    // user-installed overlay can also restrict, mirroring the
    // mode-side semantics.
    val state = overlays.foldLeft(apply(apply(initial, agent.tools), mode.tools))(apply)
    val essentials     = if (state.pureDiscovery) pureDiscoveryEssentials else fullEssentials
    val findCapability = if (state.includesFindCapability) List(FindCapabilityTool.schema.name) else Nil
    val baseline       = if (state.includesBaseline) agent.toolNames else Nil
    val merged         = (essentials ++ findCapability ++ baseline ++ state.extras ++ suggested).distinct
    val deduped =
      if (state.pureDiscovery) {
        // Strip the entire respond family + no_response so the agent
        // can only reach a reply through discovery. The legacy
        // standalone tools (deprecated post sigil bug #157) stay in
        // the strip set so apps that opted back into them retain the
        // same pure-discovery semantics.
        val stripped: Set[sigil.tool.ToolName] =
          (Set(
            RespondTool, RespondOptionsTool, RespondFieldTool, RespondFailureTool, NoResponseTool
          ): @annotation.nowarn("cat=deprecation")).map(_.schema.name)
        merged.filterNot(stripped.contains)
      } else merged
    // Tool position bias is real for smaller models — they tend to pick the
    // first appropriate-looking tool. Put discovery + action tools first so
    // a "do X" request can land on `find_capability` / `change_mode` instead
    // of being captured by the always-applicable `respond` family. Response
    // tools render last so they're available for chat without dominating
    // when an action tool is the right call.
    val priority: Map[sigil.tool.ToolName, Int] = (Map(
      ChangeModeTool.schema.name        -> 0,
      FindCapabilityTool.schema.name    -> 1,
      ActivateSkillTool.schema.name     -> 2,
      CancelTool.schema.name              -> 100,
      // Within the response tail, `respond_options` precedes `respond` so first-tool
      // bias on small models surfaces the specific "asking" shape before the
      // catch-all "telling" tool. Sigil bug #168.
      RespondOptionsTool.schema.name    -> 101,
      RespondTool.schema.name           -> 102,
      RespondFieldTool.schema.name      -> 103,
      RespondFailureTool.schema.name    -> 104,
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
    val suggested      = context.turnInput.projectionFor(agent.id).suggestedTools

    // Strategy resolution: Mode override beats space-level
    // assignment beats agent's pinned modelId. The strategy returns
    // ordered candidates for the agent's `workType`; the first
    // available candidate's `modelId` is what this turn calls. Apps
    // wanting cooldown-aware fallback across multiple turns can
    // override `runAgentTurn` (or override `resolveProviderStrategy`
    // to return a custom strategy that itself encapsulates retry).
    val strategyTask: Task[Option[ProviderStrategy]] =
      context.conversation.pinnedModelId match {
        // Conversation-level pin wins over mode and space strategies —
        // pinned means the user explicitly chose this model for every
        // dispatch in the conversation.
        case Some(pinnedId) =>
          Task.pure(Some(ProviderStrategy.single(pinnedId)))
        case None =>
          context.conversation.currentMode.strategyId match {
            case Some(modeStrategyId) =>
              withDB(_.providerStrategies.transaction(_.get(modeStrategyId)))
                .map(_.map(materializeStrategy))
            case None =>
              resolveProviderStrategy(context.conversation.space)
          }
      }

    // Mode-overrides-agent for work-type routing: a mode that intrinsically
    // dictates a work shape (`ScriptAuthoringMode = CodingWork`,
    // `WebBrowserMode = AnalysisWork`) routes the turn to the matching
    // candidate chain even when the agent itself defaults to
    // `ConversationWork`. Modes that don't pin a work type fall through
    // to whatever the agent declares.
    val effectiveWorkType: WorkType =
      context.conversation.currentMode.workType.getOrElse(agent.workType)

    // Bug #128 — find the latest user-authored Message; classify
    // (WorkType, Complexity) per user turn when the strategy opted
    // in. Cached by `Sigil.classifyForRoute` so multiple agent
    // iterations within one user turn share the round-trip.
    val latestUserMessage: Task[Option[sigil.event.Message]] =
      withDB(_.events.transaction(_.list)).map { evs =>
        evs.iterator
          .collect { case m: sigil.event.Message if m.conversationId == context.conversation._id => m }
          .filter(m => !m.participantId.isInstanceOf[sigil.participant.AgentParticipantId])
          .filter(_.role == sigil.event.MessageRole.Standard)
          .toList
          .sortBy(-_.timestamp.value)
          .headOption
      }.handleError(_ => Task.pure(None))

    val resolved: Task[(Provider, Vector[Tool], Id[Model], GenerationSettings, List[sigil.role.Role])] =
      for {
        strategyOpt <- strategyTask
        userMsg     <- latestUserMessage
        (routedWorkType, complexity) <- strategyOpt match {
          case Some(strategy) => classifyForRoute(strategy, effectiveWorkType, context.conversation, userMsg, context)
          case None           => Task.pure((effectiveWorkType, Complexity.Medium))
        }
        candidateChain = strategyOpt.toList.flatMap(_.availableCandidates(routedWorkType))
        skipReasons    = candidateChain.iterator.collect {
          case c if !c.supportedComplexity.contains(complexity) =>
            c.modelId -> s"supportedComplexity does not include $complexity"
        }.toMap
        chosen       = candidateChain.find(_.supportedComplexity.contains(complexity))
        modelId      = chosen.map(_.modelId).getOrElse(agent.modelId)
        _ <- publishRouteResolved(
               agentId            = agent.id,
               conversation       = context.conversation,
               userMessage        = userMsg,
               strategyOpt        = strategyOpt,
               inferredWorkType   = routedWorkType,
               effectiveWorkType  = effectiveWorkType,
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
                 withDB(_.events.transaction(_.list)).map { evs =>
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
        effectiveNames = effectiveToolNames(
          agent, context.conversation.currentMode, suggested, overlays.map(_.policy)
        ).distinct
        // Per-candidate `settings` overlays the agent's
        // generationSettings. The framework keeps the agent's settings
        // as the base — the candidate's settings take precedence on
        // any field they specify (currently a wholesale replace; if
        // we want field-by-field merge later that lives here).
        genSettings  = chosen.map(_.settings).getOrElse(agent.generationSettings)
        p           <- providerFor(modelId, effectiveChain)
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
      // Bug #91 — stamp the registry's canonical id on outbound
      // requests when one is known. Settled Messages then carry the
      // prefixed form (`openai/gpt-5.5`) that the cost projection
      // and OpenRouter-derived catalog lookups expect, so we don't
      // depend on the tolerant fallback at every read site. No-op
      // when the candidate's id isn't in the registry.
      val canonicalModelId = cache.canonicalIdFor(modelId)
      val request = ConversationRequest(
        conversationId = context.conversation.id,
        modelId = canonicalModelId,
        instructions = agent.instructions,
        turnInput = context.turnInput,
        currentMode = context.conversation.currentMode,
        currentTopic = context.conversation.currentTopic,
        previousTopics = context.conversation.previousTopics,
        generationSettings = genSettings,
        tools = tools,
        builtInTools = agent.builtInTools ++ context.conversation.currentMode.builtInTools,
        chain = effectiveChain,
        roles = rolesResolved,
        isGreeting = context.isGreeting,
        forceResponseSynthesis = context.forceResponseSynthesis
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
    List(LocationCaptureTransform, ContentExternalizationTransform, RespondOptionsSelectionFramingTransform, TopicIndexCanonicalizingTransform)

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
      validateEventInvariants(signal).flatMap { _ =>
      applyInboundTransforms(signal).flatMap { resolved =>
        for {
          _ <- withDB(_.apply(resolved))
          _ <- attachContextFrameOnSettle(resolved)
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
    val targetIdOpt: Option[Id[Event]] = signal match {
      case e: Event if e.state == EventState.Complete =>
        Some(e._id)
      case d: sigil.signal.Delta =>
        Some(d.target.asInstanceOf[Id[Event]])
      case _ => None
    }
    targetIdOpt match {
      case None => Task.unit
      case Some(eventId) =>
        withDB(_.events.transaction { tx =>
          tx.get(eventId).flatMap {
            case None => Task.unit
            case Some(event) if event.state != EventState.Complete => Task.unit
            case Some(event) =>
              val frame = FrameBuilder.computeFrame(event)
              if (event.contextFrame == frame) Task.unit
              else tx.upsert(event.withContextFrame(frame)).unit
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
      val framed = events.map { e =>
        if (e.state != EventState.Complete || e.contextFrame.nonEmpty) e
        else e.withContextFrame(FrameBuilder.computeFrame(e))
      }
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
          model.pricing.prompt * m.usage.promptTokens + model.pricing.completion * m.usage.completionTokens
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
    import sigil.signal.{FrameworkWorkflowNotice, FrameworkWorkflowPhase}
    val workflowId = java.util.UUID.randomUUID().toString
    val started    = System.currentTimeMillis()
    val token      = new CancellationToken(workflowId)
    val record     = ActiveFrameworkWorkflow(workflowId, workflowType, label, conversationId, started, token)
    def emit(phase: FrameworkWorkflowPhase): Task[Unit] =
      publish(FrameworkWorkflowNotice(workflowId, workflowType, phase, conversationId))
    val stepCb: String => Task[Unit] = { stepLabel =>
      token.checkpoint.flatMap(_ => emit(FrameworkWorkflowPhase.Step(stepLabel, System.currentTimeMillis() - started)))
    }
    val control = FrameworkWorkflowControl(stepCb, token)
    emit(FrameworkWorkflowPhase.Started(label)).flatMap { _ =>
      Sigil.activeFrameworkWorkflows.put(workflowId, record)
      task(control).attempt.flatMap { result =>
        Sigil.activeFrameworkWorkflows.remove(workflowId)
        result match {
          case scala.util.Success(value) =>
            emit(FrameworkWorkflowPhase.Completed(System.currentTimeMillis() - started))
              .map(_ => value)
          case scala.util.Failure(c: CancellationException) =>
            emit(FrameworkWorkflowPhase.Failed(s"cancelled: ${c.reason}", System.currentTimeMillis() - started))
              .flatMap(_ => Task.error(c))
          case scala.util.Failure(err) =>
            val reason = s"${err.getClass.getSimpleName}: ${Option(err.getMessage).getOrElse("")}"
            emit(FrameworkWorkflowPhase.Failed(reason, System.currentTimeMillis() - started))
              .flatMap(_ => Task.error(err))
        }
      }
    }
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
        for {
          all <- withDB { db =>
                   db.events.transaction(_.list.map(_.filter(_.conversationId == convId)))
                 }
          _   <- {
                   val sorted = all.sortBy(_.timestamp.value)
                   val cap = math.max(0, limit)
                   val window = if (sorted.length <= cap) sorted else sorted.drop(sorted.length - cap)
                   val hasMore = sorted.length > cap
                   publishTo(fromViewer, sigil.signal.ConversationSnapshot(convId, window.toVector, hasMore))
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
                      spaces: Option[Set[SpaceId]] = None,
                      categories: Option[Set[sigil.storage.StoredFileCategory]] = None,
                      includeExpired: Boolean = false): Task[List[sigil.signal.StoredFileSummary]] =
    accessibleSpaces(List(viewer)).flatMap { authorized =>
      val effective = spaces.fold(authorized)(_.intersect(authorized))
      val now = lightdb.time.Timestamp()
      withDB(_.storedFiles.transaction(_.list)).map(_.toList.collect {
        case file if effective.contains(file.space)
                  && categories.forall(_.contains(file.category))
                  && (includeExpired || !file.isExpired(now)) =>
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
      withDB(_.events.transaction(_.get(d.target.asInstanceOf[Id[Event]]))).flatMap {
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
        updateProjection(ti.conversationId, ti.participantId) { proj =>
          proj.copy(recentTools = ti.toolName :: proj.recentTools.filterNot(_ == ti.toolName))
        }
      case tr: ToolResults if tr.schemas.nonEmpty =>
        // Sigil bug #169 — replace the overlay only when the tool result
        // carries a non-empty suggestion set. Tools that don't participate
        // in natural-progression flows emit `schemas = Nil` (the framework
        // default) and have no effect on the overlay, so a discovered tool
        // surviving from a prior `find_capability` stays in scope across
        // subsequent ToolResults emissions. Tools that DO participate
        // (e.g. `create_workflow` → `[add_workflow_step, add_trigger]`)
        // re-emit their suggestions on each call so the relevant follow-
        // ups stay sticky across multi-step build sequences.
        updateProjection(tr.conversationId, tr.participantId) { proj =>
          proj.copy(suggestedTools = tr.schemas.map(_.name).toList)
        }
      case _: ToolResults => Task.unit
      case cr: CapabilityResults =>
        updateProjection(cr.conversationId, cr.participantId) { proj =>
          val toolNames = cr.matches.collect {
            case m if m.capabilityType == sigil.tool.discovery.CapabilityType.Tool => sigil.tool.ToolName(m.name)
          }
          val now = Timestamp()
          val updatedDiscovered =
            if (cr.query.isEmpty) proj.discoveredCapabilities
            else proj.discoveredCapabilities.updatedWith(cr.query) {
              case Some(existing) => Some(existing.copy(matches = toolNames, lastSeen = now))
              case None           => Some(_root_.sigil.conversation.DiscoveredCapability(
                matches = toolNames, firstSeen = now, lastSeen = now
              ))
            }
          proj.copy(
            suggestedTools         = toolNames,
            discoveredCapabilities = updatedDiscovered
          )
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

  /** Most-recent [[sigil.event.ToolApproval]] for `(toolName,
    * conversationId)`, or `None` when the agent hasn't recorded a
    * decision yet. Sigil bug #83 — the orchestrator's consent gate
    * reads this before dispatching a `requiresUserConsent` tool;
    * apps can also call directly to surface "is this tool approved
    * in this conversation?" UX. */
  def latestToolApproval(toolName: sigil.tool.ToolName,
                         conversationId: Id[Conversation]): Task[Option[sigil.event.ToolApproval]] =
    withDB(_.events.transaction(_.list)).map { events =>
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
      withDB(_.events.transaction(_.list)).map { all =>
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
    withDB(_.conversations.transaction(_.modify(conversationId) {
      case Some(conv) =>
        val current = conv.clearedAt.map(_.value).getOrElse(0L)
        if (at.value <= current) Task.pure(Some(conv))
        else Task.pure(Some(conv.copy(clearedAt = Some(at), modified = Timestamp(Nowish()))))
      case None => Task.pure(None)
    })).unit

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
    * code that needs to mutate per-participant projection state. */
  def updateProjection(conversationId: Id[Conversation], participantId: ParticipantId)
                      (f: ParticipantProjection => ParticipantProjection): Task[Unit] =
    withDB(_.participantProjections.transaction(_.modify(ParticipantProjection.idFor(participantId, conversationId)) {
      case Some(proj) =>
        Task.pure(Some(f(proj).copy(modified = Timestamp(Nowish()))))
      case None =>
        Task.pure(Some(f(ParticipantProjection.empty(participantId, conversationId))
          .copy(modified = Timestamp(Nowish()))))
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
    updateProjection(conversationId, participantId)(
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
    updateProjection(conversationId, participantId)(
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
    withDB(_.events.transaction(_.get(readThrough))).flatMap {
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
    withDB(_.events.transaction(_.get(stateId))).flatMap {
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
    withDB(_.events.transaction(_.get(stateId))).map {
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

  /** Persist a new [[ContextMemory]] and return the stored record.
    * When vector search is wired, auto-embeds `memory.fact` and
    * upserts into [[vectorIndex]] with payload
    * `kind=memory, spaceId=…`.
    *
    * Pinned memories (`memory.pinned == true`) pass through the soft
    * [[validateCoreContextCap]] hook (default no-op — apps that want
    * hard rejection override and throw their own exception).
    *
    * If [[memoryClassifierModel]] is set and the supplied
    * `memory.keywords` is empty, the framework runs a one-shot LLM
    * classification (sync) to populate keywords + permanence + space
    * before the write. The classifier respects caller-set fields:
    * non-empty keywords skip the call entirely, explicit
    * `pinned = true` is preserved, and a non-Global caller-set space
    * is preserved. Apps that want fully manual control supply
    * non-empty keywords. */
  def persistMemory(memory: ContextMemory): Task[ContextMemory] =
    validateCoreContextCap(memory).flatMap { _ =>
      enrichMemoryClassification(memory, memory.createdBy.toList).flatMap { enriched =>
        withDB(_.memories.transaction(_.upsert(enriched))).flatMap { stored =>
          indexMemory(stored).map(_ => stored)
        }
      }
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
    if (!memory.key.exists(_.nonEmpty))
      Task.error(new IllegalArgumentException("upsertMemoryByKey requires Some(non-empty key); use persistMemory for un-keyed inserts"))
    else validateCoreContextCap(memory).flatMap { _ =>
      enrichMemoryClassification(memory, memory.createdBy.toList).flatMap(upsertMemoryByKeyImpl)
    }
  }

  private def upsertMemoryByKeyImpl(memory: ContextMemory): Task[UpsertMemoryResult] =
    withDB { db =>
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
                  keywords = memory.keywords,
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

  /**
   * Seed a [[SpaceId]] with a list of declarative natural-language
   * statements at account-creation time (or any moment the app has
   * known facts that haven't yet shown up in conversation):
   *
   * {{{
   *   sigil.initializeMemories(
   *     space      = UserSpace(userId),
   *     statements = List(
   *       "My first name is Matt",
   *       "My last name is Hicks",
   *       "My email address is matt@outr.com",
   *       "I'm 46 years old"
   *     ),
   *     modelId    = extractionModelId,
   *     chain      = List(theUserId, theAgentId)
   *   )
   * }}}
   *
   * The framework wraps the statements in an extraction prompt and
   * runs them through [[ExtractMemoriesTool]] via [[ConsultTool]] —
   * one LLM round-trip per call regardless of statement count, so
   * the model can disambiguate keys cross-statement (e.g. it
   * produces `user.first_name` + `user.last_name` rather than
   * collapsing both into `user.name`).
   *
   * Each result becomes a [[ContextMemory]] with
   * `source = MemorySource.UserInput`, `status = MemoryStatus.Approved`,
   * and `pinned = pinAll` (default `true` — declarative identity
   * facts are almost always always-loaded). Keyed entries route
   * through [[upsertMemoryByKey]] (idempotent — re-running with the
   * same seeds refreshes rather than duplicates); keyless entries
   * fall back to [[persistMemory]].
   *
   * Apps that want fine-grained per-fact control over pinning skip
   * this helper and construct [[ContextMemory]] records directly.
   */
  def initializeMemories(space: SpaceId,
                         statements: List[String],
                         modelId: Id[Model],
                         chain: List[sigil.participant.ParticipantId],
                         pinAll: Boolean = true,
                         systemPrompt: String = Sigil.DefaultInitializationSystemPrompt): Task[List[ContextMemory]] = {
    val cleaned = statements.iterator.map(_.trim).filter(_.nonEmpty).toList
    if (cleaned.isEmpty) Task.pure(Nil)
    else {
      val numbered = cleaned.iterator.zipWithIndex.map { case (s, i) => s"  ${i + 1}. $s" }.mkString("\n")
      val userPrompt =
        s"""Convert each statement below into a durable memory via the `extract_memories` tool.
           |One memory per statement. Use a stable `key` rooted at the user's identity (e.g.
           |"user.first_name", "user.email") so future updates can version the slot.
           |
           |Statements:
           |$numbered""".stripMargin
      sigil.tool.consult.ConsultTool.invoke[sigil.tool.consult.ExtractMemoriesInput](
        sigil = this,
        modelId = modelId,
        chain = chain,
        systemPrompt = systemPrompt,
        userPrompt = userPrompt,
        tool = sigil.tool.consult.ExtractMemoriesTool
      ).flatMap {
        case None => Task.pure(Nil)
        case Some(result) =>
          val kept = result.memories.filter(_.content.trim.nonEmpty)
          Task.sequence(kept.map { m =>
            val mem = ContextMemory(
              fact       = m.content,
              label      = if (m.label.trim.nonEmpty) m.label else m.key.getOrElse("memory"),
              summary    = m.content,
              source     = MemorySource.UserInput,
              spaceId    = space,
              key        = m.key,
              keywords   = m.tags.toVector,
              pinned     = pinAll,
              status     = MemoryStatus.Approved,
              createdBy  = chain.lastOption
            )
            // Seeded outside any conversation, so leave `conversationId`
            // None and skip the `*For` variants' location lookup.
            if (m.key.isDefined) upsertMemoryByKey(mem).map(_.memory)
            else persistMemory(mem)
          })
      }
    }
  }

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

  /** Model used by [[persistMemory]] / [[upsertMemoryByKey]] to extract
    * retrieval keywords for the memory's content (sync — blocks the
    * write on a one-shot LLM call). When `None` the framework skips
    * extraction and the memory persists with whatever keywords the
    * caller supplied (often empty); the lexical retriever then matches
    * only on label / summary / fact / tags via the existing tokenized
    * `searchText` field, which works less well for memories whose
    * surface vocabulary doesn't share tokens with future queries.
    *
    * Apps wanting topical retrieval to actually work over their memory
    * collection set this to a small / fast model — the classification
    * is a single short-list response per memory, not a reasoning task. */
  def memoryClassifierModel: Option[Id[Model]] = None

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

  /** Run [[sigil.tool.consult.ClassifyMemoryTool]] against the memory's
    * content when [[memoryClassifierModel]] is set and the caller didn't
    * already supply keywords. Returns the input memory enriched with
    * keywords + permanence (`pinned`) + space (or unchanged on opt-out
    * / classification failure — never blocks persist on an LLM hiccup).
    *
    * Caller-set fields are respected:
    *   - `memory.keywords` non-empty → skip the classifier entirely
    *     (caller has explicit keywords; nothing to enrich).
    *   - `memory.pinned == true` → keep pinned even if classifier says
    *     `Once` (caller deliberately pinned).
    *   - `memory.spaceId` other than [[sigil.GlobalSpace]] → keep the
    *     caller's explicit space (the classifier's choice only fills
    *     in when the caller defaulted to global). */
  private def enrichMemoryClassification(memory: ContextMemory,
                                          chain: List[sigil.participant.ParticipantId]
                                         ): Task[ContextMemory] =
    if (memory.keywords.nonEmpty) Task.pure(memory)
    else memoryClassifierModel match {
      case None => Task.pure(memory)
      case Some(modelId) =>
        for {
          accessible <- memory.conversationId match {
                          case Some(convId) => accessibleSpaces(chain, convId).map(_ + GlobalSpace)
                          case None         => accessibleSpaces(chain).map(_ + GlobalSpace)
                        }
          recentMsg  <- recentUserMessageText(memory.conversationId)
          enriched   <- runMemoryClassifier(memory, chain, modelId, accessible, recentMsg)
        } yield enriched
    }

  private def runMemoryClassifier(memory: ContextMemory,
                                   chain: List[sigil.participant.ParticipantId],
                                   modelId: Id[Model],
                                   accessibleSpaces: Set[SpaceId],
                                   recentUserMessage: Option[String]): Task[ContextMemory] = {
    val spaceCatalog =
      if (accessibleSpaces.isEmpty) "  (none — only global available)"
      else accessibleSpaces.toList.sortBy(_.value).map { s =>
        val desc = s.description.fold("")(d => s" — $d")
        s"  - value=\"${s.value}\" displayName=\"${s.displayName}\"$desc"
      }.mkString("\n")
    val rendered = renderMemoryForClassification(memory)
    val userMsgBlock = recentUserMessage match {
      case Some(text) => s"\n\nUser's recent message (the trigger for this save):\n$text"
      case None       => ""
    }
    val systemPrompt =
      """You classify a memory the framework is about to persist. Decide three things in one call:
        |
        |1. keywords (5-10 retrieval-shaped tokens)
        |2. permanence ("Once" or "Always") — based on imperative cues in the user's recent message
        |3. space (one accessible space `value` or "ambiguous") — most-specific applicable
        |
        |See the tool description for the rules. When unsure on permanence, default to "Once".
        |When unsure on space, output "ambiguous" and supply ambiguityReason.""".stripMargin
    val userPrompt =
      s"""Memory to classify:
         |$rendered
         |
         |Accessible spaces:
         |$spaceCatalog$userMsgBlock
         |
         |Return the classification.""".stripMargin
    val settings = {
      val base = sigil.provider.GenerationSettings(
        maxOutputTokens = Some(220),
        reasoningMode = sigil.provider.ReasoningMode.Off
      )
      if (supportsParameter(modelId, "temperature")) base.copy(temperature = Some(0.0))
      else base
    }
    sigil.tool.consult.ConsultTool.invoke[sigil.tool.consult.ClassifyMemoryInput](
      sigil = this,
      modelId = modelId,
      chain = chain,
      systemPrompt = systemPrompt,
      userPrompt = userPrompt,
      tool = sigil.tool.consult.ClassifyMemoryTool,
      generationSettings = settings
    ).map {
      case None => memory
      case Some(input) => applyClassifierOutput(memory, input, accessibleSpaces)
    }.handleError { e =>
      Task {
        scribe.warn(s"memory classification failed (${e.getClass.getSimpleName}: ${e.getMessage}) — persisting unclassified")
        memory
      }
    }
  }

  /** Apply classifier output to the memory record, respecting caller-set
    * fields. Unrecognised permanence falls back to keeping the caller's
    * value; ambiguous space leaves the caller's space intact and emits
    * a scribe warning (apps that want to surface ambiguity to the user
    * subscribe to the warning via their log infra, or pre-classify
    * explicitly via [[sigil.Sigil.classifyMemoryDecision]]). */
  private def applyClassifierOutput(memory: ContextMemory,
                                     input: sigil.tool.consult.ClassifyMemoryInput,
                                     accessibleSpaces: Set[SpaceId]): ContextMemory = {
    val cleanedKeywords = input.keywords.iterator.map(_.trim.toLowerCase).filter(_.nonEmpty).toVector.distinct
    val withKeywords = if (cleanedKeywords.isEmpty) memory else memory.copy(keywords = cleanedKeywords)

    val withPinned = input.permanence match {
      case sigil.conversation.Permanence.Always => withKeywords.copy(pinned = true)
      case sigil.conversation.Permanence.Once   => withKeywords  // keep caller's pinned value (default false)
    }

    val classifierSpace = input.space.trim
    val withSpace =
      if (classifierSpace.equalsIgnoreCase("ambiguous")) {
        val reason = input.ambiguityReason.getOrElse("(no reason supplied by classifier)")
        val keyOrId = if (memory.key.nonEmpty) memory.key else memory._id.value
        scribe.warn(
          s"memory classifier returned 'ambiguous' for memory key='$keyOrId' " +
            s"(fallback space='${memory.spaceId.value}'); reason: $reason"
        )
        withPinned
      }
      else if (memory.spaceId != GlobalSpace) withPinned  // caller picked explicitly
      else accessibleSpaces.find(_.value == classifierSpace) match {
        case Some(picked) => withPinned.copy(spaceId = picked)
        case None         => withPinned
      }

    withSpace
  }

  /** Look up the most recent non-agent message text in a conversation —
    * the LLM uses this to detect imperative cues. Returns None when no
    * conversation context is available. */
  private def recentUserMessageText(conversationId: Option[Id[Conversation]]): Task[Option[String]] =
    conversationId match {
      case None => Task.pure(None)
      case Some(convId) =>
        framesFor(convId).map { frames =>
          frames.reverseIterator.collectFirst {
            case t: sigil.conversation.ContextFrame.Text
              if !t.participantId.isInstanceOf[sigil.participant.AgentParticipantId] => t.content
          }
        }
    }

  /** Render a memory in a compact form for the classifier. */
  private def renderMemoryForClassification(memory: ContextMemory): String = {
    val sb = new StringBuilder
    sb.append(s"Label: ${memory.label}\n")
    memory.key.foreach(k => sb.append(s"Key: $k\n"))
    sb.append(s"Summary: ${memory.summary}\n")
    sb.append(s"Fact: ${memory.fact}")
    if (memory.keywords.nonEmpty) sb.append(s"\nExisting keywords: ${memory.keywords.mkString(", ")}")
    sb.toString
  }

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
        applyMessageCostToConversation(m)
      case _ => Task.unit
    }
  }

  /** Increment [[Conversation.cost]] for a settled [[Message]] whose
    * `modelId` is known to the [[sigil.cache.ModelRegistry]].
    *
    * Math: per-token pricing × token counts (USD). Cache miss or
    * `modelId = None` → no-op (the Message contributes zero). On a
    * non-zero delta, publishes a
    * [[sigil.signal.ConversationCostUpdated]] Notice carrying the new
    * running total + the per-Message delta. */
  private final def applyMessageCostToConversation(m: Message): Task[Unit] = {
    // Bug #91 — `findTolerant` lets a Message stamped with a bare id
    // (`gpt-5.5`) match a registry entry indexed by its prefixed id
    // (`openai/gpt-5.5`). Without it, every cost projection on a
    // bare-id Message silently misses and the conversation's running
    // total stays at zero.
    val deltaOpt: Option[BigDecimal] = m.modelId.flatMap { mid =>
      cache.findTolerant(mid).map { model =>
        val pricing = model.pricing
        val u = m.usage
        pricing.prompt * u.promptTokens + pricing.completion * u.completionTokens
      }
    }.filter(_ > 0)
    deltaOpt match {
      case None => Task.unit
      case Some(delta) =>
        withDB(_.conversations.transaction(_.modify(m.conversationId) {
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
                         userMessage: String): Task[TopicShiftResult] = {
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
    //
    // `maxOutputTokens = 512` — the classifier's actual output is a
    // single tool call with a one-field arg (≤ ~20 tokens). The
    // budget covers thinking models (Qwen3.6, DeepSeek-R1, o-series)
    // that burn output tokens on internal reasoning before emitting
    // the structured tool_call, even with `enable_thinking: false`
    // hints — some templates leak reasoning regardless. 50 tokens
    // (the prior default) cut these models off mid-think and the
    // classifier returned `finish_reason: length` with no tool call
    // → ConsultTool returned None → topic resolution silently
    // defaulted to NoChange.
    val classifierSettings = {
      val base = GenerationSettings(
        maxOutputTokens = Some(512),
        reasoningMode = sigil.provider.ReasoningMode.Off
      )
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
      }
    }.handleError { e =>
      Task {
        scribe.warn(s"classifyTopicShift failed (${e.getClass.getSimpleName}: ${e.getMessage}) — falling back to NoChange")
        TopicShiftResult.NoChange
      }
    }
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
   *   - [[sigil.tool.core.RespondTool.executeTyped]] for atomic
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
        classifyTopicShift(modelId, chain, currentTopic, previousTopics, proposedLabel, proposedSummary, userMessage).flatMap {
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
    // Event store has no conversationId index today, so use a
    // streaming `tx.stream.filter` rather than `tx.query.filter`.
    // For large event stores this is a full-table scan; the merge
    // is one-shot per import, so an index could be added later if
    // profiling shows it's load-bearing.
    val rewriteEvents: Task[Int] = withDB(_.events.transaction { tx =>
      val rewritten = tx.stream
        .filter(_.conversationId == staging)
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
               // No conversationId index on Event; stream-scan + filter.
               val ids = tx.stream.filter(_.conversationId == staging).map(_._id)
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
               tx.list.flatMap { all =>
                 val targets = all.filter(_.conversationId == conversationId)
                 Task.sequence(targets.map(e => tx.delete(e._id))).unit
               }
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
      _ <- withDB { db =>
             db.toolOutputs.transaction { tx =>
               tx.list.flatMap { all =>
                 val targets = all.filter(_.conversationId == conversationId)
                 Task.sequence(targets.map(n => tx.delete(n._id))).unit
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
    * claim. Generous default — the primary stuck-detection mechanism is the
    * delta-based progress checkpoint (see [[progressCheckpointInterval]] +
    * [[consecutiveNoProgressLimit]]), which fires well before this ceiling
    * for any real loop. The cap exists as a runaway-cost safety net for
    * pathological cases where the checkpoint itself misbehaves; reaching
    * it raises [[AgentRunawayException]] in the runAgent fiber after
    * releasing the AgentState claim. Apps tighten or relax per their
    * cost / latency tolerance. */
  protected def maxAgentIterations: Int = 200

  /** Iterations between progress checkpoints. Every Nth iteration the
    * framework runs an out-of-band reflection turn that compares the
    * current task state against the prior checkpoint's status and
    * decides whether to continue / intervene / ask the user. Default
    * 15 — long enough to amortise the extra LLM call across real
    * work, short enough to catch sustained loops within ~30
    * iterations. Set to 0 to disable checkpointing. */
  protected def progressCheckpointInterval: Int = 15

  /** Number of consecutive `meaningfulProgress = false` checkpoints
    * required before the framework intervenes with a synthetic
    * respond asking the user for guidance. Default 2. Setting to 1
    * is aggressive (any single "no progress" report stops the
    * loop); higher values give the agent more rope. */
  protected def consecutiveNoProgressLimit: Int = 2

  /** Cap on `discoveredCapabilities` entries surfaced in the
    * agent's prompt — keeps the prompt bounded even on long-running
    * conversations that have searched many queries. The cap is
    * over the *map* (one entry per distinct query); each entry's
    * matches list is already bounded by `find_capability`'s
    * page size. Apps override to tune the prompt budget. */
  def discoveredCapabilitiesPromptCap: Int = 25

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
      userVisibleSeen = new java.util.concurrent.atomic.AtomicBoolean(false),
      // Bug #149 — the per-turn memory extractor must fire exactly
      // once per user turn (not once per agent-loop iteration). This
      // flag is threaded through every recursion so a CAS at the
      // terminate path guarantees a single fire across the whole
      // loop.
      turnExtractorFired = new java.util.concurrent.atomic.AtomicBoolean(false)
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
                                 forceResponseSynthesis: Boolean = false): Task[Unit] = Task.defer {
    // Bug #149 — release the agent's claim AND fire the per-turn
    // memory extractor exactly once. The CAS-guard guarantees a
    // single extraction across every terminal exit path of the
    // loop (Stop, max-iterations, cap-hit, checkpoint intervention,
    // post-stream error). The extractor itself runs on a fiber
    // so the release path isn't blocked by its LLM round-trip.
    def terminate(): Task[Unit] = {
      if (turnExtractorFired.compareAndSet(false, true)) {
        firePostTurnExtraction(agent, convId, claimed.timestamp).startUnit()
      }
      // Sigil bug #169 — clear the suggestedTools overlay at loop release
      // so leftover suggestions don't bleed into the next user's turn.
      // Per-iteration decay was removed in the same bug fix; persistence
      // is loop-scoped and cleared exactly here.
      clearSuggestedTools(convId, agent.id).flatMap(_ => releaseClaim(claimed))
    }
    // Snapshot the start of THIS iteration. The next iteration uses this as
    // its own `sinceTimestamp`, so events emitted during this iteration
    // (including self-emitted non-terminal tool results the agent acted on)
    // don't re-appear as triggers next time.
    val thisIterationStart = Timestamp(Nowish())
    val stopFlag = Option(stopFlags.get(claimed._id))
    // Bug #74 — flips when a `respond` settles with `endsTurn = false`
    // (a progress / status update). The post-drain decision below
    // iterates the loop without waiting for new triggers, so the
    // agent picks up its own respond Message in the next iteration's
    // history and continues working. Per-iteration scope (the next
    // iteration starts with its own fresh AtomicBoolean).
    val agentRequestedContinue = new java.util.concurrent.atomic.AtomicBoolean(false)
    // Bug #57 — diagnostic logging at iteration boundaries so a
    // future repro of "agent parks at thinking" can be localised
    // by reading the server log for missing exit lines. The cost
    // of these scribe.debug calls is negligible compared to the
    // turn's actual work; volume is ~3 lines per iteration.
    scribe.info(s"runAgentLoop[${agent.id.value}/${convId.value}] iter=$iteration enter")
    // A Stop may have landed before this iteration even starts; short-
    // circuit if so (graceful = "don't start another iteration"; force
    // = "same, plus the in-flight stream below won't run"). Either way,
    // release and exit.
    if (stopFlag.exists(_.requested)) terminate()
    else
    // Reload the conversation each iteration — materialized projections
    // (currentMode, modified, etc.) update as Events flow through `publish`,
    // so the conversation we hand to the agent must reflect the latest state.
    withDB(_.conversations.transaction(_.get(convId))).flatMap {
      case None =>
        // Conversation deleted mid-turn — release the lock and exit cleanly.
        // Extractor isn't fired here — no conversation = nothing to extract.
        releaseClaim(claimed)
      case Some(conv) =>
        // Sigil bug #169 — overlay persists across iterations within the
        // same user turn. Prerequisite calls (`record_consent`, etc.) and
        // multi-invocation flows (`create_workflow` → `add_workflow_step` 5×)
        // keep their discovered tools in scope until the loop terminates
        // (handled in `terminate()` above) or a new `find_capability` /
        // suggestion-emitting tool result replaces the list.
        {
          scribe.info(s"runAgentLoop[${agent.id.value}/${convId.value}] iter=$iteration buildContext start")
          buildContext(agent, conv, sinceTimestamp = sinceTimestamp, claimedId = claimed._id, isGreeting = greeting && iteration == 1).flatMap {
            case (rawCtx, triggers) =>
              // Sigil bug #125 — propagate the cap-hit soft-stop flag
              // through the TurnContext so runAgentTurn → ConversationRequest →
              // Provider's tool_choice all reflect it.
              val ctx = if (forceResponseSynthesis) rawCtx.copy(forceResponseSynthesis = true) else rawCtx
              scribe.info(s"runAgentLoop[${agent.id.value}/${convId.value}] iter=$iteration buildContext done; dispatching agent.process")
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
                    Task { activeUserVisibleInvokes.put(ti._id, ti.toolName.value); () }
                  case td: ToolDelta if td.state.contains(EventState.Complete)
                                     && activeUserVisibleInvokes.containsKey(td.target) =>
                    Task {
                      userVisibleSeen.set(true)
                      // Bug #74 — `respond(endsTurn = false)` keeps the
                      // turn open. The settled delta carries the parsed
                      // input; flip the continue flag when it's a
                      // RespondInput with endsTurn = false. Anything
                      // else (the other respond_* tools, no_response,
                      // unparseable input) leaves the flag false and
                      // the loop falls through to its normal end-of-
                      // turn check.
                      val toolName = activeUserVisibleInvokes.get(td.target)
                      if (toolName == "respond") {
                        td.input match {
                          case Some(r: sigil.tool.model.RespondInput) if !r.endsTurn =>
                            agentRequestedContinue.set(true)
                          case _ => ()
                        }
                      }
                      ()
                    }
                  case _ => Task.unit
                }
                .evalTap(publish)
                .drain
          }
        }.flatMap { _ =>
          scribe.info(s"runAgentLoop[${agent.id.value}/${convId.value}] iter=$iteration drain done")
          // After the iteration drains, check stop flags before anything
          // else — a Stop that fired mid-stream means exit now, don't
          // continue looping even if there are new triggers.
          if (stopFlag.exists(_.requested))
            ensureSilentTurnReply(agent, convId, userVisibleSeen).flatMap(_ => terminate())
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
              terminate()
            else
              terminate().flatMap(_ =>
                Task.error(new AgentRunawayException(
                  s"Agent ${agent.id.value} hit maxAgentIterations ($maxAgentIterations) " +
                    s"in conversation ${conv._id.value} AND the forced-synthesis turn also " +
                    s"failed to call `respond`. Check LLM behavior or raise the cap."))
              )
          }
          else {
            // Bug #74 — `respond(endsTurn = false)` continues the
            // loop without waiting for an external trigger. The
            // agent's own progress respond IS the signal to keep
            // going; the next iteration will see it in history and
            // proceed with the announced work.
            val shouldIterate: Task[Boolean] =
              if (agentRequestedContinue.get()) Task.pure(true)
              else newTriggersExist(agent, conv, sinceTimestamp = thisIterationStart)
            shouldIterate.flatMap {
            case true if iteration < maxAgentIterations =>
              // Bug #54 — emit per-iteration boundary state. Without
              // these, a multi-iteration agent loop pins the
              // consumer's state at `typing` (or whatever the last
              // streaming activity was) for the whole outer-loop
              // duration. Clients then can't render an accurate
              // Stop button or per-turn UX.
              //
              // The pulses don't change the AgentState event's
              // `state` (still Active — claim still held) — they
              // mutate `activity` only, so the framework's claim-
              // lock semantics are preserved. The next iteration
              // runs in the same outer fiber.
              publish(AgentStateDelta(
                target = claimed._id,
                conversationId = convId,
                activity = Some(AgentActivity.Idle)
              )).flatMap(_ =>
                publish(AgentStateDelta(
                  target = claimed._id,
                  conversationId = convId,
                  activity = Some(AgentActivity.Thinking)
                ))
              ).flatMap { _ =>
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
                  if (progressCheckpointInterval > 0 && nextIteration % progressCheckpointInterval == 0)
                    runProgressCheckpoint(agent, convId, claimed, nextIteration)
                  else
                    Task.pure(None)
                checkpointTask.flatMap {
                  case Some(intervention) if intervention.askingUser =>
                    // Genuine "agent needs user input to proceed" —
                    // publish user-visible and release the claim. The
                    // agent can't make progress without the user's
                    // reply; the next user Message will re-trigger.
                    publish(intervention.message).flatMap(_ => terminate())
                  case Some(intervention) =>
                    // Bug #133 — stall / no-progress streak. The
                    // intervention text is a directive to the agent
                    // ("Stop gathering and call respond"). Publish
                    // as Tool-role (Agents visibility) under a
                    // synthetic `_stall_detected` parent invoke so
                    // the agent reads it on its next iteration but
                    // the user doesn't see the raw directive. Then
                    // run ONE forced-synthesis iteration so the
                    // agent actually responds rather than going
                    // silent. Same shape as #125's cap-hit.
                    val syntheticInvokeId = Event.id()
                    val syntheticInvoke = sigil.event.ToolInvoke(
                      toolName       = sigil.tool.ToolName("_stall_detected"),
                      participantId  = agent.id,
                      conversationId = convId,
                      topicId        = conv.currentTopicId,
                      _id            = syntheticInvokeId,
                      state          = EventState.Complete,
                      internal       = true
                    )
                    val taggedDirective = intervention.message.copy(
                      role       = MessageRole.Tool,
                      visibility = MessageVisibility.Agents,
                      origin     = Some(syntheticInvokeId)
                    )
                    publish(syntheticInvoke)
                      .flatMap(_ => publish(taggedDirective))
                      .flatMap { _ =>
                        runAgentLoop(
                          agent                  = agent,
                          convId                 = convId,
                          claimed                = claimed,
                          iteration              = nextIteration,
                          sinceTimestamp         = thisIterationStart,
                          greeting               = false,
                          userVisibleSeen        = userVisibleSeen,
                          turnExtractorFired     = turnExtractorFired,
                          forceResponseSynthesis = true
                        )
                      }
                  case None =>
                    runAgentLoop(agent, convId, claimed, nextIteration, thisIterationStart,
                      userVisibleSeen = userVisibleSeen, turnExtractorFired = turnExtractorFired)
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
              val capInvokeId = Event.id()
              val capInvoke = sigil.event.ToolInvoke(
                toolName       = sigil.tool.ToolName("_cap_reached"),
                participantId  = agent.id,
                conversationId = convId,
                topicId        = conv.currentTopicId,
                _id            = capInvokeId,
                state          = EventState.Complete,
                internal       = true
              )
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
                origin         = Some(capInvokeId)
              )
              publish(capInvoke).flatMap(_ => publish(capDiagnostic)).flatMap { _ =>
                // Bug #128 composition — when `escalateOnCapHit` is on,
                // bump the cached complexity tier one step up before
                // the forced-synthesis turn. The recovery attempt then
                // resolves to whichever model in the chain supports
                // the elevated tier. No-op when the flag is off.
                escalateForCapHit(convId).flatMap(_ =>
                  runAgentLoop(
                    agent                  = agent,
                    convId                 = convId,
                    claimed                = claimed,
                    iteration              = iteration + 1,
                    sinceTimestamp         = thisIterationStart,
                    greeting               = false,
                    userVisibleSeen        = userVisibleSeen,
                    turnExtractorFired     = turnExtractorFired,
                    forceResponseSynthesis = true
                  )
                )
              }
            case true =>
              // Cap hit on the forced-synthesis iteration too. The model
              // failed to call `respond` despite `tool_choice` pinning it
              // (very weak / non-instruction-following local models, or
              // a buggy provider). Soft path exhausted — surface the hard
              // failure so the calling fiber's error boundary logs it.
              ensureSilentTurnReply(agent, convId, userVisibleSeen).flatMap(_ =>
                terminate().flatMap(_ =>
                  Task.error(new AgentRunawayException(
                    s"Agent ${agent.id.value} hit maxAgentIterations ($maxAgentIterations) " +
                      s"in conversation ${conv._id.value} AND the forced-synthesis turn also " +
                      s"failed to call `respond`. Check LLM behavior or raise the cap."))))
            case false =>
              ensureSilentTurnReply(agent, convId, userVisibleSeen).flatMap(_ => terminate())
            }
          }
        }
    }.handleError { t =>
      // Any unhandled failure mid-turn — surface the failure to the
      // user so the chat doesn't go silent (Bug #6), then release the
      // lock so the agent isn't stuck Active forever, then re-raise
      // so the fiber's error boundary logs it. Each step is
      // independently best-effort: a downstream failure (DB
      // unavailable, hub closed, missing topic, etc.) doesn't mask
      // the original error.
      scribe.error(s"runAgent failed for ${agent.id.value} in ${convId.value}", t)
      publishFailureMessage(agent, convId, t).handleError(_ => Task.unit)
        .flatMap(_ => terminate().handleError(_ => Task.unit))
        .flatMap(_ => Task.error(t))
    }
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
    withDB(_.events.transaction(_.list)).flatMap { all =>
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
        sigil.tool.consult.ConsultTool.invoke[sigil.tool.consult.ProgressReflectionInput](
        sigil              = this,
        modelId            = agent.modelId,
        chain              = List(agent.id),
        systemPrompt       = systemPrompt,
        userPrompt         = userPrompt,
        tool               = sigil.tool.consult.ProgressReflectionTool,
        generationSettings = sigil.provider.GenerationSettings(
          maxOutputTokens = Some(200),
          reasoningMode = sigil.provider.ReasoningMode.Off
        )
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

  /** Load the context the reflection prompt needs: the user's most
    * recent substantive Message + the agent's tool-call history
    * since that message. Best-effort — failures fall through to
    * empty context rather than aborting the checkpoint. */
  private final def loadProgressContext(convId: Id[Conversation],
                                        agentId: ParticipantId): Task[ProgressContext] =
    withDB(_.events.transaction(_.list)).map { all =>
      val convEvents = all.iterator
        .collect { case e: Event if e.conversationId == convId => e }
        .toList
        .sortBy(_.timestamp.value)
      // The most recent user Message — non-agent participant, Standard role.
      val userMsg = convEvents.reverseIterator.collectFirst {
        case m: Message
          if !m.participantId.isInstanceOf[sigil.participant.AgentParticipantId] &&
             m.role == MessageRole.Standard &&
             m.content.nonEmpty =>
          m
      }
      val task: Option[String] = userMsg.map(m => textOfContent(m.content))
      // Tool calls + agent responds since the user message.
      val cutoff = userMsg.map(_.timestamp.value).getOrElse(0L)
      val historyEntries = scala.collection.mutable.ListBuffer.empty[String]
      // Group by callId so a ToolInvoke + ToolResults pair render as one line.
      val invokesById = convEvents.collect {
        case ti: sigil.event.ToolInvoke if ti.timestamp.value > cutoff && ti.participantId == agentId => ti
      }
      val resultsByOrigin = convEvents.collect {
        case tr: sigil.event.ToolResults if tr.timestamp.value > cutoff && tr.origin.isDefined => tr.origin.get -> tr
      }.toMap
      val sortedInvokes = invokesById.sortBy(_.timestamp.value).take(20)  // cap the history
      sortedInvokes.foreach { ti =>
        val tail = resultsByOrigin.get(ti._id) match {
          case Some(_) => "OK"
          case None    => "(no result yet)"
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
      ProgressContext(userTask = task, toolHistory = historyEntries.toList)
    }.handleError(_ => Task.pure(ProgressContext(None, Nil)))

  /** Evaluate the agent's recent tool-call tail for objective stall
    * signals — identical-call streaks and empty-payload streaks.
    * Folds into the progress checkpoint's `meaningfulProgress`
    * computation. Best-effort: failures fall through to the empty
    * signal rather than aborting the checkpoint. */
  private final def evaluateStall(convId: Id[Conversation],
                                  agentId: ParticipantId): Task[sigil.conversation.compression.StallDetector.Signal] =
    withDB(_.events.transaction(_.list)).map { all =>
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
      val resultsByOrigin = convEvents.collect {
        case tr: sigil.event.ToolResults if tr.origin.isDefined => tr.origin.get -> tr
      }.toMap
      val messagesByOrigin = convEvents.collect {
        case m: Message if m.role == MessageRole.Tool && m.origin.isDefined => m.origin.get -> m
      }.toMap
      val records = invokes.sortBy(_.timestamp.value).map { ti =>
        sigil.conversation.compression.StallDetector.CallRecord(
          invoke        = ti,
          result        = resultsByOrigin.get(ti._id),
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
      case Some(t) => s"The user's request:\n\"$t\"\n\n"
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
        s"shouldAskUser = true ONLY if the user must clarify something."
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
          publish(Message(
            participantId  = agent.id,
            conversationId = convId,
            topicId        = topic.id,
            content        = Vector(sigil.tool.model.ResponseContent.Text(reason)),
            disposition    = sigil.event.MessageDisposition.Failure(
              recoverable  = false,
              errorContext = Some(ec)
            ),
            state          = EventState.Complete,
            role           = MessageRole.Standard
          )).map(_ => ())
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
            // Recover the model that actually ran this turn — the most
            // recent RouteResolved's `chosenModelId` — so the placeholder
            // attributes its content to the right model rather than the
            // agent's static default.
            withDB(_.events.transaction(_.list)).map { evs =>
              evs.reverseIterator
                .collectFirst {
                  case rr: sigil.event.RouteResolved
                    if rr.conversationId == convId => rr.chosenModelId
                }
                .getOrElse(agent.modelId)
            }.flatMap { resolvedModelId =>
              publish(Message(
                participantId  = agent.id,
                conversationId = convId,
                topicId        = topic.id,
                content        = Vector(sigil.tool.model.ResponseContent.Text("(agent completed without a reply)")),
                disposition    = sigil.event.MessageDisposition.Failure(recoverable = true),
                state          = EventState.Complete,
                modelId        = Some(resolvedModelId)
              )).map(_ => ())
            }
        }
    }

  /** Clear the `suggestedTools` overlay at loop release. Sigil bug
    * #169 — the overlay persists across iterations within a single
    * user turn (so prerequisite-call flows and multi-invocation
    * progression flows keep their discovered tools in scope) but is
    * cleared here when the loop terminates so suggestions don't bleed
    * into the next user's turn. Single chokepoint covers every release
    * path. */
  private final def clearSuggestedTools(conversationId: Id[Conversation],
                                         agentId: ParticipantId): Task[Unit] =
    projectionFor(agentId, conversationId).flatMap { proj =>
      if (proj.suggestedTools.nonEmpty)
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
                                 isGreeting: Boolean = false): Task[(TurnContext, Stream[Event])] =
    for {
      triggerEvents <- withDB(_.events.transaction(_.list)).map { all =>
        all.view
          .filter(e => e.conversationId == conv._id
                    && e.timestamp.value > sinceTimestamp.value
                    && TriggerFilter.isTriggerFor(agent, e)
                    && visibilityAllows(e.visibility, agent.id))
          .toList
      }
      chain = buildChain(triggerEvents, agent)
      input <- curate(conv._id, agent.modelId, chain)
    } yield {
      val triggers: Stream[Event] = Stream.emits(triggerEvents)
      val ctx = TurnContext(
        sigil = this,
        chain = chain,
        conversation = conv,
        turnInput = input,
        currentAgentStateId = Some(claimedId),
        isGreeting = isGreeting
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
      _ = sigil.provider.WorkType.register(workTypes.map(w => RW.static(w))*)
      // Bug #53 — `toolInputRegistrations` is the mixin extension
      // point for non-static tools whose `inputRW` isn't reachable
      // through the static-roster scan (notably `JsonInput`, used by
      // `ScriptTool` and `McpTool`). Without including it here,
      // `ScriptSigil` / `McpSigil` apps would crash at the first
      // runtime tool's `ToolInvoke` persistence with `Type not found
      // [JsonInput]`.
      _ = ToolInput.register((CoreTools.inputRWs ++ findTools.toolInputRWs ++ toolInputRegistrations).distinctBy(_.definition.className)*)
      _ = sigil.viewer.ViewerStatePayload.register(viewerStatePayloadRegistrations.distinct*)
      // Mixin hook — runs AFTER all framework leaf polytypes register but
      // BEFORE the aggregates that walk Participant/Tool/Signal definitions.
      // Mixins that register polytypes whose subtype RWs reach into framework
      // leaves (e.g. WorkflowSigil's WorkflowStepInput → AgentDecisionStepInput
      // → Role → WorkType) MUST run here, not at trait-init time — otherwise
      // the subtype lazy-val Definitions cache empty leaf-poly snapshots and
      // downstream codegen sees empty dispatchers despite the leaf register
      // calls succeeding (sigil bug #18).
      _ <- mixinPolymorphicRegistrations
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
          new sigil.tool.StaticToolSyncUpgrade(staticTools),
          new sigil.skill.StaticSkillSyncUpgrade(staticSkills)
        )
      )
      _ <- db.init
      _ <- reconcileStaleActiveEvents(db)
      _ <- if (vectorWired) vectorIndex.ensureCollection(embeddingProvider.dimensions)
           else Task.unit
      _ <- cache.loadFromDisk
      _ <- validateModeSkillSizes()
      _ <- startModelRefresh()
      _ <- startExpiredMemorySweep()
      _ <- startMaintenanceTasks()
    } yield SigilInstance(
      config = config,
      db = db
    )
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
      sigil.maintenance.ToolOutputExpirationSweep(toolOutputExpirationInterval),
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

  /** Cadence for [[sigil.maintenance.ToolOutputExpirationSweep]] —
    * how often the framework reclaims expired
    * [[sigil.tool.output.ToolOutputNode]] rows. Default: 15
    * minutes. The default per-row TTL is 30 minutes (set on
    * [[sigil.tool.output.PaginatedTool.rowTtl]]) so the sweep
    * runs about twice per TTL window — fine grained enough that
    * reclaimed storage doesn't grow unboundedly between sweeps. */
  def toolOutputExpirationInterval: scala.concurrent.duration.FiniteDuration =
    scala.concurrent.duration.DurationInt(15).minutes

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
    Task.pure(Nil)

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
    Task.pure(Nil)

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

object Sigil {
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
