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
 * Capability-discovery cluster — the `find_capability` surface and
 * the roster composition that decides what a turn is allowed to call.
 * Owns the tool / memory / skill / mode discovery legs, the relevance
 * ranking knobs those legs share, space authorization, per-conversation
 * tool overlays, and [[Sigil.effectiveToolNames]].
 *
 * Mixed into [[Sigil]]; the self-type reaches `findTools`'s backing
 * finder, `withDB`, `searchMemories`, `resolvedStaticTools`, and the
 * mode registry.
 */
trait DiscoveryOps { this: Sigil =>

  /**
   * Capability-discovery finder. Default queries [[sigil.db.SigilDB.tools]]
   * via [[sigil.tool.DbToolFinder]] — apps override only when they
   * need a custom finder (marketplace union, in-memory test catalog,
   * etc.).
   */
  def findTools: sigil.tool.ToolFinder = defaultFindTools

  private final lazy val defaultFindTools: sigil.tool.ToolFinder = sigil.tool.DbToolFinder(this)

  /** Conversation-aware exact-name tool resolution: the conversation's
    * live client-registered tools first (registration rejects
    * server-tool collisions, so this shadows nothing), then the app's
    * [[findTools]] catalog. The roster-build path resolves through
    * here so a discovered client tool actually reaches the wire
    * roster; framework paths without a conversation in hand
    * (workflow steps, curator internals) keep using
    * `findTools.byName` — client tools are conversation-scoped and
    * genuinely don't exist there. */
  final def resolveToolFor(conversationId: Id[Conversation], name: sigil.tool.ToolName): Task[Option[sigil.tool.Tool]] =
    clientTools.byName(conversationId, name) match {
      case some @ Some(_) => Task.pure(some)
      case None           => findTools.byName(name)
    }

  /** Skill discovery finder. Default queries [[sigil.db.SigilDB.skills]]
    * via [[sigil.skill.DbSkillFinder]] (BM25 over `searchText`,
    * mode-scoped post-filter). Apps override for custom skill catalogs. */
  def findSkills(request: sigil.tool.DiscoveryRequest): rapid.Task[List[sigil.skill.Skill]] =
    sigil.skill.DbSkillFinder(this).apply(request)

  /**
   * The [[sigil.skill.Skill.alwaysOn]] skills that apply to
   * `conversation`, materialized as render-ready
   * [[sigil.conversation.ActiveSkillSlot]]s: enabled, always-on, space
   * matching the conversation's space (or [[GlobalSpace]]), and mode
   * matching the conversation's current mode (or mode-unrestricted).
   *
   * Queried fresh at every turn build — no per-conversation activation
   * state exists, so registering a new always-on skill or editing an
   * existing one reaches every conversation in the space on its next
   * iteration, and disabling one withdraws it just as immediately.
   */
  final def alwaysOnSkillsFor(conversation: Conversation): Task[Vector[sigil.conversation.ActiveSkillSlot]] =
    withDB(_.skills.transaction(_.query.filter(_.alwaysOnIndex === true).toList)).map { skills =>
      skills.iterator
        .filter { s =>
          s.enabled &&
            (s.space == conversation.space || s.space == GlobalSpace) &&
            (s.modes.isEmpty || s.modes.contains(conversation.currentMode.id))
        }
        .map(s => sigil.conversation.ActiveSkillSlot(s.name, s.content))
        .toVector
    }

  /** Maximum number of memory matches surfaced by [[findCapabilitiesMemories]].
    * Memory catalogs grow large; an aggressive cap keeps `find_capability`
    * results focused. */
  def findCapabilitiesMemoriesMaxResults: Int = 10

  /** Memory discovery for `find_capability`. BM25 search over the
    * [[sigil.conversation.ContextMemory]] `searchText` index. Space
    * affinity (`GlobalSpace` plus the caller's accessible spaces) and
    * the `Approved` status are compiled into the Lucene query so the
    * scope applies BEFORE the relevance cut — a large multi-tenant
    * store can't crowd the caller's own matches out of the candidate
    * window. The rest of the recall gate
    * ([[sigil.conversation.ContextMemory.isRecallable]] — current
    * version + expiry) is applied on the small result. Returns the top
    * [[findCapabilitiesMemoriesMaxResults]] hits, each as a
    * (memory, BM25 score) pair. Apps override for vector / hybrid
    * scoring or alternate filters. */
  def findCapabilitiesMemories(request: sigil.tool.DiscoveryRequest): rapid.Task[List[(sigil.conversation.ContextMemory, Double)]] = {
    import lightdb.Sort
    import lightdb.filter.*
    val tokens = request.keywords.toLowerCase.split("\\s+").filter(_.nonEmpty).toList
    val spaces = request.callerSpaces + GlobalSpace
    if (tokens.isEmpty) Task.pure(Nil)
    else withDB(_.memories.transaction { tx =>
      tx.query
        .filter { _ =>
          val keywordClauses = tokens.map { kw =>
            FilterClause(ContextMemory.searchText.exactly(kw), Condition.Should, None)
          }
          val spaceClauses = spaces.toList.map { space =>
            FilterClause(ContextMemory.spaceIdValue === space.value, Condition.Should, None)
          }
          Filter.Multi(minShould = 1, filters = keywordClauses) &&
            Filter.Multi(minShould = 1, filters = spaceClauses) &&
            (ContextMemory.statusName === MemoryStatus.Approved.toString)
        }
        .scored
        .sort(Sort.BestMatch())
        .limit(findCapabilitiesMemoriesMaxResults * 2)
        .toList
    }).map { memories =>
      val now = Timestamp()
      memories
        .filter(_.isRecallable(now))
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
      foundTools       <- findTools(request)
      overlayPolicies  <- overlayPoliciesTask
      // UI-registered tools join discovery for their own conversation
      // only, keyword-filtered like any catalog tool. They are
      // in-memory, so the union happens here rather than in the
      // (possibly DB-backed) finder.
      clientMatches     = request.conversationId
        .map(clientTools.toolsFor)
        .getOrElse(Nil)
        .filter(t => sigil.tool.DiscoveryFilter.score(t, request.keywords) > 0.0 ||
          t.name.value.equalsIgnoreCase(request.keywords.trim))
      rawTools          = foundTools ++ clientMatches.filterNot(c => foundTools.exists(_.name == c.name))
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
          // An always-on skill returned by the finder is in scope (the
          // finder already applied space + mode affinity) and therefore
          // ALREADY in this conversation's prompt — advertising an
          // `activate_skill` step for it would send the agent on a
          // pointless round-trip.
          status =
            if (s.alwaysOn) CapabilityStatus.Ready
            else CapabilityStatus.RequiresSetup(s"""activate_skill("${s.name}")""")
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
      // Relevance trim — drop tool matches scoring far below the best tool
      // match. `DiscoveryFilter.score` weights a generic keyword ("search",
      // "find") the same as a distinctive one, so a code-search query like
      // "grep search find text pattern match" otherwise surfaces
      // conversation/semantic-search tools that matched ONLY the generic
      // words (grep=57, glob=25 vs search_conversation=20, semantic_search=20).
      // Those noise tools land in the prompt's "Suggested tools" / discovered
      // sections and steer the model toward irrelevant calls (the
      // `search_conversation` runaway). Always keep the best tool; require the
      // rest to clear `discoveryRelevanceFloor` × top so the agent's discovery
      // and the rendered suggestions stay on-task.
      val rankedTools = toolMatches.sortBy(-_.score)
      val relevantTools = rankedTools match {
        case top :: rest if top.score > 0.0 =>
          top :: rest.filter(_.score >= top.score * discoveryRelevanceFloor)
        case other => other
      }
      (relevantTools ++ modeMatches ++ skillMatches ++ memoryMatches).sortBy(-_.score)
    }
  }

  /** Relevance floor for [[findCapabilities]] tool matches: a tool below
    * `discoveryRelevanceFloor × topScore` is dropped (the best tool is always
    * kept). Keeps `find_capability` results and the prompt's "Suggested tools"
    * on-task instead of surfacing tools that matched only generic query words.
    * Apps that want the full ranked roster set this to `0.0`. */
  def discoveryRelevanceFloor: Double = 0.4

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
    * score.
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
    * tune by override. */
  def toolchainBoost: Double = 10.0

  /** Score penalty subtracted from a tool's [[findCapabilities]]
    * result when [[sigil.tool.Tool.preferIfNoBetter]] is set.
    * Generic primitives (grep, glob, bash, …) get nudged below
    * domain-specific tools that ranker score them as ties. Default
    * `3.0` — large enough to push grep below LSP for "examine code"
    * queries, small enough that a generic-only match still ranks
    * positive (no domain match → grep is still the top result). */
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
                         recentlyUsedTools: Set[sigil.tool.ToolName] = Set.empty,
                         /** UI-registered client tools for the turn's
                           * conversation. Unioned into the policy fold's
                           * extras — the semantics of an explicit
                           * `ToolPolicy.Active(names)` overlay — so an
                           * explicit registration takes effect without a
                           * `find_capability` round-trip, on every host
                           * including discovery-suppressed ones. */
                         clientToolNames: List[sigil.tool.ToolName] = Nil): List[sigil.tool.ToolName] = {
    import sigil.tool.core.{
      ChangeModeTool, FindCapabilityTool, NoResponseTool, RespondTool, RespondOptionsTool
    }
    import sigil.tool.skill.ActivateSkillTool
    // Reply surface: `respond` (markdown + Field-callout + H2-Card +
    // disposition) for telling; `respond_options` (typed) for asking.
    // The standalone `respond_card` / `respond_cards` tools are opt-in
    // (not essentials) — markdown callouts and disposition cover
    // their cases in `respond`.
    // `no_response` dropped from defaults.
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
    val initial = PolicyState(clientToolNames, includesFindCapability = true, includesBaseline = true, pureDiscovery = false)

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
    val afterDiscovery =
      if (state.pureDiscovery) {
        // Strip the entire respond family + no_response so the agent
        // can only reach a reply through discovery. The legacy
        // standalone tools stay in
        // the strip set so apps that opted back into them retain the
        // same pure-discovery semantics.
        val stripped: Set[sigil.tool.ToolName] =
          Set(RespondTool, RespondOptionsTool, NoResponseTool).map(_.schema.name)
        merged.filterNot(stripped.contains)
      } else merged
    // Sigil #388 — `includesFindCapability = false` (ToolPolicy.ActiveOnly /
    // ToolPolicy.None) means "ensure find_capability is ABSENT", not merely
    // "don't ADD it". It can still arrive via baseline (`agent.toolNames`),
    // extras (the policy's own `names`), or `suggested` — e.g. an app whose
    // roster is built from `CoreTools.coreToolNames`, which includes
    // find_capability. Strip it here as a final filter so the documented
    // suppression holds regardless of which channel surfaced it.
    val deduped =
      if (state.includesFindCapability) afterDiscovery
      else afterDiscovery.filterNot(_ == FindCapabilityTool.schema.name)
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
      // catch-all "telling" tool.
      RespondOptionsTool.schema.name    -> 101,
      RespondTool.schema.name           -> 102,
      NoResponseTool.schema.name        -> 105
    ): @annotation.nowarn("cat=deprecation")).withDefaultValue(50)
    deduped.zipWithIndex.sortBy { case (name, idx) => (priority(name), idx) }.map(_._1)
  }
}
