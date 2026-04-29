package spec

import fabric.*
import fabric.rw.*
import lightdb.id.Id
import profig.Profig
import rapid.Task
import sigil.{Sigil, TurnContext}
import sigil.conversation.{ActiveSkillSlot, Conversation, ConversationView, Topic, TopicEntry, TurnInput}
import sigil.SpaceId
import sigil.conversation.compression.extract.{MemoryExtractor, NoOpMemoryExtractor}
import sigil.db.Model
import sigil.embedding.{EmbeddingProvider, NoOpEmbeddingProvider}
import sigil.event.Event
import sigil.information.{InMemoryInformation, Information}
import sigil.participant.{AgentParticipantId, Participant, ParticipantId}
import sigil.provider.{Mode, Provider}
import sigil.signal.Signal
import sigil.spatial.{Geocoder, NoOpGeocoder, Place}
import sigil.tool.{InMemoryToolFinder, Tool, ToolFinder, ToolInput}
import sigil.tool.core.CoreTools
import sigil.vector.{NoOpVectorIndex, VectorIndex}
import spice.net.*

import java.util.concurrent.atomic.AtomicReference

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

  lazy val llamaCppHost: URL = Profig("sigil.llamacpp.host").asOr[URL](url"https://llama.voidcraft.ai")

  // Core tools + a few app tools. The framework's StaticToolSyncUpgrade
  // writes all of these into SigilDB.tools at startup; the default
  // DbToolFinder resolves by name from there.
  override def staticTools: List[Tool] =
    super.staticTools ++ List(
      sigil.tool.core.ChangeModeTool,
      SendSlackMessageTool,
      sigil.tool.util.SleepTool,
      sigil.tool.util.LookupInformationTool,
      GetMagicNumberTool
    )

  // ---- registration lists ----

  override protected def signalRegistrations: List[RW[? <: Signal]] = Nil

  override protected def participants: List[RW[? <: Participant]] = Nil

  /** Registers every test-only participant id once. Add new test
    * participants to this list — re-registration is cheap and
    * avoids per-suite Sigil variants. */
  override protected def participantIds: List[RW[? <: ParticipantId]] =
    List(RW.static(TestUser), RW.static(TestAgent))

  /** Registers every test-only [[SpaceId]] once. Add new test
    * spaces here when specs introduce them. */
  override protected def spaceIds: List[RW[? <: SpaceId]] =
    List(RW.static(TestSpace), RW.static(WiringSpace), RW.static(MemoryTestSpace))

  /** Registers the test-only `TestCodingMode` and `TestSkilledMode` for
    * both polymorphic Mode RW and `modeByName` resolution. */
  override protected def modes: List[Mode] =
    List(TestCodingMode, TestSkilledMode, WebResearchMode)

  // ---- default values for mutable hooks ----

  private val defaultProvider: () => Task[Provider] =
    () => Task.error(new RuntimeException("TestSigil.setProvider was not called — no provider configured"))
  private val defaultEmbedding: EmbeddingProvider = NoOpEmbeddingProvider
  private val defaultVectorIndex: VectorIndex = NoOpVectorIndex
  private val defaultCompressionSpace: Option[SpaceId] = None
  private val defaultPutInformation: Information => Unit = _ => ()
  private val defaultCurate: (ConversationView, Id[Model], List[ParticipantId]) => Task[TurnInput] =
    (view, _, _) => Task.pure(TurnInput(view))
  private val defaultMemoryExtractor: MemoryExtractor = NoOpMemoryExtractor
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
  private val curateRef = new AtomicReference[(ConversationView, Id[Model], List[ParticipantId]) => Task[TurnInput]](defaultCurate)
  private val wireInterceptorRef = new AtomicReference[spice.http.client.intercept.Interceptor](defaultWireInterceptor)
  private val memoryExtractorRef = new AtomicReference[MemoryExtractor](defaultMemoryExtractor)
  private val locationForRef = new AtomicReference[(ParticipantId, Id[Conversation]) => Task[Option[Place]]](defaultLocationFor)
  private val geocoderRef = new AtomicReference[Geocoder](defaultGeocoder)
  private val accessibleSpacesRef = new AtomicReference[List[ParticipantId] => Task[Set[SpaceId]]](
    (_: List[ParticipantId]) => Task.pure(Set.empty[SpaceId])
  )

  // ---- hook overrides delegate to refs ----

  override def providerFor(modelId: Id[Model], chain: List[ParticipantId]): Task[Provider] =
    providerRef.get().apply()

  override def getInformation(id: Id[Information]): Task[Option[Information]] =
    informationRef.get().get(id)

  override def putInformation(information: Information): Task[Unit] =
    Task(putInformationRef.get().apply(information))

  override def embeddingProvider: EmbeddingProvider = embeddingProviderRef.get()

  override def vectorIndex: VectorIndex = vectorIndexRef.get()

  override def compressionMemorySpace(conversationId: Id[Conversation]): Task[Option[SpaceId]] =
    Task.pure(compressionSpaceRef.get())

  override def curate(view: ConversationView,
                      modelId: Id[Model],
                      chain: List[ParticipantId]): Task[TurnInput] =
    curateRef.get().apply(view, modelId, chain)

  override def wireInterceptor: spice.http.client.intercept.Interceptor = wireInterceptorRef.get()

  override def memoryExtractor: MemoryExtractor = memoryExtractorRef.get()

  override def locationFor(participantId: ParticipantId,
                           conversationId: Id[Conversation]): Task[Option[Place]] =
    locationForRef.get().apply(participantId, conversationId)

  override def geocoder: Geocoder = geocoderRef.get()

  override def accessibleSpaces(chain: List[ParticipantId]): Task[Set[SpaceId]] =
    accessibleSpacesRef.get().apply(chain)

  // ---- setters (per-test overrides) ----

  def setProvider(p: => Task[Provider]): Unit = providerRef.set(() => p)
  def setEmbeddingProvider(p: EmbeddingProvider): Unit = embeddingProviderRef.set(p)
  def setVectorIndex(v: VectorIndex): Unit = vectorIndexRef.set(v)
  def setCompressionSpace(s: Option[SpaceId]): Unit = compressionSpaceRef.set(s)

  /** Install a callback invoked on every `putInformation` call — specs
    * that want to capture writes for assertions pass an appender. */
  def onPutInformation(f: Information => Unit): Unit = putInformationRef.set(f)

  /** Install a curator function — specs exercising compression /
    * custom TurnInput shaping wire a curator via this hook. */
  def setCurate(f: (ConversationView, Id[Model], List[ParticipantId]) => Task[TurnInput]): Unit =
    curateRef.set(f)

  /** Install a wire interceptor — specs needing full HTTP logging
    * (e.g. dumping the OpenAI request/response stream) wire a
    * [[sigil.provider.debug.JsonLinesInterceptor]] via this hook. */
  def setWireInterceptor(i: spice.http.client.intercept.Interceptor): Unit =
    wireInterceptorRef.set(i)

  /** Install a per-turn memory extractor — specs exercising the
    * Orchestrator's extraction hook wire a stub here. */
  def setMemoryExtractor(e: MemoryExtractor): Unit = memoryExtractorRef.set(e)

  /** Install a capture hook — specs exercising the publish-time
    * location capture wire a stub here. */
  def setLocationFor(f: (ParticipantId, Id[Conversation]) => Task[Option[Place]]): Unit =
    locationForRef.set(f)

  /** Install a [[Geocoder]] — specs exercising async enrichment wire
    * an [[sigil.spatial.InMemoryGeocoder]] or custom impl here. */
  def setGeocoder(g: Geocoder): Unit = geocoderRef.set(g)

  /** Install an `accessibleSpaces` resolver — specs exercising space
    * authz (storage, tool discovery) wire whatever they need. Default
    * is `Set.empty` (fail-closed, matches Sigil's framework default). */
  def setAccessibleSpaces(f: List[ParticipantId] => Task[Set[SpaceId]]): Unit =
    accessibleSpacesRef.set(f)

  /** Reset every mutable hook to its default. Call from `beforeEach`
    * (or inline at the start of a test) to guarantee isolation from
    * prior tests within the same suite. */
  def reset(): Unit = {
    providerRef.set(defaultProvider)
    informationRef.set(new InMemoryInformation)
    embeddingProviderRef.set(defaultEmbedding)
    vectorIndexRef.set(defaultVectorIndex)
    compressionSpaceRef.set(defaultCompressionSpace)
    putInformationRef.set(defaultPutInformation)
    curateRef.set(defaultCurate)
    wireInterceptorRef.set(defaultWireInterceptor)
    memoryExtractorRef.set(defaultMemoryExtractor)
    locationForRef.set(defaultLocationFor)
    geocoderRef.set(defaultGeocoder)
  }

  /** Expose the in-memory information store that backs `getInformation`
    * so specs populate it before exercising
    * [[sigil.tool.util.LookupInformationTool]]. */
  def information: InMemoryInformation = informationRef.get()

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

  private def deleteRecursive(path: java.nio.file.Path): Unit = {
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
}

/**
 * Synthetic tool exposed through [[TestSigil.findTools]]. Exists so the
 * `find_capability` flow has a real catalog entry to surface; its
 * `execute` returns no events because tests never actually invoke it.
 */
case class SendSlackMessageInput(channel: String, text: String) extends ToolInput derives RW

case object SendSlackMessageTool extends sigil.tool.TypedTool[SendSlackMessageInput](
  name = sigil.tool.ToolName("send_slack_message"),
  description = "Send a message to a Slack channel on behalf of the user. Takes a channel name and the message text.",
  keywords = Set("slack", "message", "channel")
) {
  override protected def executeTyped(input: SendSlackMessageInput, context: TurnContext): rapid.Stream[Event] = rapid.Stream.empty
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

/** General-purpose memory space used by specs that need to persist
  * memories without caring about the scope. Registered once via
  * [[TestSigil.spaceIds]]. */
case object TestSpace extends SpaceId {
  override val value: String = "test-space"
}

/** Memory space used by the Sigil embedding-wiring spec. */
case object WiringSpace extends SpaceId {
  override val value: String = "wiring-space"
}

/** Memory space used by the memory-compressor spec for extracted facts. */
case object MemoryTestSpace extends SpaceId {
  override val value: String = "memory-compressor-space"
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
    content = "You are a test agent with a test skill."
  ))
}

/** Deterministic test embedder — each token contributes to a
  * fixed-dim vector via a stable hash. Avoids a real embedding API
  * while exercising the vector-wired code paths. Used by any spec
  * that needs [[sigil.Sigil.searchMemories]] or
  * [[sigil.Sigil.searchConversationEvents]] to go through the vector
  * branch rather than the Lucene fallback. */
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
