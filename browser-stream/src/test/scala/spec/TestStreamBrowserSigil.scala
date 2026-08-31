package spec

import fabric.rw.RW
import lightdb.store.CollectionManager
import lightdb.upgrade.DatabaseUpgrade
import profig.Profig
import robobrowser.display.VirtualDisplayConfig
import robobrowser.{BrowserConfig, RoboBrowserConfig}
import sigil.browser.stream.StreamBrowserSigil
import sigil.db.Model
import sigil.embedding.{EmbeddingProvider, NoOpEmbeddingProvider}
import sigil.participant.{Participant, ParticipantId}
import sigil.provider.Mode
import sigil.tool.{Tool, ToolFinder}
import sigil.vector.{NoOpVectorIndex, VectorIndex}
import sigil.{Sigil, SpaceId}

import java.nio.file.Path
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}

/**
 * Test-only Sigil for the `sigil-browser-stream` specs. Reuses the
 * browser module's `TestBrowserDB` (cookie jars + secrets) since
 * [[StreamBrowserSigil]] refines `BrowserSigil`.
 *
 * The preview config and the fallback policy are settable so one suite
 * can drive both rungs of the ladder without standing up a second host.
 */
object TestStreamBrowserSigil extends Sigil with StreamBrowserSigil {
  override type DB = TestBrowserDB

  override protected def buildDB(directory: Option[Path],
                                 storeManager: CollectionManager,
                                 upgrades: List[DatabaseUpgrade]): TestBrowserDB =
    new TestBrowserDB(directory, storeManager, upgrades)

  override def testMode: Boolean = true

  /**
   * CI runners restrict unprivileged user namespaces, which kills
   * Chrome's sandbox at launch — tests run unsandboxed.
   */
  override def browserConfig: RoboBrowserConfig = RoboBrowserConfig(
    browserConfig = BrowserConfig(headless = true, disableGPU = true, noSandbox = true),
    enableNetworkEvents = false,
    enableDOMEvents = false
  )

  /**
   * Headless, no virtual display — the shape that can only ever reach
   * the screencast rung.
   */
  val headlessPreviewConfig: RoboBrowserConfig = browserConfig

  /**
   * Headful on a dedicated Xvfb display — the shape WebRTC needs. 720p
   * keeps the encoder's work modest on a shared CI box.
   */
  val virtualDisplayPreviewConfig: RoboBrowserConfig = browserConfig.copy(
    browserConfig = browserConfig.browserConfig.copy(headless = false),
    virtualDisplay = Some(VirtualDisplayConfig(width = 1280, height = 720))
  )

  private val previewConfigRef: AtomicReference[RoboBrowserConfig] =
    new AtomicReference(headlessPreviewConfig)
  private val fallbackRef: AtomicBoolean = new AtomicBoolean(true)

  override def streamBrowserConfig: RoboBrowserConfig = previewConfigRef.get()

  /**
   * The framework's unoverridden default — the clean-capture profile
   * specs assert against this, not the per-test override above.
   */
  lazy val defaultStreamBrowserConfig: RoboBrowserConfig = super.streamBrowserConfig
  override def streamFallbackToScreencast: Boolean = fallbackRef.get()

  def usePreviewConfig(config: RoboBrowserConfig): Unit = previewConfigRef.set(config)
  def setFallbackToScreencast(enabled: Boolean): Unit = fallbackRef.set(enabled)

  override def secretStoreKey: String = "test-crypto-key-do-not-use-in-production"

  override protected def participantIds: List[RW[? <: ParticipantId]] = Nil
  override protected def spaceIds: List[RW[? <: SpaceId]] = Nil
  override protected def participants: List[RW[? <: Participant]] = Nil
  override protected def modes: List[Mode] = Nil

  override def staticTools: List[Tool] = sigil.tool.core.CoreTools.all.toList
  override val findTools: ToolFinder = sigil.tool.DbToolFinder(this)

  override def modelResolver: sigil.provider.ModelResolver = (_: lightdb.id.Id[Model]) => None

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
