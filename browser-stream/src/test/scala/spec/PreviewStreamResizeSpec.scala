package spec

import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import robobrowser.stream.{SignalMessage, StreamConfig}
import robobrowser.stream.gst.GstEngine
import sigil.browser.stream.{PreviewSignal, PreviewStreamSession}
import sigil.conversation.Conversation

import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters.*

/**
 * Per-stream render targets on the WebRTC rung, end to end on a real
 * headful Chrome under Xvfb.
 *
 * A portrait request lays the page out as a phone and streams video of
 * exactly that shape — the display it runs on is a bound, not the
 * resolution. Resizing mid-preview reconfigures the live pipeline: the
 * page relays out and the stats carry the new size, but the session
 * signals no second offer, so a viewer keeps rendering the track it
 * already negotiated rather than re-answering or re-subscribing.
 *
 * Self-skips (with the reason) when the host can't stream.
 */
class PreviewStreamResizeSpec extends AnyWordSpec with Matchers with BeforeAndAfterAll {

  TestStreamBrowserSigil.initFor(getClass.getSimpleName)

  private val chromeAvailable: Boolean =
    List("/usr/bin/google-chrome", "/usr/bin/google-chrome-stable", "/usr/bin/chromium",
      "/usr/local/bin/google-chrome", "/opt/google/chrome/chrome")
      .exists(p => new java.io.File(p).canExecute)

  private val xvfbAvailable: Boolean =
    sys.env.getOrElse("PATH", "").split(java.io.File.pathSeparatorChar)
      .exists(dir => new java.io.File(dir, "Xvfb").canExecute)

  private val gstReason: Option[String] = GstEngine.initResult match {
    case Left(error) => Some(s"GStreamer unavailable: $error")
    case Right(_) =>
      if (GstEngine.missingElements.nonEmpty) Some(s"missing GStreamer elements: ${GstEngine.missingElements.mkString(", ")}")
      else if (GstEngine.selectedEncoder.isEmpty) Some("no usable H.264 encoder")
      else None
  }

  private val skipReason: Option[String] =
    if (!chromeAvailable) Some("Chrome/Chromium not installed")
    else if (!xvfbAvailable) Some("Xvfb not installed — a virtual display is required for WebRTC capture")
    else gstReason

  private val convId = Conversation.id("preview-resize")
  private lazy val fixture: PreviewFixtureServer = new PreviewFixtureServer
  private val received = new ConcurrentLinkedQueue[PreviewSignal]()

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    if (skipReason.isEmpty) {
      TestStreamBrowserSigil.usePreviewConfig(TestStreamBrowserSigil.virtualDisplayPreviewConfig)
      fixture.start().sync()
      TestStreamBrowserSigil.signals.foreach {
        case signal: PreviewSignal => received.add(signal); ()
        case _ => ()
      }.drain.startUnit()
    }
  }

  override protected def afterAll(): Unit = {
    if (skipReason.isEmpty) {
      try TestStreamBrowserSigil.disposeStreamBrowserController(convId).sync() catch { case _: Throwable => () }
      fixture.stop().sync()
    }
    TestStreamBrowserSigil.shutdown.sync()
    super.afterAll()
  }

  private def offers(streamId: String): List[String] = received.iterator().asScala.collect {
    case signal if signal.streamId == streamId => signal.message
  }.collect {
    case SignalMessage.Offer(sdp) => sdp
  }.toList

  private def awaitOffers(streamId: String, atLeast: Int, timeoutMs: Long): List[String] = {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (offers(streamId).size < atLeast && System.currentTimeMillis() < deadline) Thread.sleep(100)
    offers(streamId)
  }

  private def evalString(expression: String): String =
    TestStreamBrowserSigil.streamBrowserController(convId)
      .flatMap(_.run(_.eval(s"return $expression")))
      .map(_("result")("value").asString)
      .sync()

  private def evalBoolean(expression: String): Boolean =
    TestStreamBrowserSigil.streamBrowserController(convId)
      .flatMap(_.run(_.eval(s"return $expression")))
      .map(_("result")("value").asBoolean)
      .sync()

  "previewStreamFor with a portrait render target" should {

    "stream the target's own shape and resize in place, without renegotiating" in {
      skipReason.foreach(reason => cancel(s"Skipping live preview resize test: $reason"))

      val controller = TestStreamBrowserSigil
        .streamBrowserController(convId, StreamConfig(width = Some(390), height = Some(844)))
        .sync()
      controller.run(_.navigate(fixture.url)).sync()

      // The display grew only where the portrait target needed it to, so
      // the later landscape resize still fits inside it
      controller.browser.virtualDisplay.map(_.width) shouldBe Some(1280)
      controller.browser.virtualDisplay.map(_.height) shouldBe Some(844)

      val session = TestStreamBrowserSigil.previewStreamFor(convId, StreamConfig(
        width = Some(390), height = Some(844), maxFps = 30
      )).sync()
      val webRtc = session match {
        case w: PreviewStreamSession.WebRtc => w
        case other => fail(s"expected a WebRTC session, got $other")
      }

      try {
        evalString("window.innerWidth + 'x' + window.innerHeight") shouldBe "390x844"
        evalBoolean("window.mobile") shouldBe true

        val first = awaitOffers(webRtc.streamId, atLeast = 1, timeoutMs = 60_000)
        first should not be empty
        first.head should include("m=video")

        val portrait = webRtc.stats.sync()
        portrait.width shouldBe 390
        portrait.height shouldBe 844
        portrait.height should be > portrait.width

        TestStreamBrowserSigil.resizePreview(convId, 1280, 820).sync()

        evalString("window.innerWidth + 'x' + window.innerHeight") shouldBe "1280x820"
        evalBoolean("window.mobile") shouldBe false

        // The pipeline was reconfigured, not rebuilt: no second offer, so
        // the viewer's peer connection is never asked to re-handshake
        awaitOffers(webRtc.streamId, atLeast = 2, timeoutMs = 5_000) should have size 1

        TestStreamBrowserSigil.previewStreamsFor(convId).map(_.streamId) should contain(webRtc.streamId)

        val landscape = webRtc.stats.sync()
        landscape.width shouldBe 1280
        landscape.height shouldBe 820
      } finally {
        webRtc.stop.sync()
      }
    }

    "grow past the first render target within a max-declared envelope, and clamp beyond it" in {
      skipReason.foreach(reason => cancel(s"Skipping live preview resize test: $reason"))

      val growConv = Conversation.id(s"resize-grow-${rapid.Unique()}")
      TestStreamBrowserSigil.withDB(_.conversations.transaction(_.upsert(
        Conversation(_id = growConv, topics = List(TestTopicEntry))))).sync()
      // First pane is portrait-small, but the declared max sizes the
      // framebuffer envelope up front — the later landscape grow that
      // used to raise DisplayResizeUnsupportedException just works.
      val controller = TestStreamBrowserSigil
        .streamBrowserController(growConv, StreamConfig(
          width = Some(390), height = Some(844),
          maxWidth = Some(1600), maxHeight = Some(1000)
        )).sync()
      controller.run(_.navigate(fixture.url)).sync()
      controller.browser.virtualDisplay.map(_.width).get should be >= 1600
      controller.browser.virtualDisplay.map(_.height).get should be >= 1000

      val session = TestStreamBrowserSigil.previewStreamFor(growConv, StreamConfig(
        width = Some(390), height = Some(844), maxFps = 30
      )).sync()
      val webRtc = session match {
        case w: PreviewStreamSession.WebRtc => w
        case other => fail(s"expected a WebRTC session, got $other")
      }
      try {
        TestStreamBrowserSigil.resizePreview(growConv, 1600, 1000).sync()
        val grown = webRtc.stats.sync()
        grown.width shouldBe 1600
        grown.height shouldBe 1000

        // Beyond the envelope: clamped and served, never aborted.
        TestStreamBrowserSigil.resizePreview(growConv, 3840, 2160).sync()
        val clamped = webRtc.stats.sync()
        clamped.width shouldBe 1600
        clamped.height shouldBe 1000
        TestStreamBrowserSigil.previewStreamsFor(growConv).map(_.streamId) should contain(webRtc.streamId)
        // Two resizes, still the one offer the viewer answered at start
        awaitOffers(webRtc.streamId, atLeast = 2, timeoutMs = 5_000) should have size 1
      } finally {
        webRtc.stop.sync()
        TestStreamBrowserSigil.disposeStreamBrowserController(growConv).sync()
      }
    }

    "warn and do nothing when no preview is live" in {
      skipReason.foreach(reason => cancel(s"Skipping live preview resize test: $reason"))

      TestStreamBrowserSigil.previewStreamsFor(convId) shouldBe empty
      TestStreamBrowserSigil.resizePreview(convId, 800, 600).sync()
      TestStreamBrowserSigil.previewStreamsFor(convId) shouldBe empty
    }
  }
}
