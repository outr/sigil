package spec

import fabric.rw.RW
import lightdb.store.CollectionManager
import lightdb.upgrade.DatabaseUpgrade
import profig.Profig
import rapid.Task
import sigil.{Sigil, SpaceId}
import sigil.conversation.{Conversation, TurnInput}
import sigil.db.Model
import sigil.embedding.{EmbeddingProvider, NoOpEmbeddingProvider}
import sigil.information.Information
import sigil.mcp.McpSigil
import sigil.participant.{Participant, ParticipantId}
import sigil.signal.Signal
import sigil.tool.{Tool, ToolFinder}
import sigil.vector.{NoOpVectorIndex, VectorIndex}

import java.nio.file.Path

/**
 * Shared MCP test host: a minimal [[Sigil]] with [[McpSigil]] mixed in,
 * an in-test DB path per suite, and no provider wiring. Specs that need
 * their own hooks (a custom [[sigil.mcp.McpClient]] factory, alternate
 * sampling) extend this and override just those members.
 */
abstract class TestMcpSigilBase extends Sigil with McpSigil {
  override type DB = TestMcpDB

  override protected def buildDB(directory: Option[Path],
                                 storeManager: CollectionManager,
                                 upgrades: List[DatabaseUpgrade]): TestMcpDB =
    new TestMcpDB(directory, storeManager, upgrades)

  override def testMode: Boolean = true

  override protected def signalRegistrations: List[RW[? <: Signal]] = Nil
  override protected def participantIds: List[RW[? <: ParticipantId]] = Nil
  override protected def spaceIds: List[RW[? <: SpaceId]] = Nil
  override protected def participants: List[RW[? <: Participant]] = Nil

  // Compose the MCP finder the way a real app does — its `toolIO`
  // contribution is what registers `JsonInput` (and the open ToolOutput
  // codec) into the polymorphic RWs at init.
  override def findTools: ToolFinder = mcpToolFinder
  override def staticTools: List[Tool] = Nil

  override def curate(conversationId: lightdb.id.Id[Conversation],
                      modelId: lightdb.id.Id[Model],
                      chain: List[ParticipantId]): Task[TurnInput] =
    Task.pure(TurnInput(conversationId = conversationId))

  override def getInformation(id: lightdb.id.Id[Information]): Task[Option[Information]] = Task.pure(None)
  override def putInformation(information: Information): Task[Unit] = Task.unit
  override def compressionMemorySpace(conversationId: lightdb.id.Id[Conversation]): Task[Option[SpaceId]] =
    Task.pure(None)

  override def wireInterceptor: spice.http.client.intercept.Interceptor =
    spice.http.client.intercept.Interceptor.empty

  override def modelResolver: sigil.provider.ModelResolver = _ => None

  override val embeddingProvider: EmbeddingProvider = NoOpEmbeddingProvider
  override val vectorIndex: VectorIndex = NoOpVectorIndex

  def initFor(testClassName: String): Unit = {
    val name = testClassName.replace("$", "")
    val dbPath = Path.of("db", "test", name)
    deleteRecursive(dbPath)
    Profig.merge(fabric.obj("sigil" -> fabric.obj("dbPath" -> fabric.str(dbPath.toString))))
    instance.sync()
    ()
  }

  private def deleteRecursive(path: Path): Unit =
    if (java.nio.file.Files.exists(path)) {
      val s = java.nio.file.Files.walk(path)
      try {
        import scala.jdk.CollectionConverters.*
        s.iterator().asScala.toList.reverse.foreach(p => java.nio.file.Files.deleteIfExists(p))
      } finally s.close()
    }
}
