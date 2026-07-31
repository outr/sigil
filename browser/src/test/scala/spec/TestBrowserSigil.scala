package spec

import fabric.rw.RW
import lightdb.store.CollectionManager
import lightdb.upgrade.DatabaseUpgrade
import profig.Profig
import rapid.Task
import sigil.{Sigil, SpaceId}
import sigil.browser.{BrowserCollections, BrowserSigil, WebBrowserMode}
import sigil.conversation.{TurnInput}
import sigil.db.{Model, SigilDB}
import sigil.embedding.{EmbeddingProvider, NoOpEmbeddingProvider}
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

  /** CI runners (GitHub Actions ubuntu-24.04) restrict unprivileged
    * user namespaces via AppArmor, which kills Chrome's sandbox at
    * launch — the CDP debug port never opens ("Connection refused:
    * localhost:<port>/json" retry storms in the CI log), navigation
    * silently times out, and captures come back as an empty document.
    * Tests run Chrome unsandboxed; production keeps [[BrowserSigil]]'s
    * default sandboxed config. */
  override def browserConfig: robobrowser.RoboBrowserConfig = robobrowser.RoboBrowserConfig(
    browserConfig = robobrowser.BrowserConfig(headless = true, disableGPU = true, noSandbox = true),
    enableNetworkEvents = false,
    enableDOMEvents = false
  )

  /** Static test-only key — `BrowserSigil` mixes in `SecretsSigil`,
    * which requires this. Production apps source it from a real
    * secret manager; tests get a deterministic literal. */
  override def secretStoreKey: String = "test-crypto-key-do-not-use-in-production"

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

  override val findTools: ToolFinder = sigil.tool.DbToolFinder(this)

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

  // Sigil #403 — settable navigation guard so specs can simulate a host that
  // blacklists URLs without standing up a second Sigil.
  private val navGuardRef: AtomicReference[(URL, List[ParticipantId], lightdb.id.Id[sigil.conversation.Conversation]) => Task[Option[String]]] =
    new AtomicReference((_, _, _) => Task.pure(None))
  override def navigationGuard(url: URL,
                               chain: List[ParticipantId],
                               conversationId: lightdb.id.Id[sigil.conversation.Conversation]): Task[Option[String]] =
    navGuardRef.get().apply(url, chain, conversationId)
  def setNavigationGuard(f: (URL, List[ParticipantId], lightdb.id.Id[sigil.conversation.Conversation]) => Task[Option[String]]): Unit =
    navGuardRef.set(f)
  def resetNavigationGuard(): Unit = navGuardRef.set((_, _, _) => Task.pure(None))

  override def modelResolver: sigil.provider.ModelResolver = (modelId: lightdb.id.Id[Model]) =>
    cache.find(modelId).map(sigil.provider.ProviderModel(providerRef.get().apply().sync(), _))

  override def accessibleSpaces(chain: List[ParticipantId]): Task[Set[SpaceId]] =
    accessibleSpacesRef.get().apply(chain)

  // Mirror the framework default — StandardContextCurator builds
  // TurnInput frames from db.events. Without this the curator returned
  // an empty TurnInput, the user's published Message never made it to
  // the provider's `messages`, and OpenAI's Responses API rejected the
  // empty `input: []` with HTTP 400. NewsArticleDetectionSpec then sat
  // through its 5-minute polling deadline waiting for an agent reply
  // that would never come.
  override def curate(conversationId: lightdb.id.Id[sigil.conversation.Conversation],
                      modelId: lightdb.id.Id[Model],
                      chain: List[ParticipantId]): Task[TurnInput] =
    sigil.conversation.compression.StandardContextCurator(this).curate(conversationId, modelId, chain)

  // getInformation / putInformation use the framework defaults
  // (StoredInformation-backed via db.storedInformations) — browser_save_html
  // registers a lookup-able Information (sigil #377), and the e2e spec
  // reads it back through this path.
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
