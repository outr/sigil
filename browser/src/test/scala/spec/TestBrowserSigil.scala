package spec

import fabric.rw.RW
import lightdb.store.CollectionManager
import lightdb.upgrade.DatabaseUpgrade
import profig.Profig
import rapid.Task
import sigil.{Sigil, SpaceId}
import sigil.browser.{BrowserCollections, BrowserSigil, WebBrowserMode}
import sigil.conversation.{ConversationView, TurnInput}
import sigil.db.{Model, SigilDB}
import sigil.embedding.{EmbeddingProvider, NoOpEmbeddingProvider}
import sigil.information.Information
import sigil.participant.{Participant, ParticipantId}
import sigil.provider.{Mode, Provider}
import sigil.secrets.SecretsCollections
import sigil.browser.tool.BrowserCoreTools
import sigil.tool.core.CoreTools
import sigil.tool.{InMemoryToolFinder, Tool, ToolFinder}
import sigil.vector.{NoOpVectorIndex, VectorIndex}
import spice.net.{TLDValidation, URL, url}

import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference

/** Concrete `SigilDB` for the browser-module tests — adds the cookie
  * jar store ([[BrowserCollections]]) and the secret store
  * ([[SecretsCollections]]) since [[BrowserSigil]] requires both. */
class TestBrowserDB(directory: Option[Path],
                   storeManager: CollectionManager,
                   upgrades: List[DatabaseUpgrade] = Nil)
  extends SigilDB(directory, storeManager, upgrades)
    with BrowserCollections
    with SecretsCollections

/** Test-only Sigil for `sigil-browser` specs. Mirrors the minimal
  * shape of `TestSecretsSigil` — no provider, no vector index, no app
  * participants. Mixes in [[BrowserSigil]] so the spec can exercise
  * controller resolution, registration round-trips, and cookie jar
  * persistence.
  *
  * Does NOT actually open Chrome — specs that need a live browser
  * gate themselves on Chrome availability and self-skip when not
  * present. */
object TestBrowserSigil extends Sigil with BrowserSigil {
  override type DB = TestBrowserDB

  override protected def buildDB(directory: Option[Path],
                                  storeManager: CollectionManager,
                                  upgrades: List[DatabaseUpgrade]): TestBrowserDB =
    new TestBrowserDB(directory, storeManager, upgrades)

  override def testMode: Boolean = true

  override protected def participantIds: List[RW[? <: ParticipantId]] = Nil
  override protected def spaceIds: List[RW[? <: SpaceId]] = Nil
  override protected def participants: List[RW[? <: Participant]] = Nil

  override protected def modes: List[Mode] = List(WebBrowserMode)

  /** Static tools include framework essentials + the seven primitive
    * browser tools so specs running an end-to-end agent flow can
    * resolve them via `find_capability` / direct toolNames. Specs
    * that don't want them override `staticTools` again to `Nil`. */
  override def staticTools: List[Tool] =
    CoreTools.all.toList ++ BrowserCoreTools.all

  override val findTools: ToolFinder = {
    val inputs = staticTools.map(_.inputRW).distinctBy(_.definition.className)
    sigil.tool.DbToolFinder(this, inputs)
  }

  // Mutable provider hook — specs install a real provider via setProvider.
  private val providerRef: AtomicReference[() => Task[Provider]] =
    new AtomicReference(() => Task.error(new RuntimeException(
      "TestBrowserSigil.setProvider was not called — no provider configured")))

  // accessibleSpaces hook — specs that need authz wire their own.
  // Default authorizes GlobalSpace so the structural-query browser tools
  // can read back HTML they saved via Sigil.storeBytes(GlobalSpace, ...).
  private val accessibleSpacesRef: AtomicReference[List[ParticipantId] => Task[Set[SpaceId]]] =
    new AtomicReference(_ => Task.pure(Set[SpaceId](sigil.GlobalSpace)))

  def setProvider(p: => Task[Provider]): Unit = providerRef.set(() => p)
  def setAccessibleSpaces(f: List[ParticipantId] => Task[Set[SpaceId]]): Unit =
    accessibleSpacesRef.set(f)

  override def providerFor(modelId: lightdb.id.Id[Model], chain: List[ParticipantId]): Task[Provider] =
    providerRef.get().apply()

  override def accessibleSpaces(chain: List[ParticipantId]): Task[Set[SpaceId]] =
    accessibleSpacesRef.get().apply(chain)

  override def curate(view: ConversationView,
                      modelId: lightdb.id.Id[Model],
                      chain: List[ParticipantId]): Task[TurnInput] =
    Task.pure(TurnInput(view))

  override def getInformation(id: lightdb.id.Id[Information]): Task[Option[Information]] = Task.pure(None)
  override def putInformation(information: Information): Task[Unit] = Task.unit
  override def compressionMemorySpace(conversationId: lightdb.id.Id[sigil.conversation.Conversation]): Task[Option[SpaceId]] =
    Task.pure(None)

  private val wireInterceptorRef: AtomicReference[spice.http.client.intercept.Interceptor] =
    new AtomicReference(spice.http.client.intercept.Interceptor.empty)

  override def wireInterceptor: spice.http.client.intercept.Interceptor =
    wireInterceptorRef.get()

  override val embeddingProvider: EmbeddingProvider = NoOpEmbeddingProvider
  override val vectorIndex: VectorIndex = NoOpVectorIndex

  /** Local llama.cpp host for live-LLM specs. Defaults to the public
    * `https://llama.voidcraft.ai` host; override with the
    * `sigil.llamacpp.host` profig key (or `SIGIL_LLAMACPP_HOST` env)
    * to point at a local server. */
  lazy val llamaCppHost: URL =
    Profig("sigil.llamacpp.host").asOr[URL](url"https://llama.voidcraft.ai")

  def initFor(testClassName: String): Unit = {
    val name = testClassName.replace("$", "")
    val dbPath = Path.of("db", "test", name)
    deleteRecursive(dbPath)
    Profig.merge(fabric.obj("sigil" -> fabric.obj("dbPath" -> fabric.str(dbPath.toString))))
    instance.sync()
    // Per-suite wire log — same convention as TestSigil.
    import fabric.rw.*
    val wireDir = Profig("sigil.wire.log.dir").opt[String].getOrElse("target/wire-logs")
    val wirePath = Path.of(wireDir, s"$name.jsonl")
    if (java.nio.file.Files.exists(wirePath)) java.nio.file.Files.delete(wirePath)
    wireInterceptorRef.set(sigil.provider.debug.JsonLinesInterceptor(wirePath))
    ()
  }

  private def deleteRecursive(path: Path): Unit = {
    if (java.nio.file.Files.exists(path)) {
      val s = java.nio.file.Files.walk(path)
      try {
        import scala.jdk.CollectionConverters.*
        s.iterator().asScala.toList.reverse.foreach(p => java.nio.file.Files.deleteIfExists(p))
      } finally s.close()
    }
  }
}
