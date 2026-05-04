package spec

import fabric.rw.RW
import lightdb.store.CollectionManager
import lightdb.upgrade.DatabaseUpgrade
import profig.Profig
import rapid.Task
import sigil.{Sigil, SpaceId}
import sigil.conversation.{Conversation, ConversationView, TurnInput}
import sigil.db.{Model, SigilDB}
import sigil.embedding.{EmbeddingProvider, NoOpEmbeddingProvider}
import sigil.information.Information
import sigil.mcp.McpCollections
import sigil.metals.MetalsSigil
import sigil.participant.{Participant, ParticipantId}
import sigil.provider.Provider
import sigil.signal.Signal
import sigil.tool.{InMemoryToolFinder, Tool, ToolFinder}
import sigil.vector.{NoOpVectorIndex, VectorIndex}

import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference

/** Concrete DB for the metals-module tests — `SigilDB` plus
  * [[McpCollections]] (transitively required by [[MetalsSigil]]). */
class TestMetalsDB(directory: Option[Path],
                   storeManager: CollectionManager,
                   upgrades: List[DatabaseUpgrade] = Nil)
  extends SigilDB(directory, storeManager, upgrades)
    with McpCollections

/** Test fixture for the `sigil-metals` spec.
  *
  *   - `metalsLauncher` points at `fake-metals.sh` so spawn doesn't
  *     require a real Metals install.
  *   - `metalsWorkspace` returns whatever the test sets via
  *     [[setWorkspace]] — per-test mapping without subclassing.
  *   - `metalsIdleTimeoutMs` is overridable per-test via
  *     [[setIdleTimeout]] so the idle-reap behavior can be exercised
  *     without waiting fifteen real minutes. */
object TestMetalsSigil extends Sigil with MetalsSigil {
  override type DB = TestMetalsDB
  override protected def buildDB(directory: Option[Path],
                                  storeManager: CollectionManager,
                                  upgrades: List[DatabaseUpgrade]): TestMetalsDB =
    new TestMetalsDB(directory, storeManager, upgrades)

  override def testMode: Boolean = true

  override protected def signalRegistrations: List[RW[? <: Signal]] = Nil
  override protected def participantIds: List[RW[? <: ParticipantId]] = Nil
  override protected def spaceIds: List[RW[? <: SpaceId]] = Nil
  override protected def participants: List[RW[? <: Participant]] = Nil

  override val findTools: ToolFinder = InMemoryToolFinder(Nil)

  override def curate(view: ConversationView,
                      modelId: lightdb.id.Id[Model],
                      chain: List[ParticipantId]): Task[TurnInput] =
    Task.pure(TurnInput(view))

  override def getInformation(id: lightdb.id.Id[Information]): Task[Option[Information]] = Task.pure(None)
  override def putInformation(information: Information): Task[Unit] = Task.unit
  override def compressionMemorySpace(conversationId: lightdb.id.Id[Conversation]): Task[Option[SpaceId]] =
    Task.pure(None)

  override def wireInterceptor: spice.http.client.intercept.Interceptor =
    spice.http.client.intercept.Interceptor.empty

  override def providerFor(modelId: lightdb.id.Id[Model], chain: List[ParticipantId]): Task[Provider] =
    Task.error(new RuntimeException("TestMetalsSigil: no provider configured"))

  override val embeddingProvider: EmbeddingProvider = NoOpEmbeddingProvider
  override val vectorIndex: VectorIndex = NoOpVectorIndex

  /** Per-test workspace override — set by [[setWorkspace]]. */
  private val workspaceRef: AtomicReference[Option[Path]] = new AtomicReference(None)
  def setWorkspace(p: Option[Path]): Unit = workspaceRef.set(p)
  override def metalsWorkspace(conversationId: lightdb.id.Id[Conversation]): Task[Option[Path]] =
    Task.pure(workspaceRef.get())

  /** Per-test idle-timeout override. Defaults to 15 minutes (the
    * production value) so idle-reap doesn't fire mid-test unless
    * explicitly enabled. */
  private val idleTimeoutRef: AtomicReference[Long] = new AtomicReference(15L * 60L * 1000L)
  def setIdleTimeout(ms: Long): Unit = idleTimeoutRef.set(ms)
  override def metalsIdleTimeoutMs: Long = idleTimeoutRef.get()

  /** Point at the fake-metals.sh fixture from `src/test/resources`. */
  override def metalsLauncher: List[String] = {
    val cwd = java.nio.file.Path.of("metals/src/test/resources/fake-metals.sh").toAbsolutePath.normalize
    List(cwd.toString)
  }

  def initFor(testClassName: String): Unit = {
    val name = testClassName.replace("$", "")
    val dbPath = java.nio.file.Path.of("db", "test", name)
    deleteRecursive(dbPath)
    Profig.merge(fabric.obj("sigil" -> fabric.obj("dbPath" -> fabric.str(dbPath.toString))))
    instance.sync()
    ()
  }

  private def deleteRecursive(path: java.nio.file.Path): Unit = {
    if (java.nio.file.Files.exists(path)) {
      val s = java.nio.file.Files.walk(path)
      try {
        import scala.jdk.CollectionConverters.*
        s.iterator().asScala.toList.reverse.foreach(p => java.nio.file.Files.deleteIfExists(p))
      } finally s.close()
    }
  }
}
