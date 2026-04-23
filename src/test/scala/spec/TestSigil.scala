package spec

import fabric.*
import fabric.rw.*
import lightdb.id.Id
import profig.Profig
import rapid.Task
import sigil.{Sigil, SignalBroadcaster, TurnContext}
import sigil.conversation.{ActiveSkillSlot, Conversation, ConversationView, MemorySpaceId, Topic, TopicEntry, TurnInput}
import sigil.db.Model
import sigil.embedding.{EmbeddingProvider, NoOpEmbeddingProvider}
import sigil.event.Event
import sigil.information.{InMemoryInformation, Information}
import sigil.participant.{AgentParticipantId, Participant, ParticipantId}
import sigil.provider.{Mode, Provider}
import sigil.signal.Signal
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
  override def testMode: Boolean = true

  lazy val llamaCppHost: URL = Profig("sigil.llamacpp.host").asOr[URL](url"http://localhost:8081")

  // Core tools + the synthetic SendSlackMessageTool. Agents reference them
  // by name; `byName` resolves from this catalog at call time.
  // Tests opt into the non-core utility tools explicitly. `SleepTool`
  // backs timing-sensitive dispatcher tests; `LookupInformationTool`
  // backs the CoreToolsSpec information-resolution coverage.
  private val appTools: List[Tool[? <: ToolInput]] = List(
    SendSlackMessageTool,
    sigil.tool.util.SleepTool,
    sigil.tool.util.LookupInformationTool
  )

  override val findTools: ToolFinder =
    InMemoryToolFinder(CoreTools.all.toList ++ appTools)

  // ---- registration lists ----

  override protected def signals: List[RW[? <: Signal]] = Nil

  override protected def participants: List[RW[? <: Participant]] = Nil

  /** Registers every test-only participant id once. Add new test
    * participants to this list — re-registration is cheap and
    * avoids per-suite Sigil variants. */
  override protected def participantIds: List[RW[? <: ParticipantId]] =
    List(RW.static(TestUser), RW.static(TestAgent))

  /** Registers every test-only [[MemorySpaceId]] once. Add new test
    * spaces here when specs introduce them. */
  override protected def memorySpaceIds: List[RW[? <: MemorySpaceId]] =
    List(RW.static(TestSpace), RW.static(WiringSpace), RW.static(MemoryTestSpace))

  // ---- default values for mutable hooks ----

  private val defaultProvider: () => Task[Provider] =
    () => Task.error(new RuntimeException("TestSigil.setProvider was not called — no provider configured"))
  private val defaultBroadcaster: SignalBroadcaster = SignalBroadcaster.NoOp
  private val defaultModeSkill: Mode => Task[Option[ActiveSkillSlot]] = _ => Task.pure(None)
  private val defaultEmbedding: EmbeddingProvider = NoOpEmbeddingProvider
  private val defaultVectorIndex: VectorIndex = NoOpVectorIndex
  private val defaultCompressionSpace: Option[MemorySpaceId] = None
  private val defaultPutInformation: Information => Unit = _ => ()
  private val defaultCurate: (ConversationView, Id[Model], List[ParticipantId]) => Task[TurnInput] =
    (view, _, _) => Task.pure(TurnInput(view))

  // ---- mutable refs (per-test overrides) ----

  private val providerRef = new AtomicReference[() => Task[Provider]](defaultProvider)
  private val broadcasterRef = new AtomicReference[SignalBroadcaster](defaultBroadcaster)
  private val informationRef = new AtomicReference[InMemoryInformation](new InMemoryInformation)
  private val modeSkillRef = new AtomicReference[Mode => Task[Option[ActiveSkillSlot]]](defaultModeSkill)
  private val embeddingProviderRef = new AtomicReference[EmbeddingProvider](defaultEmbedding)
  private val vectorIndexRef = new AtomicReference[VectorIndex](defaultVectorIndex)
  private val compressionSpaceRef = new AtomicReference[Option[MemorySpaceId]](defaultCompressionSpace)
  private val putInformationRef = new AtomicReference[Information => Unit](defaultPutInformation)
  private val curateRef = new AtomicReference[(ConversationView, Id[Model], List[ParticipantId]) => Task[TurnInput]](defaultCurate)

  // ---- hook overrides delegate to refs ----

  override def broadcaster: SignalBroadcaster = broadcasterRef.get()

  override def providerFor(modelId: Id[Model], chain: List[ParticipantId]): Task[Provider] =
    providerRef.get().apply()

  override def getInformation(id: Id[Information]): Task[Option[Information]] =
    informationRef.get().get(id)

  override def putInformation(information: Information): Task[Unit] =
    Task(putInformationRef.get().apply(information))

  override def modeSkill(mode: Mode): Task[Option[ActiveSkillSlot]] =
    modeSkillRef.get().apply(mode)

  override def embeddingProvider: EmbeddingProvider = embeddingProviderRef.get()

  override def vectorIndex: VectorIndex = vectorIndexRef.get()

  override def compressionMemorySpace(conversationId: Id[Conversation]): Task[Option[MemorySpaceId]] =
    Task.pure(compressionSpaceRef.get())

  override def curate(view: ConversationView,
                      modelId: Id[Model],
                      chain: List[ParticipantId]): Task[TurnInput] =
    curateRef.get().apply(view, modelId, chain)

  // ---- setters (per-test overrides) ----

  def setBroadcaster(b: SignalBroadcaster): Unit = broadcasterRef.set(b)
  def setProvider(p: => Task[Provider]): Unit = providerRef.set(() => p)
  def setModeSkill(f: Mode => Task[Option[ActiveSkillSlot]]): Unit = modeSkillRef.set(f)
  def setEmbeddingProvider(p: EmbeddingProvider): Unit = embeddingProviderRef.set(p)
  def setVectorIndex(v: VectorIndex): Unit = vectorIndexRef.set(v)
  def setCompressionSpace(s: Option[MemorySpaceId]): Unit = compressionSpaceRef.set(s)

  /** Install a callback invoked on every `putInformation` call — specs
    * that want to capture writes for assertions pass an appender. */
  def onPutInformation(f: Information => Unit): Unit = putInformationRef.set(f)

  /** Install a curator function — specs exercising compression /
    * custom TurnInput shaping wire a curator via this hook. */
  def setCurate(f: (ConversationView, Id[Model], List[ParticipantId]) => Task[TurnInput]): Unit =
    curateRef.set(f)

  /** Reset every mutable hook to its default. Call from `beforeEach`
    * (or inline at the start of a test) to guarantee isolation from
    * prior tests within the same suite. */
  def reset(): Unit = {
    providerRef.set(defaultProvider)
    broadcasterRef.set(defaultBroadcaster)
    informationRef.set(new InMemoryInformation)
    modeSkillRef.set(defaultModeSkill)
    embeddingProviderRef.set(defaultEmbedding)
    vectorIndexRef.set(defaultVectorIndex)
    compressionSpaceRef.set(defaultCompressionSpace)
    putInformationRef.set(defaultPutInformation)
    curateRef.set(defaultCurate)
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

object SendSlackMessageTool extends Tool[SendSlackMessageInput] {
  override protected def uniqueName: String = "send_slack_message"
  override protected def description: String =
    "Send a message to a Slack channel on behalf of the user. Takes a channel name and the message text."
  override def execute(input: SendSlackMessageInput, context: TurnContext): rapid.Stream[Event] = rapid.Stream.empty
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
  * [[TestSigil.memorySpaceIds]]. */
case object TestSpace extends MemorySpaceId {
  override val value: String = "test-space"
}

/** Memory space used by the Sigil embedding-wiring spec. */
case object WiringSpace extends MemorySpaceId {
  override val value: String = "wiring-space"
}

/** Memory space used by the memory-compressor spec for extracted facts. */
case object MemoryTestSpace extends MemorySpaceId {
  override val value: String = "memory-compressor-space"
}
