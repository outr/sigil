package spec

import fabric.rw.RW
import lightdb.store.CollectionManager
import lightdb.upgrade.DatabaseUpgrade
import profig.Profig
import rapid.Task
import sigil.{GlobalSpace, Sigil, SpaceId}
import sigil.conversation.{Conversation, ConversationView, TurnInput}
import sigil.db.{DefaultSigilDB, Model, SigilDB}
import sigil.embedding.{EmbeddingProvider, NoOpEmbeddingProvider}
import sigil.information.Information
import sigil.participant.{Participant, ParticipantId}
import sigil.provider.{Mode, Provider}
import sigil.script.ScriptSigil
import sigil.signal.Signal
import sigil.tool.{InMemoryToolFinder, Tool, ToolFinder, ToolInput}
import sigil.vector.{NoOpVectorIndex, VectorIndex}

import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference

case object TestProjectSpace extends SpaceId {
  override val value: String = "test-project"
}

object TestScriptSigil extends Sigil with ScriptSigil {
  override type DB = DefaultSigilDB

  override protected def buildDB(directory: Option[Path],
                                  storeManager: CollectionManager,
                                  upgrades: List[DatabaseUpgrade]): DefaultSigilDB =
    new DefaultSigilDB(directory, storeManager, upgrades)

  override def testMode: Boolean = true

  override protected def spaceIds: List[RW[? <: SpaceId]] = List(RW.static(TestProjectSpace))

  override protected def participantIds: List[RW[? <: ParticipantId]] =
    List(RW.static(TestScriptUser), RW.static(TestScriptAgent))

  override val findTools: ToolFinder = InMemoryToolFinder(Nil)
  override def staticTools: List[Tool] = Nil

  override def curate(view: ConversationView,
                      modelId: lightdb.id.Id[Model],
                      chain: List[ParticipantId]): Task[TurnInput] = Task.pure(TurnInput(view))

  override def getInformation(id: lightdb.id.Id[Information]): Task[Option[Information]] = Task.pure(None)
  override def putInformation(information: Information): Task[Unit] = Task.unit
  override def compressionMemorySpace(conversationId: lightdb.id.Id[Conversation]): Task[Option[SpaceId]] =
    Task.pure(None)

  override def providerFor(modelId: lightdb.id.Id[Model], chain: List[ParticipantId]): Task[Provider] =
    Task.error(new RuntimeException("TestScriptSigil: no provider configured"))

  override val embeddingProvider: EmbeddingProvider = NoOpEmbeddingProvider
  override val vectorIndex: VectorIndex = NoOpVectorIndex

  // Per-test space-policy override hook. Default is the framework default
  // (always GlobalSpace, ignore agent hints). Specs swap behavior by
  // setting `spaceResolverRef`.
  private val defaultSpaceResolver: (List[ParticipantId], Option[String]) => Task[SpaceId] =
    (_, _) => Task.pure(GlobalSpace)
  private val spaceResolverRef =
    new AtomicReference[(List[ParticipantId], Option[String]) => Task[SpaceId]](defaultSpaceResolver)

  override def scriptToolSpace(chain: List[ParticipantId],
                               requested: Option[String]): Task[SpaceId] =
    spaceResolverRef.get().apply(chain, requested)

  def setSpaceResolver(f: (List[ParticipantId], Option[String]) => Task[SpaceId]): Unit =
    spaceResolverRef.set(f)
  def resetSpaceResolver(): Unit = spaceResolverRef.set(defaultSpaceResolver)

  // accessibleSpaces hook is mutable for authz tests.
  private val defaultAccessible: List[ParticipantId] => Task[Set[SpaceId]] = _ => Task.pure(Set.empty)
  private val accessibleRef =
    new AtomicReference[List[ParticipantId] => Task[Set[SpaceId]]](defaultAccessible)

  override def accessibleSpaces(chain: List[ParticipantId]): Task[Set[SpaceId]] =
    accessibleRef.get().apply(chain)

  def setAccessible(f: List[ParticipantId] => Task[Set[SpaceId]]): Unit = accessibleRef.set(f)
  def resetAccessible(): Unit = accessibleRef.set(defaultAccessible)

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
