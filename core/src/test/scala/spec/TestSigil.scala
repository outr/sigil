package spec

import fabric.*
import fabric.rw.*
import lightdb.id.Id
import profig.Profig
import rapid.{Stream, Task}
import sigil.event.Event
import sigil.{Sigil, TurnContext}
import sigil.conversation.{ActiveSkillSlot, Conversation, ConversationStatus, Topic, TopicEntry, TurnInput}
import sigil.SpaceId
import sigil.conversation.compression.extract.{MemoryExtractor, StandardMemoryExtractor}
import sigil.db.{Model, ModelArchitecture, ModelLinks, ModelPricing, ModelTopProvider}
import sigil.embedding.{EmbeddingProvider, NoOpEmbeddingProvider}
import sigil.information.{InMemoryInformation, Information}
import sigil.participant.{AgentParticipantId, Participant, ParticipantId}
import sigil.provider.{ContextFeature, CurrentDateFeature, FeatureId, Mode, Provider}
import sigil.signal.Signal
import sigil.spatial.{Geocoder, NoOpGeocoder, Place}
import sigil.tool.{InMemoryToolFinder, Resolution, Tool, ToolContext, ToolFinder, ToolIO, ToolInput}
import sigil.tool.core.CoreTools
import sigil.vector.{NoOpVectorIndex, VectorIndex}
import spice.net.*

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.*

/**
 * Shared test Sigil — one singleton reused across every spec to avoid
 * RocksDB lock contention (multiple Sigils opening the same DB path
 * can't hold the lock simultaneously) and to centralize all test
 * fixtures (tool catalog, synthetic tools, well-known participants,
 * well-known memory spaces).
 *
 * All optional hooks from [[sigil.Sigil]] are overridden here with
 * safe no-op defaults, backed by [[AtomicReference]]s so specs can
 * swap behavior per-test via the `set*` methods. Call [[reset]] at
 * the top of each test (or in `beforeEach`) to restore the defaults.
 *
 * `testMode = true` so any side-effectful tools the tests reach can
 * opt for stub responses via `context.sigil.testMode`.
 */
object TestSigil extends Sigil {
  override type DB = sigil.db.DefaultSigilDB
  override protected def buildDB(directory: Option[java.nio.file.Path],
                                 storeManager: lightdb.store.CollectionManager,
                                 appUpgrades: List[lightdb.upgrade.DatabaseUpgrade]): DB =
    new sigil.db.DefaultSigilDB(directory, storeManager, appUpgrades)

  override def testMode: Boolean = true

  /**
   * The instant every spec's prompt reads as "now" — Saturday, March
   * 14, 2026, 15:09 UTC. A mid-day instant so no timezone arithmetic in
   * a spec can land it on an adjacent day, and a date that is neither
   * today nor plausibly today, so a rendered prompt that somehow picked
   * up the host clock reads as obviously wrong rather than passing.
   *
   * Pinning it is what makes rendered prompts byte-deterministic across
   * runs: the date rides the system prompt, and the system prompt is
   * part of the recorded-fixture cache key, so a live clock would mint a
   * new key every minute and no fixture would ever replay.
   */
  val PinnedInstant: java.time.Instant = java.time.Instant.parse("2026-03-14T15:09:00Z")
  val PinnedClock: java.time.Clock = java.time.Clock.fixed(PinnedInstant, java.time.ZoneOffset.UTC)

  /** The features every spec renders with, unless it swaps them. */
  private val DefaultContextFeatures: List[ContextFeature] = List(CurrentDateFeature(PinnedClock))

  private val healingModeRef =
    new java.util.concurrent.atomic.AtomicReference[sigil.heal.HealingMode](sigil.heal.HealingMode.Strict)
  override def healingMode: sigil.heal.HealingMode = healingModeRef.get()
  def setHealingMode(mode: sigil.heal.HealingMode): Unit = healingModeRef.set(mode)
  def resetHealingMode(): Unit = healingModeRef.set(sigil.heal.HealingMode.Strict)

  override def loadOpenRouterModels: Boolean = false

  /**
   * Host the LlamaCpp test specs hit. Resolution order:
   *   1. `SIGIL_LLAMACPP_HOST` env var IF that host accepts a TCP
   *      connection within the probe window — lets local-dev runs
   *      against a developer's own llama.cpp server (the default
   *      `/etc/environment` setting on the maintainer's machine).
   *   2. `sigil.llamacpp.host` profig key when set.
   *   3. The framework's stable public test endpoint at
   *      `https://llama.voidcraft.ai`.
   *
   * The fallback (step 3) keeps `test-all.sh` working on any
   * developer's machine or CI box, regardless of whether a local
   * llama.cpp happens to be running.
   */
  lazy val llamaCppHost: URL = {
    val configured: Option[URL] = sys.env.get("SIGIL_LLAMACPP_HOST")
      .flatMap(URL.get(_).toOption)
      .orElse {
        val path = Profig("sigil.llamacpp.host")
        if (path.exists()) scala.util.Try(path.as[URL]).toOption else None
      }
    val voidcraft: URL = url"https://llama.voidcraft.ai"
    configured match {
      case Some(host) if hostReachable(host) => host
      case Some(stale) =>
        scribe.warn(s"TestSigil.llamaCppHost: configured host $stale is unreachable; falling back to $voidcraft")
        voidcraft
      case None => voidcraft
    }
  }

  /**
   * Open a TCP socket to `url`'s host:port with a 1-second timeout.
   * Used to fast-skip `SIGIL_LLAMACPP_HOST` overrides that point at a
   * stale local server so test runs don't wait for okhttp's default
   * connection timeout × N tests.
   */
  private def hostReachable(url: URL): Boolean = {
    val port = url.port
    val socket = new java.net.Socket()
    try {
      socket.connect(new java.net.InetSocketAddress(url.host, port), 1000)
      true
    } catch {
      case _: Throwable => false
    } finally
      scala.util.Try(socket.close())
  }

  /**
   * Per-conversation workspace overrides for tests that exercise
   * `workspaceFor`-aware code paths (filesystem tools, Metals
   * routing, etc.). Tests call [[setWorkspace]] before exercising
   * the code path; the framework default returns `None`.
   */
  private val workspaceOverrides: java.util.concurrent.ConcurrentHashMap[
    lightdb.id.Id[sigil.conversation.Conversation],
    java.nio.file.Path
  ] = new java.util.concurrent.ConcurrentHashMap()

  def setWorkspace(conversationId: lightdb.id.Id[sigil.conversation.Conversation], path: Option[java.nio.file.Path]): Unit = path match {
    case Some(p) => workspaceOverrides.put(conversationId, p); ()
    case None => workspaceOverrides.remove(conversationId); ()
  }

  /**
   * Poll until the agent's self-loop for `convId` has released its claim — the
   * `agentlock:` AgentState event settles to `Complete` when the loop's
   * `releaseClaim` runs, a deterministic "the loop finished" signal.
   * Falls through after `timeout` so a turn that never claims doesn't hang.
   */
  def awaitSettled(convId: lightdb.id.Id[sigil.conversation.Conversation],
                   agentId: AgentParticipantId = TestAgent,
                   timeout: FiniteDuration = 15.seconds): Task[Unit] = {
    val lockId = Id[Event](s"agentlock:${agentId.value}:${convId.value}")
    val deadline = System.currentTimeMillis() + timeout.toMillis
    def loop: Task[Unit] =
      withDB(_.events.transaction(_.get(lockId))).flatMap {
        case Some(s: sigil.event.AgentState) if s.state == sigil.signal.EventState.Complete => Task.unit
        case _ if System.currentTimeMillis() > deadline => Task.unit
        case _ => Task.sleep(40.millis).flatMap(_ => loop)
      }
    loop
  }

  /**
   * Spec hook for invoke the boot-time stale-Active
   * reconciliation against the already-open DB.
   */
  def runStaleActiveReconciliation(): rapid.Task[Unit] =
    runStaleActiveReconciliationTask

  override def workspaceFor(conversationId: lightdb.id.Id[sigil.conversation.Conversation])
    : rapid.Task[Option[java.nio.file.Path]] =
    rapid.Task(Option(workspaceOverrides.get(conversationId)))

  // Core tools + a few app tools. The framework's StaticToolSyncUpgrade
  // writes all of these into SigilDB.tools at startup; the default
  // DbToolFinder resolves by name from there.
  override def staticTools: List[Tool] =
    super.staticTools ++ (List(
      sigil.tool.core.ChangeModeTool,
      sigil.tool.provider.PinComplexityTool,
      sigil.tool.core.RespondOptionsTool,
      sigil.tool.core.NoResponseTool,
      SendSlackMessageTool,
      sigil.tool.util.SleepTool,
      sigil.tool.util.LookupTool,
      GetMagicNumberTool,
      ProbeReadTool,
      ProgressEmittingTool,
      EagerActiveLatchTool,
      SlowCooperativeTool,
      SlowStubbornTool,
      DetachableSweepTool,
      FastDetachableTool,
      MutatingSpecTool,
      VerifyingSpecTool,
      sigil.tool.core.RequestEscalationTool,
      sigil.tool.core.RequestDeescalationTool,
      sigil.tool.core.SetBudgetTool
    ): @annotation.nowarn("cat=deprecation"))

  // ---- registration lists ----

  override protected def signalRegistrations: List[RW[? <: Signal]] = Nil

  override protected def participants: List[RW[? <: Participant]] = Nil

  /**
   * Registers every test-only participant id once. Add new test
   * participants to this list — re-registration is cheap and
   * avoids per-suite Sigil variants.
   */
  override protected def participantIds: List[RW[? <: ParticipantId]] =
    List(
      RW.static(TestUser),
      RW.static(TestAgent),
      RW.static(StrictAgent),
      RW.static(ExhaustedAgent),
      RW.static(ForensicAgent),
      RW.static(BrokenHistoryAgent),
      RW.static(NoOpHealAgent),
      RW.static(AgentLoopErrorAgent),
      RW.static(AgentLoopMultiAgent),
      RW.static(EagerActiveAgent)
    )

  /**
   * Registers every test-only [[SpaceId]] once. Add new test
   * spaces here when specs introduce them.
   */
  override protected def spaceIds: List[RW[? <: SpaceId]] =
    List(RW.static(TestSpace), RW.static(WiringSpace), RW.static(MemoryTestSpace))

  /**
   * Registers the test-only `TestCodingMode` and `TestSkilledMode` for
   * both polymorphic Mode RW and `modeByName` resolution.
   */
  override protected def modes: List[Mode] =
    List(TestCodingMode, TestSkilledMode, WebResearchMode, TestModeAlpha)

  /**
   * Registers the test-only [[ConversationStatus]] subtypes:
   * a plain marker and a data-carrying one (proving payload-bearing
   * statuses still answer a `key`-category query).
   */
  override protected def conversationStatusRegistrations: List[RW[? <: ConversationStatus]] =
    List(RW.static(TestSavedStatus), summon[RW[TestCompletedStatus]])

  // ---- default values for mutable hooks ----

  private val defaultProvider: () => Task[Provider] =
    () => Task.error(new RuntimeException("TestSigil.setProvider was not called — no provider configured"))
  private val defaultEmbedding: EmbeddingProvider = NoOpEmbeddingProvider
  private val defaultVectorIndex: VectorIndex = NoOpVectorIndex
  private val defaultCompressionSpace: Option[SpaceId] = None
  private val defaultPutInformation: Information => Unit = _ => ()
  // Mirror the framework default — use the StandardContextCurator so
  // dispatcher / conversation tests that publish a user Message and let
  // the agent fiber pick up `framesFor` actually see the user's content
  // in `TurnInput.frames`. The prior empty-TurnInput default silently
  // hid every published user message from the LLM (the LlamaCppProvider
  // injected its `(begin conversation)` placeholder), masking real
  // model behaviour with placeholder behaviour.
  private val defaultCurate: (Id[Conversation], Id[Model], List[ParticipantId]) => Task[TurnInput] =
    (convId, modelId, chain) =>
      sigil.conversation.compression.StandardContextCurator(TestSigil).curate(convId, modelId, chain)
  // Inherit Sigil's framework default — `StandardMemoryExtractor`
  // wired to `compressionMemorySpace` — so test JVMs exercise the
  // primary extractor path. When `compressionSpaceRef` is None
  // (default), the extractor short-circuits with no LLM call. Tests
  // that opt into extraction call `setCompressionSpace(...)` AND
  // wire a provider.
  private val defaultMemoryExtractor: MemoryExtractor =
    StandardMemoryExtractor(spaceIdFor = compressionMemorySpace)
  private val defaultWireInterceptor: spice.http.client.intercept.Interceptor =
    spice.http.client.intercept.Interceptor.empty
  private val defaultLocationFor: (ParticipantId, Id[Conversation]) => Task[Option[Place]] =
    (_, _) => Task.pure(None)
  private val defaultGeocoder: Geocoder = NoOpGeocoder

  // ---- mutable refs (per-test overrides) ----

  private val providerRef = new AtomicReference[() => Task[Provider]](defaultProvider)
  private val informationRef = new AtomicReference[InMemoryInformation](new InMemoryInformation)
  private val embeddingProviderRef = new AtomicReference[EmbeddingProvider](defaultEmbedding)
  private val vectorIndexRef = new AtomicReference[VectorIndex](defaultVectorIndex)
  private val compressionSpaceRef = new AtomicReference[Option[SpaceId]](defaultCompressionSpace)
  private val putInformationRef = new AtomicReference[Information => Unit](defaultPutInformation)
  private val putInformationsRef = new AtomicReference[Option[Vector[Information] => Unit]](None)
  private val curateRef = new AtomicReference[(Id[Conversation], Id[Model], List[ParticipantId]) => Task[TurnInput]](defaultCurate)
  private val wireInterceptorRef = new AtomicReference[spice.http.client.intercept.Interceptor](defaultWireInterceptor)
  private val memoryExtractorRef = new AtomicReference[MemoryExtractor](defaultMemoryExtractor)
  private val locationForRef = new AtomicReference[(ParticipantId, Id[Conversation]) => Task[Option[Place]]](defaultLocationFor)
  private val geocoderRef = new AtomicReference[Geocoder](defaultGeocoder)
  private val accessibleSpacesRef =
    new AtomicReference[List[ParticipantId] => Task[Set[SpaceId]]]((_: List[ParticipantId]) => Task.pure(Set.empty[SpaceId]))
  private val memoryClassifierModelRef = new AtomicReference[Option[Id[Model]]](None)
  private val currentParticipantRef =
    new AtomicReference[Participant => Task[Participant]](p => Task.pure(p))
  // The default for resolveProviderStrategy delegates to the
  // framework's real flow (storage + lookup through
  // providerStrategies / providerAssignments). Specs that need to
  // inject a fake strategy call `setResolveProviderStrategy(...)`;
  // specs that just want the real persistence flow (e.g.
  // `ProviderStrategySpec`) get it without ceremony. The `Option`
  // models "use override" (`Some`) vs "use framework default"
  // (`None`), with `reset()` reverting to `None`.
  private val resolveProviderStrategyRef =
    new AtomicReference[Option[SpaceId => Task[Option[sigil.provider.ProviderStrategy]]]](None)
  private val toolFinderRef =
    new AtomicReference[Option[ToolFinder]](None)
  private val activeToolchainsHookRef =
    new AtomicReference[Option[Id[Conversation] => Task[Set[String]]]](None)

  /**
   * Per-spec override of the framework's `reservedTopicLabels` set.
   * Some(set) — return that set as the override; None — use the
   * framework default. Used by ReservedTopicLabelsSpec to exercise
   * apps adding the agent's display name to the reserved set
   * without permanently mutating shared TestSigil state.
   */
  val reservedTopicLabelsOverride =
    new AtomicReference[Option[Set[String]]](None)

  /**
   * per-spec override of `narrowRosterByRecentUse`.
   * `None` (default) uses the framework default (`false`); `Some(b)`
   * forces the flag. Used by IntraTurnNarrowingSpec to exercise both
   * sides without mutating shared TestSigil state across tests.
   */
  val narrowRosterByRecentUseOverride =
    new AtomicReference[Option[Boolean]](None)

  /**
   * per-spec override of `pinCoversAuxiliaryCalls`. `None`
   * (default) uses the framework default (`false`, cost-first);
   * `Some(b)` forces the flag. Used by PinnedModelSpec to exercise
   * both sides of `auxModelFor` without mutating shared state.
   */
  val pinCoversAuxiliaryCallsOverride =
    new AtomicReference[Option[Boolean]](None)

  // ---- hook overrides delegate to refs ----

  override def modelResolver: sigil.provider.ModelResolver = new sigil.provider.ModelResolver {
    override def resolve(modelId: Id[Model]): Option[sigil.provider.ProviderModel] = {
      if (cache.find(modelId).isEmpty) testModel(modelId)
      // The swappable provider is constructed as a by-name Task (specs
      // call `setProvider(...)`); resolve is synchronous, so run it here.
      val provider = providerRef.get().apply().sync()
      cache.find(modelId).map(sigil.provider.ProviderModel(provider, _))
    }
  }

  /**
   * auto-register an agent's nominal modelId before the
   * framework's `runAgentTurn` path resolves it against the registry.
   * Specs declare per-spec model ids that aren't in `knownTestModels`;
   * registering at this entry-point keeps every per-spec id resolvable
   * without making each spec explicitly `cache.merge`.
   */
  override def process(participant: Participant, context: TurnContext, triggers: Stream[Event]): Stream[Signal] = {
    registerAgentModelIfMissing(participant)
    super.process(participant, context, triggers)
  }

  /**
   * auto-register every agent participant's modelId on
   * conversation creation. Specs that go through
   * [[sigil.Sigil.newConversation]] get every agent's nominal modelId
   * resolvable at the per-turn boundary without needing to call
   * [[testModel]] manually.
   */
  override def newConversation(createdBy: ParticipantId,
                               label: String = sigil.conversation.Topic.DefaultLabel,
                               summary: String = sigil.conversation.Topic.DefaultSummary,
                               participants: List[Participant] = Nil,
                               currentMode: Mode = sigil.provider.ConversationMode,
                               parentConversationId: Option[Id[sigil.conversation.Conversation]] = None,
                               pinnedComplexity: Option[sigil.provider.Complexity] = None,
                               conversationId: Id[sigil.conversation.Conversation] = sigil.conversation.Conversation.id())
    : Task[sigil.conversation.Conversation] = {
    participants.foreach(registerAgentModelIfMissing)
    super.newConversation(createdBy, label, summary, participants, currentMode, parentConversationId, pinnedComplexity, conversationId)
  }

  /**
   * auto-register the joining agent's modelId on
   * `addParticipant` (late-join path).
   */
  override def addParticipant(conversationId: Id[sigil.conversation.Conversation],
                              participant: Participant): Task[sigil.conversation.Conversation] = {
    registerAgentModelIfMissing(participant)
    super.addParticipant(conversationId, participant)
  }

  private def registerAgentModelIfMissing(p: Participant): Unit = p match {
    case agent: sigil.participant.AgentParticipant =>
      if (cache.find(agent.modelId).isEmpty) {
        testModel(agent.modelId)
        ()
      }
    case _ => ()
  }

  override def getInformation(id: Id[Information]): Task[Option[Information]] =
    informationRef.get().get(id)

  override def putInformation(information: Information): Task[Unit] =
    Task(putInformationRef.get().apply(information))

  override def putInformations(informations: Vector[Information]): Task[Unit] =
    putInformationsRef.get() match {
      case Some(f) => Task(f(informations))
      case None => super.putInformations(informations) // default = N putInformation calls
    }

  override def embeddingProvider: EmbeddingProvider = embeddingProviderRef.get()

  override def vectorIndex: VectorIndex = vectorIndexRef.get()

  override def compressionMemorySpace(conversationId: Id[Conversation]): Task[Option[SpaceId]] =
    Task.pure(compressionSpaceRef.get())

  override def curate(conversationId: Id[Conversation], modelId: Id[Model], chain: List[ParticipantId]): Task[TurnInput] =
    curateRef.get().apply(conversationId, modelId, chain)

  override def wireInterceptor: spice.http.client.intercept.Interceptor = wireInterceptorRef.get()

  override def memoryExtractor: MemoryExtractor = memoryExtractorRef.get()

  override def locationFor(participantId: ParticipantId, conversationId: Id[Conversation]): Task[Option[Place]] =
    locationForRef.get().apply(participantId, conversationId)

  override def geocoder: Geocoder = geocoderRef.get()

  override def accessibleSpaces(chain: List[ParticipantId]): Task[Set[SpaceId]] =
    accessibleSpacesRef.get().apply(chain)

  override def resolveProviderStrategy(space: SpaceId): Task[Option[sigil.provider.ProviderStrategy]] =
    resolveProviderStrategyRef.get() match {
      case Some(fn) => fn.apply(space)
      case None => super.resolveProviderStrategy(space)
    }

  override def findTools: ToolFinder = toolFinderRef.get().getOrElse(super.findTools)

  override def activeToolchains(conversationId: Id[Conversation]): Task[Set[String]] =
    activeToolchainsHookRef.get() match {
      case Some(fn) => fn.apply(conversationId)
      case None => super.activeToolchains(conversationId)
    }

  override def memoryClassifierModel: Option[Id[Model]] = memoryClassifierModelRef.get()

  override def reservedTopicLabels: Set[String] =
    reservedTopicLabelsOverride.get().getOrElse(super.reservedTopicLabels)

  override def narrowRosterByRecentUse: Boolean =
    narrowRosterByRecentUseOverride.get().getOrElse(super.narrowRosterByRecentUse)

  override def pinCoversAuxiliaryCalls: Boolean =
    pinCoversAuxiliaryCallsOverride.get().getOrElse(super.pinCoversAuxiliaryCalls)

  /**
   * Per-spec override of the detach threshold so DetachedToolSpec can
   * promote a fixture tool in milliseconds instead of the 60s
   * framework default. `None` uses the default.
   */
  val toolDetachThresholdOverride =
    new java.util.concurrent.atomic.AtomicReference[Option[Long]](None)
  override def toolDetachThresholdMs: Long =
    toolDetachThresholdOverride.get().getOrElse(super.toolDetachThresholdMs)

  /**
   * Per-spec override of the intra-turn compactor. Specs that count
   * scripted-provider round-trips exactly (DetachedToolSpec) disable
   * compaction — its `compressCovering` issues its own LLM call at
   * respond boundaries, which pollutes call-count assertions. `None`
   * uses the framework default.
   */
  val intraTurnCompactorOverride =
    new AtomicReference[Option[sigil.conversation.compression.IntraTurnCompactor]](None)
  override def intraTurnCompactor: sigil.conversation.compression.IntraTurnCompactor =
    intraTurnCompactorOverride.get().getOrElse(super.intraTurnCompactor)

  // ---- setters (per-test overrides) ----

  def setProvider(p: => Task[Provider]): Unit = providerRef.set(() => p)
  def setEmbeddingProvider(p: EmbeddingProvider): Unit = embeddingProviderRef.set(p)
  def setVectorIndex(v: VectorIndex): Unit = vectorIndexRef.set(v)

  /**
   * Per-test cap override — specs exercising the iteration-cap
   * soft-stop tighten the cap so the soft-stop
   * fires within a reasonable test window. `resetMaxAgentIterations`
   * reverts to the framework default.
   */
  private val maxAgentIterationsRef =
    new java.util.concurrent.atomic.AtomicReference[Option[Int]](None)
  override def maxAgentIterations: Int =
    maxAgentIterationsRef.get().getOrElse(super.maxAgentIterations)
  def setMaxAgentIterations(n: Int): Unit = maxAgentIterationsRef.set(Some(n))
  def resetMaxAgentIterations(): Unit = maxAgentIterationsRef.set(None)

  /**
   * Per-test override for the no-tool-call recovery retry budget
   * Default at the framework level is 3 retries
   * before forced-synthesis; specs asserting on specific call counts
   * pin the budget here to keep their assertions stable across
   * framework default bumps.
   */
  private val noToolCallRetryLimitRef =
    new java.util.concurrent.atomic.AtomicReference[Option[Int]](None)
  override def noToolCallRetryLimit: Int =
    noToolCallRetryLimitRef.get().getOrElse(super.noToolCallRetryLimit)
  def setNoToolCallRetryLimit(n: Int): Unit = noToolCallRetryLimitRef.set(Some(n))
  def resetNoToolCallRetryLimit(): Unit = noToolCallRetryLimitRef.set(None)

  /**
   * Per-test override for the progress-checkpoint cadence. Specs exercising the stall-intervention forced-
   * synthesis path lower this to 1-2 iterations so the checkpoint
   * fires within a reasonable test window without driving the
   * default 15-iteration cadence. `resetProgressCheckpointInterval`
   * reverts to the framework default.
   */
  private val progressCheckpointIntervalRef =
    new java.util.concurrent.atomic.AtomicReference[Option[Int]](None)
  override def progressCheckpointInterval: Int =
    progressCheckpointIntervalRef.get().getOrElse(super.progressCheckpointInterval)
  def setProgressCheckpointInterval(n: Int): Unit = progressCheckpointIntervalRef.set(Some(n))

  /**
   * Per-test iteration-boundary governor roster. Specs registering a
   * custom [[sigil.governor.TurnGovernor]] prepend or append it here;
   * `resetTurnGovernors` reverts to the framework defaults (budget,
   * then progress).
   */
  private val turnGovernorsRef =
    new java.util.concurrent.atomic.AtomicReference[Option[List[sigil.governor.TurnGovernor]]](None)
  override def turnGovernors: List[sigil.governor.TurnGovernor] =
    turnGovernorsRef.get().getOrElse(super.turnGovernors)
  def defaultTurnGovernors: List[sigil.governor.TurnGovernor] = super.turnGovernors
  def setTurnGovernors(governors: List[sigil.governor.TurnGovernor]): Unit = turnGovernorsRef.set(Some(governors))
  def resetTurnGovernors(): Unit = turnGovernorsRef.set(None)

  /**
   * Per-test drained-iteration governor roster. Specs registering a
   * custom [[sigil.governor.OutcomeGovernor]] prepend or append it
   * here; `resetOutcomeGovernors` reverts to the framework defaults.
   */
  private val outcomeGovernorsRef =
    new java.util.concurrent.atomic.AtomicReference[Option[List[sigil.governor.OutcomeGovernor]]](None)
  override def outcomeGovernors: List[sigil.governor.OutcomeGovernor] =
    outcomeGovernorsRef.get().getOrElse(super.outcomeGovernors)
  def defaultOutcomeGovernors: List[sigil.governor.OutcomeGovernor] = super.outcomeGovernors
  def setOutcomeGovernors(governors: List[sigil.governor.OutcomeGovernor]): Unit =
    outcomeGovernorsRef.set(Some(governors))
  def resetOutcomeGovernors(): Unit = outcomeGovernorsRef.set(None)

  /**
   * Per-test planner-tier override — specs exercising the planner
   * checkpoint set the oversight model id here; `None` (the framework
   * default) keeps the executor self-reflection path.
   */
  private val plannerModelIdRef =
    new java.util.concurrent.atomic.AtomicReference[Option[Id[Model]]](None)
  override def plannerModelId: Option[Id[Model]] = plannerModelIdRef.get()
  def setPlannerModelId(modelId: Id[Model]): Unit = plannerModelIdRef.set(Some(modelId))
  def resetPlannerModelId(): Unit = plannerModelIdRef.set(None)

  /**
   * Per-test planner-cadence override — specs asserting call
   * sparseness tighten the periodic tick below the framework default.
   */
  private val plannerCadenceRef =
    new java.util.concurrent.atomic.AtomicReference[Option[Int]](None)
  override def plannerCadence: Int = plannerCadenceRef.get().getOrElse(super.plannerCadence)
  def setPlannerCadence(n: Int): Unit = plannerCadenceRef.set(Some(n))
  def resetPlannerCadence(): Unit = plannerCadenceRef.set(None)

  private val clientToolResultTimeoutRef =
    new java.util.concurrent.atomic.AtomicReference[Option[Long]](None)
  override def clientToolResultTimeoutMs: Long =
    clientToolResultTimeoutRef.get().getOrElse(super.clientToolResultTimeoutMs)
  def setClientToolResultTimeoutMs(ms: Long): Unit = clientToolResultTimeoutRef.set(Some(ms))
  def resetClientToolResultTimeoutMs(): Unit = clientToolResultTimeoutRef.set(None)

  /**
   * Spec access to the protected checkpoint-context builder.
   */
  def progressContextFor(convId: lightdb.id.Id[sigil.conversation.Conversation],
                         agentId: sigil.participant.ParticipantId): rapid.Task[sigil.conversation.ProgressContext] =
    loadProgressContext(convId, agentId)
  def resetProgressCheckpointInterval(): Unit = progressCheckpointIntervalRef.set(None)

  /**
   * Settable hard-stall (identical-call) limit so a spec can disable the
   * model-independent hard-stall and isolate the LLM-checkpoint path.
   */
  private val hardStallLimitRef =
    new java.util.concurrent.atomic.AtomicReference[Option[Int]](None)
  override protected def hardStallIdenticalCallLimit: Int =
    hardStallLimitRef.get().getOrElse(super.hardStallIdenticalCallLimit)
  def setHardStallIdenticalCallLimit(n: Int): Unit = hardStallLimitRef.set(Some(n))
  def resetHardStallIdenticalCallLimit(): Unit = hardStallLimitRef.set(None)

  /**
   * Per-test ToolFinder override — specs that need a synthetic
   * tool catalog (consent gate, toolchain boost, etc.) install
   * one without touching the persisted DB tools. `None` reverts
   * to the framework default (DbToolFinder over the static
   * roster).
   */
  def setToolFinder(f: ToolFinder): Unit = toolFinderRef.set(Some(f))
  def clearToolFinder(): Unit = toolFinderRef.set(None)

  /**
   * Per-test [[Sigil.activeToolchains]] override — specs that
   * exercise the toolchain-boost ranker wire which
   * toolchains are "active" without spawning a real Metals /
   * BSP / etc. session. `None` reverts to the framework
   * default `Set.empty`.
   */
  def setActiveToolchainsHook(f: Id[Conversation] => Task[Set[String]]): Unit =
    activeToolchainsHookRef.set(Some(f))
  def clearActiveToolchainsHook(): Unit = activeToolchainsHookRef.set(None)
  def setCompressionSpace(s: Option[SpaceId]): Unit = compressionSpaceRef.set(s)

  /**
   * Install a callback invoked on every `putInformation` call — specs
   * that want to capture writes for assertions pass an appender.
   */
  def onPutInformation(f: Information => Unit): Unit = putInformationRef.set(f)

  // stub external-image fetching so specs are deterministic and
  // never hit the network. Default: no external image is fetchable.
  private val fetchExternalImageRef =
    new java.util.concurrent.atomic.AtomicReference[String => Task[Option[(Array[Byte], String)]]](_ => Task.pure(None))
  override def fetchExternalImageBytes(url: String): Task[Option[(Array[Byte], String)]] =
    fetchExternalImageRef.get()(url)
  def onFetchExternalImage(f: String => Task[Option[(Array[Byte], String)]]): Unit =
    fetchExternalImageRef.set(f)
  def resetFetchExternalImage(): Unit = fetchExternalImageRef.set(_ => Task.pure(None))

  /**
   * Install a callback invoked on every `putInformations` (bulk)
   * call. When set, the framework's default delegation to per-record
   * `putInformation` is bypassed — specs use this to assert the
   * batch path. Unset by default: TestSigil falls back to the
   * framework's `N` calls to `putInformation` so existing specs
   * that hook `onPutInformation` continue to capture the same
   * writes.
   */
  def onPutInformations(f: Vector[Information] => Unit): Unit =
    putInformationsRef.set(Some(f))

  def clearPutInformations(): Unit = putInformationsRef.set(None)

  /**
   * Install a curator function — specs exercising compression /
   * custom TurnInput shaping wire a curator via this hook.
   */
  def setCurate(f: (Id[Conversation], Id[Model], List[ParticipantId]) => Task[TurnInput]): Unit =
    curateRef.set(f)

  /**
   * Install a wire interceptor — specs needing full HTTP logging
   * (e.g. dumping the OpenAI request/response stream) wire a
   * [[sigil.provider.debug.JsonLinesInterceptor]] via this hook.
   */
  def setWireInterceptor(i: spice.http.client.intercept.Interceptor): Unit =
    wireInterceptorRef.set(i)

  /**
   * Install a per-turn memory extractor — specs exercising the
   * Orchestrator's extraction hook wire a stub here.
   */
  def setMemoryExtractor(e: MemoryExtractor): Unit = memoryExtractorRef.set(e)

  /**
   * Install a capture hook — specs exercising the publish-time
   * location capture wire a stub here.
   */
  def setLocationFor(f: (ParticipantId, Id[Conversation]) => Task[Option[Place]]): Unit =
    locationForRef.set(f)

  /**
   * Install a [[Geocoder]] — specs exercising async enrichment wire
   * an [[sigil.spatial.InMemoryGeocoder]] or custom impl here.
   */
  def setGeocoder(g: Geocoder): Unit = geocoderRef.set(g)

  /**
   * Install an `accessibleSpaces` resolver — specs exercising space
   * authz (storage, tool discovery) wire whatever they need. Default
   * is `Set.empty` (fail-closed, matches Sigil's framework default).
   */
  def setAccessibleSpaces(f: List[ParticipantId] => Task[Set[SpaceId]]): Unit =
    accessibleSpacesRef.set(f)

  /**
   * Install a `memoryClassifierModel` — specs exercising save-side
   * keyword extraction wire the model id used for the one-shot
   * extraction call. Default is `None` (extraction skipped).
   */
  def setMemoryClassifierModel(modelId: Option[Id[Model]]): Unit =
    memoryClassifierModelRef.set(modelId)

  /**
   * Install a `resolveProviderStrategy` resolver — specs exercising
   * provider routing wire a custom strategy here. Default returns `None` (no
   * strategy → routedModelFor falls back to its `fallback` arg).
   */
  def setResolveProviderStrategy(f: SpaceId => Task[Option[sigil.provider.ProviderStrategy]]): Unit =
    resolveProviderStrategyRef.set(Some(f))

  /**
   * Per-test reply-suggestion configuration. Default is `None` — the
   * framework fires no post-turn suggestion consult, matching the
   * shipped default.
   */
  private val replySuggestionsRef =
    new AtomicReference[Option[sigil.conversation.ReplySuggestionsConfig]](None)
  override def replySuggestions: Option[sigil.conversation.ReplySuggestionsConfig] = replySuggestionsRef.get()
  def setReplySuggestions(config: sigil.conversation.ReplySuggestionsConfig): Unit =
    replySuggestionsRef.set(Some(config))
  def resetReplySuggestions(): Unit = replySuggestionsRef.set(None)

  /**
   * Per-test pre-persist gate. Runs inside `publish`'s inbound
   * transform chain — before the signal is persisted or broadcast —
   * so a spec can act at an exact point in the framework's pipeline
   * (notably: while an agent claim is still held, since the terminal
   * Idle `AgentStateDelta` publishes before the claim is released).
   * The signal itself is never rewritten. Default is a no-op.
   */
  private val inboundGateRef = new AtomicReference[Signal => Task[Unit]](_ => Task.unit)
  def setInboundGate(f: Signal => Task[Unit]): Unit = inboundGateRef.set(f)
  def resetInboundGate(): Unit = inboundGateRef.set(_ => Task.unit)

  /**
   * Per-test [[sigil.provider.ContextFeature]] registry. Specs that
   * exercise a feature of their own swap the list; everything else
   * runs against the framework defaults.
   */
  private val contextFeaturesRef = new AtomicReference[List[ContextFeature]](DefaultContextFeatures)
  private val disabledFeaturesRef = new AtomicReference[Set[FeatureId]](Set.empty)
  override def contextFeatures: List[ContextFeature] = contextFeaturesRef.get()
  override def disabledFeatures: Set[FeatureId] = disabledFeaturesRef.get()
  def setContextFeatures(features: List[ContextFeature]): Unit = contextFeaturesRef.set(features)
  def setDisabledFeatures(ids: Set[FeatureId]): Unit = disabledFeaturesRef.set(ids)
  def resetContextFeatures(): Unit = {
    contextFeaturesRef.set(DefaultContextFeatures)
    disabledFeaturesRef.set(Set.empty)
  }

  private object InboundGate extends sigil.pipeline.InboundTransform {
    override def apply(signal: Signal, self: Sigil): Task[Signal] =
      inboundGateRef.get().apply(signal).map(_ => signal)
  }

  override def inboundTransforms: List[sigil.pipeline.InboundTransform] =
    super.inboundTransforms :+ InboundGate

  /**
   * Reset every mutable hook to its default. Call from `beforeEach`
   * (or inline at the start of a test) to guarantee isolation from
   * prior tests within the same suite.
   */
  def reset(): Unit = {
    providerRef.set(defaultProvider)
    informationRef.set(new InMemoryInformation)
    embeddingProviderRef.set(defaultEmbedding)
    vectorIndexRef.set(defaultVectorIndex)
    compressionSpaceRef.set(defaultCompressionSpace)
    putInformationRef.set(defaultPutInformation)
    putInformationsRef.set(None)
    curateRef.set(defaultCurate)
    wireInterceptorRef.set(defaultWireInterceptor)
    memoryExtractorRef.set(defaultMemoryExtractor)
    locationForRef.set(defaultLocationFor)
    geocoderRef.set(defaultGeocoder)
    memoryClassifierModelRef.set(None)
    resolveProviderStrategyRef.set(None)
    accessibleSpacesRef.set((_: List[ParticipantId]) => Task.pure(Set.empty[SpaceId]))
    healingModeRef.set(sigil.heal.HealingMode.Strict)
    pinCoversAuxiliaryCallsOverride.set(None)
    currentParticipantRef.set(p => Task.pure(p))
    replySuggestionsRef.set(None)
    inboundGateRef.set(_ => Task.unit)
    resetContextFeatures()
  }

  override def currentParticipant(persisted: Participant): Task[Participant] =
    currentParticipantRef.get().apply(persisted)

  /**
   * Swap the per-turn participant reconciliation (Sigil's frozen-roster
   * hook). Specs use this to simulate an app whose canonical agent
   * definition gained a tool after a conversation was created.
   */
  def setCurrentParticipant(f: Participant => Task[Participant]): Unit = currentParticipantRef.set(f)
  def resetCurrentParticipant(): Unit = currentParticipantRef.set(p => Task.pure(p))

  /**
   * Expose the in-memory information store that backs `getInformation`
   * so specs populate it before exercising
   * [[sigil.tool.util.LookupTool]].
   */
  def information: InMemoryInformation = informationRef.get()

  /**
   * the default synthetic Model record used by specs that
   * don't care about a specific model id. Tests that DO care call
   * [[testModel]] with their preferred id.
   */
  val defaultTestModel: Model = syntheticTestModel(Model.id("test", "model"))

  /**
   * pre-seeded synthetic Model records for the test model
   * ids that ship with the framework's own specs. Each carries
   * conservative defaults (32K context, zero pricing) so any
   * cost / budget heuristic that reads the registered Model record
   * gets representative numbers without coupling specs to a real
   * upstream catalog.
   */
  val knownTestModels: List[Model] = List(
    defaultTestModel,
    syntheticTestModel(Model.id("test", "vision-model"))
  )

  /**
   * Build (and register) a synthetic Model record on demand. Specs
   * that need a Model with a specific id (e.g. for a routing case
   * not pre-seeded in [[knownTestModels]]) call this; the record is
   * persisted into the model cache so subsequent
   * `sigil.cache.find(id)` calls at the migration boundary succeed.
   */
  def registerTestModel(modelId: Id[Model]): Model = {
    val model = syntheticTestModel(modelId)
    cache.merge(List(model)).sync()
    model
  }

  /**
   * Convenience alias for `cache.find(id).getOrElse(registerTestModel(id))`.
   */
  def testModel(modelId: Id[Model]): Model =
    cache.find(modelId).getOrElse(registerTestModel(modelId))

  private def syntheticTestModel(id: Id[Model]): Model = {
    val now = lightdb.time.Timestamp()
    Model(
      canonicalSlug = id.value,
      huggingFaceId = "",
      name = id.value,
      description = s"Synthetic test Model fixture for ${id.value}.",
      contextLength = 32768L,
      architecture = ModelArchitecture(
        modality = "text->text",
        inputModalities = List("text"),
        outputModalities = List("text"),
        tokenizer = "GPT",
        instructType = None
      ),
      pricing = ModelPricing(prompt = BigDecimal(0), completion = BigDecimal(0), webSearch = None, inputCacheRead = None),
      topProvider = ModelTopProvider(contextLength = Some(32768L), maxCompletionTokens = Some(8192L), isModerated = false),
      perRequestLimits = None,
      supportedParameters = Set("temperature", "max_tokens", "top_p", "tools", "tool_choice"),
      knowledgeCutoff = None,
      expirationDate = None,
      links = ModelLinks(details = ""),
      created = now,
      _id = id
    )
  }

  /**
   * Initialize the test Sigil with a DB path scoped to the calling test
   * class. With `testGrouping` (one JVM per suite) plus per-suite paths,
   * each test exercises a fresh RocksDB instance — no lock contention, no
   * cross-suite state bleed.
   *
   * Wipes any leftover directory from a previous run before initializing,
   * so a crashed prior process can't leave stale RocksDB state that breaks
   * the next run. LightDB's own shutdown hook handles clean disposal on
   * normal JVM exit.
   *
   * Call once at the top of each spec, passing `getClass.getSimpleName`.
   */
  def initFor(testClassName: String): Unit = {
    val name = testClassName.replace("$", "")
    val dbPath = java.nio.file.Path.of("db", "test", name)
    deleteRecursive(dbPath)
    Profig.merge(obj("sigil" -> obj("dbPath" -> str(dbPath.toString))))
    instance.sync()
    // seed the model registry with the synthetic test
    // model fixtures every spec relies on. The registry is in-memory
    // (`cache.merge` is a fast Task), so we re-merge per-suite without
    // racing with anything else.
    cache.merge(TestSigil.knownTestModels).sync()
    // Per-suite wire log: always write a jsonl file named after this
    // suite, so that when a test fails the HTTP back-and-forth is
    // already on disk for post-mortem inspection. Override the
    // directory via `sigil.wire.log.dir` (env SIGIL_WIRE_LOG_DIR).
    val wireDir = {
      import fabric.rw.*
      Profig("sigil.wire.log.dir").opt[String].getOrElse("target/wire-logs")
    }
    val wirePath = java.nio.file.Path.of(wireDir, s"$name.jsonl")
    if (java.nio.file.Files.exists(wirePath)) java.nio.file.Files.delete(wirePath)
    wireInterceptorRef.set(sigil.provider.debug.JsonLinesInterceptor(wirePath))
    ()
  }

  private def deleteRecursive(path: java.nio.file.Path): Unit =
    if (java.nio.file.Files.exists(path)) {
      val stream = java.nio.file.Files.walk(path)
      try {
        import scala.jdk.CollectionConverters.*
        stream
          .iterator()
          .asScala
          .toList
          .reverse
          .foreach(p => java.nio.file.Files.deleteIfExists(p))
      } finally stream.close()
    }
}

/**
 * Synthetic tool exposed through [[TestSigil.findTools]]. Exists so the
 * `find_capability` flow has a real catalog entry to surface; tests
 * never actually invoke it, so its result is a trivial confirmation.
 */
case class SendSlackMessageInput(channel: String, text: String) extends ToolInput derives RW

case object SendSlackMessageTool extends sigil.tool.Tool {
  type Input = SendSlackMessageInput
  type Output = sigil.tool.TextToolOutput
  val io: ToolIO[SendSlackMessageInput, sigil.tool.TextToolOutput] = ToolIO.derived[SendSlackMessageInput, sigil.tool.TextToolOutput]

  override val name = sigil.tool.ToolName("send_slack_message")
  override val description =
    "Send a message to a Slack channel on behalf of the user. Takes a channel name and the message text."
  val spec: sigil.tool.ToolSpec = sigil.tool.ToolSpec(
    name = name,
    description = description,
    profile = sigil.tool.ToolProfile(effect = sigil.tool.Effect.Mutating(sigil.tool.MutationTargeting.none)),
    discovery = sigil.tool.DiscoverySpec(keywords = Set("slack", "message", "channel"))
  )

  protected def resolve: Resolution[Input, Output] = Resolution.Simple(executeOutput)

  private def executeOutput(input: SendSlackMessageInput, context: ToolContext): rapid.Task[sigil.tool.TextToolOutput] =
    rapid.Task.pure(sigil.tool.TextToolOutput(s"Sent to ${input.channel}: ${input.text}"))
}

/**
 * Shared synthetic topic id used across tests that don't need a live
 * topic record. Tests that exercise `Sigil.updateConversationProjection`
 * or the orchestrator's topic lookup upsert a real Topic with this id
 * via [[TestSigil.withDB]]; tests that only construct events in-memory
 * can use this id without backing it with a record.
 */
val TestTopicId: Id[Topic] = Id[Topic]("test-topic")

/**
 * Shared synthetic TopicEntry used across tests that need to seed
 * a Conversation with a topic stack. Label + summary are generic.
 */
val TestTopicEntry: TopicEntry = TopicEntry(
  id = TestTopicId,
  label = "Test Topic",
  summary = "A synthetic topic used in tests."
)

/**
 * Shared single-entry topic stack for tests. Most scenarios don't
 * care about priors; those that do build their own stack.
 */
val TestTopicStack: List[TopicEntry] = List(TestTopicEntry)

/**
 * Stand-in user participant for tests.
 */
case object TestUser extends ParticipantId {
  override val value: String = "test-user"
}

/**
 * Stand-in agent participant id for tests. Specs construct a concrete
 * [[sigil.participant.AgentParticipant]] carrying this id.
 */
case object TestAgent extends AgentParticipantId {
  override val value: String = "test-agent"
}

/**
 * General-purpose memory space used by specs that need to persist
 * memories without caring about the scope. Registered once via
 * [[TestSigil.spaceIds]].
 */
case object TestSpace extends SpaceId {
  override val value: String = "test-space"
  override val displayName: String = "Test space"
  override val description: Option[String] = Some("Generic test scope used by specs that don't care about partitioning.")
}

/**
 * Memory space used by the Sigil embedding-wiring spec.
 */
case object WiringSpace extends SpaceId {
  override val value: String = "wiring-space"
  override val displayName: String = "Wiring space"
  override val description: Option[String] = Some("Vector-wiring spec scope.")
}

/**
 * Memory space used by the memory-compressor spec for extracted facts.
 */
case object MemoryTestSpace extends SpaceId {
  override val value: String = "memory-compressor-space"
  override val displayName: String = "Memory test space"
  override val description: Option[String] = Some("Compressor / retrieval spec scope.")
}

/**
 * Test-only [[ConversationStatus]] — a plain lifecycle marker.
 */
case object TestSavedStatus extends ConversationStatus {
  val key: String = "test-saved"
}

/**
 * Test-only data-carrying [[ConversationStatus]] — proves a payload-bearing
 * status still answers a category (`key`) query.
 */
case class TestCompletedStatus(at: Long) extends ConversationStatus derives RW {
  val key: String = "test-completed"
}

/**
 * Stand-in for the old `Mode.Coding` enum case in tests — a test-only
 * mode registered by [[TestSigil]] so specs can exercise mode-switch
 * behaviour. No skill, no tool policy — a plain named mode.
 */
case object TestCodingMode extends sigil.provider.Mode {
  override val name: String = "coding"
  override val description: String = "Test coding mode — code generation, editing, review."
}

/**
 * Test-only mode that ships a non-empty skill slot. Used by specs
 * exercising the Mode-source skill path (`ParticipantProjection.activeSkills`
 * key `SkillSource.Mode`). Replaces the old `modeSkill` hook-based test.
 */
case object TestSkilledMode extends sigil.provider.Mode {
  override val name: String = "skilled"
  override val description: String = "Test mode carrying a skill slot."
  override val skill: Option[ActiveSkillSlot] = Some(ActiveSkillSlot(
    name = "test-skill",
    content =
      "You are a test agent with a test skill."
  ))
}

/**
 * Deterministic test embedder — each token contributes to a
 * fixed-dim vector via a stable hash. Avoids a real embedding API
 * while exercising the vector-wired code paths. Used by any spec
 * that needs [[sigil.Sigil.searchMemories]] or
 * [[sigil.Sigil.searchConversationEvents]] to go through the vector
 * branch rather than the Lucene fallback.
 */
object TestHashEmbeddingProvider extends EmbeddingProvider {
  override def dimensions: Int = 32

  override def embed(text: String): Task[Vector[Double]] = Task {
    val buf = Array.fill(dimensions)(0.0)
    text.toLowerCase.split("\\W+").filter(_.nonEmpty).foreach { tok =>
      val h = tok.hashCode
      val i = math.floorMod(h, dimensions)
      buf(i) += 1.0
    }
    val norm = math.sqrt(buf.map(x => x * x).sum)
    if (norm == 0.0) buf.toVector else buf.map(_ / norm).toVector
  }

  override def embedBatch(texts: List[String]): Task[List[Vector[Double]]] =
    Task.sequence(texts.map(embed))
}