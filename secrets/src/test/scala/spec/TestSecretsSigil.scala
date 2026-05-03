package spec

import fabric.rw.RW
import lightdb.store.CollectionManager
import lightdb.upgrade.DatabaseUpgrade
import profig.Profig
import rapid.Task
import sigil.{Sigil, SpaceId, TurnContext}
import sigil.conversation.{ConversationView, TurnInput}
import sigil.db.{Model, SigilDB}
import sigil.embedding.{EmbeddingProvider, NoOpEmbeddingProvider}
import sigil.information.{InMemoryInformation, Information}
import sigil.participant.{Participant, ParticipantId}
import sigil.provider.{Mode, Provider}
import sigil.secrets.{SecretsCollections, SecretsSigil}
import sigil.signal.Signal
import sigil.tool.{InMemoryToolFinder, Tool, ToolFinder, ToolInput}
import sigil.vector.{NoOpVectorIndex, VectorIndex}

import java.nio.file.Path

/** Test-only submitter participant id used by `SecretSubmissionFlowSpec`. */
case object SubmittingUser extends ParticipantId {
  override val value: String = "submitting-user"
}

/** Concrete DB for the secrets-module tests — `SigilDB` plus
  * [[SecretsCollections]]. */
class TestSecretsDB(directory: Option[Path],
                    storeManager: CollectionManager,
                    upgrades: List[DatabaseUpgrade] = Nil)
  extends SigilDB(directory, storeManager, upgrades)
    with SecretsCollections

/** Test-only Sigil for the `sigil-secrets` spec. Refines `type DB` to
  * a [[TestSecretsDB]] so `db.secrets` is reachable from
  * `DatabaseSecretStore` inside the test. Mirrors the bare-minimum
  * shape of `TestSigil` (no app participants / spaces / modes); the
  * test only exercises the secret store, not the full conversation
  * surface. */
object TestSecretsSigil extends Sigil with SecretsSigil {
  // See `core/.../TestSigil.scala` for context — disable rapid's
  // tracing in the test JVM to dodge a JIT-pressure flake on CI.
  rapid.trace.Trace.Enabled = false

  override type DB = TestSecretsDB
  override protected def buildDB(directory: Option[Path],
                                  storeManager: CollectionManager,
                                  upgrades: List[DatabaseUpgrade]): TestSecretsDB =
    new TestSecretsDB(directory, storeManager, upgrades)

  override def testMode: Boolean = true

  /** Fixed test-only crypto key. Real apps source this from config,
    * env, KMS, Vault, etc. — see [[SecretsSigil.secretStoreKey]]
    * for the patterns. The literal here is fine for tests because
    * the per-suite DB is wiped in [[initFor]]. */
  override def secretStoreKey: String = "test-crypto-key-do-not-use-in-production"

  override protected def signalRegistrations: List[RW[? <: Signal]] = Nil
  override protected def participantIds: List[RW[? <: ParticipantId]] =
    List(RW.static(SubmittingUser))
  override protected def spaceIds: List[RW[? <: SpaceId]] = Nil
  override protected def participants: List[RW[? <: Participant]] = Nil

  override val findTools: ToolFinder = InMemoryToolFinder(Nil)
  override def staticTools: List[Tool] = Nil

  override def curate(view: ConversationView,
                      modelId: lightdb.id.Id[Model],
                      chain: List[ParticipantId]): Task[TurnInput] =
    Task.pure(TurnInput(view))

  override def getInformation(id: lightdb.id.Id[Information]): Task[Option[Information]] = Task.pure(None)
  override def putInformation(information: Information): Task[Unit] = Task.unit
  override def compressionMemorySpace(conversationId: lightdb.id.Id[sigil.conversation.Conversation]): Task[Option[SpaceId]] =
    Task.pure(None)

  override def wireInterceptor: spice.http.client.intercept.Interceptor =
    spice.http.client.intercept.Interceptor.empty

  override def providerFor(modelId: lightdb.id.Id[Model], chain: List[ParticipantId]): Task[Provider] =
    Task.error(new RuntimeException("TestSecretsSigil: no provider configured (the secrets spec doesn't need one)"))

  override val embeddingProvider: EmbeddingProvider = NoOpEmbeddingProvider
  override val vectorIndex: VectorIndex = NoOpVectorIndex

  /** Initialize the DB at a per-suite path; mirrors `TestSigil.initFor`. */
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
