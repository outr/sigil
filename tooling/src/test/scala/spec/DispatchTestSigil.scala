package spec

import fabric.rw.*
import lightdb.id.Id
import lightdb.store.CollectionManager
import lightdb.upgrade.DatabaseUpgrade
import profig.Profig
import rapid.Task
import sigil.conversation.{Conversation, TurnInput}
import sigil.db.{Model, SigilDB}
import sigil.embedding.{EmbeddingProvider, NoOpEmbeddingProvider}
import sigil.information.Information
import sigil.participant.{Participant, ParticipantId}
import sigil.provider.Provider
import sigil.signal.Signal
import sigil.tool.Tool
import sigil.vector.{NoOpVectorIndex, VectorIndex}
import sigil.{Sigil, SpaceId}

import java.nio.file.{Files, Path}

/**
 * Shared test fixture for the tooling/dispatch test suite. Pulled
 * out of the original [[DispatchWorkersSpec]] so the container /
 * dispatch tests can share one Sigil subclass and one DB layer
 * without re-declaring the boilerplate.
 *
 * Does NOT mix in [[sigil.workflow.WorkflowSigil]] — the new
 * dispatch_workers tool requires it and surfaces a didactic
 * Failure when absent (covered by the dedicated DispatchWorkersSpec).
 */
val DispatchTestTopicId: Id[sigil.conversation.Topic] =
  Id[sigil.conversation.Topic]("dispatch-test-topic")

case object DispatchTestUser extends ParticipantId {
  override def value: String = "dispatch-test-user"
}

case object DispatchTestSpace extends SpaceId {
  override def value: String = "dispatch-test-space"
}

class DispatchTestDB(directory: Option[Path],
                     storeManager: CollectionManager,
                     upgrades: List[DatabaseUpgrade] = Nil)
  extends SigilDB(directory, storeManager, upgrades)

object DispatchTestSigil extends Sigil {
  override type DB = DispatchTestDB
  override protected def buildDB(directory: Option[Path],
                                  storeManager: CollectionManager,
                                  upgrades: List[DatabaseUpgrade]): DispatchTestDB =
    new DispatchTestDB(directory, storeManager, upgrades)

  override def testMode: Boolean = true

  override protected def signalRegistrations: List[RW[? <: Signal]] = Nil
  override protected def participantIds: List[RW[? <: ParticipantId]] =
    List(RW.static(DispatchTestUser))
  override protected def spaceIds: List[RW[? <: SpaceId]] =
    List(RW.static(DispatchTestSpace))
  override protected def participants: List[RW[? <: Participant]] = Nil

  override def staticTools: List[Tool] =
    super.staticTools :+ new sigil.tool.fs.GrepTool(new sigil.tool.fs.LocalFileSystemContext(basePath = None))

  private val providerRef = new java.util.concurrent.atomic.AtomicReference[() => Task[Provider]](
    () => Task.error(new RuntimeException("DispatchTestSigil — no provider configured"))
  )
  def setProvider(p: => Task[Provider]): Unit = providerRef.set(() => p)

  def reset(): Unit = {
    providerRef.set(() => Task.error(new RuntimeException("DispatchTestSigil — provider not set")))
  }

  override def providerFor(modelId: Id[Model], chain: List[ParticipantId]): Task[Provider] =
    providerRef.get()()

  override def curate(conversationId: Id[Conversation],
                      modelId: Id[Model],
                      chain: List[ParticipantId]): Task[TurnInput] =
    Task.pure(TurnInput(conversationId = conversationId))

  override def getInformation(id: Id[Information]): Task[Option[Information]] = Task.pure(None)
  override def putInformation(information: Information): Task[Unit] = Task.unit
  override def compressionMemorySpace(conversationId: Id[Conversation]): Task[Option[SpaceId]] =
    Task.pure(None)

  override val embeddingProvider: EmbeddingProvider = NoOpEmbeddingProvider
  override val vectorIndex: VectorIndex = NoOpVectorIndex

  override def wireInterceptor: spice.http.client.intercept.Interceptor =
    spice.http.client.intercept.Interceptor.empty

  def initFor(testClassName: String): Unit = {
    val name = testClassName.replace("$", "")
    val dbPath = Path.of("db", "test", name)
    deleteRecursive(dbPath)
    Profig.merge(fabric.obj("sigil" -> fabric.obj("dbPath" -> fabric.str(dbPath.toString))))
    instance.sync()
    ()
  }

  private def deleteRecursive(path: Path): Unit = {
    if (Files.exists(path)) {
      val s = Files.walk(path)
      try {
        import scala.jdk.CollectionConverters.*
        s.iterator().asScala.toList.reverse.foreach(p => Files.deleteIfExists(p))
      } finally s.close()
    }
  }
}
