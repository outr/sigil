package spec

import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import robobrowser.stream.{RenderSize, ResizeBehavior, SignalMessage, StreamConfig}
import robobrowser.stream.gst.GstEngine
import sigil.browser.stream.{PreviewSignal, PreviewStreamSession}
import sigil.conversation.Conversation

import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters.*

/**
 * Per-stream render targets on the WebRTC rung, end to end on a real
 * headful Chrome under Xvfb.
 *
 * A portrait request lays the page out as a phone and streams exactly
 * that shape — the display it runs on is a bound, not the resolution.
 * Resizing mid-preview reconfigures the live pipeline: the page relays
 * out and the stats' render size carries the new target, but the session
 * signals no second offer, so a viewer keeps rendering the track it
 * already negotiated rather than re-answering or re-subscribing.
 *
 * The transmitted frame is a separate number from the render target and
 * is asserted per encoder branch: a software branch re-pins its encoder
 * so the frame becomes the target, while a hardware branch holds a fixed
 * encode canvas for the session's lifetime and borders the target into
 * it. The render-target assertions read the same on both.
 *
 * Self-skips (with the reason) when the host can't stream.
 */
class PreviewStreamResizeSpec extends AnyWordSpec with Matchers with BeforeAndAfterAll {

  TestStreamBrowserSigil.initFor(getClass.getSimpleName)

  private val chromeAvailable: Boolean =
    List(
      "/usr/bin/google-chrome",
      "/usr/bin/google-chrome-stable",
      "/usr/bin/chromium",
      "/usr/local/bin/google-chrome",
      "/opt/google/chrome/chrome")
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
      try TestStreamBrowserSigil.disposeStreamBrowserController(convId).sync()
      catch { case _: Throwable => () }
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

      val session = TestStreamBrowserSigil.previewStreamFor(
        convId,
        StreamConfig(
          width = Some(390),
          height = Some(844),
          maxFps = 30
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

        // The render target is what was asked for, and it keeps its own shape.
        // This used to be asserted on stats.width/height, which is the
        // transmitted frame — the same number only while every encoder was
        // re-pinned per resize. A hardware branch holds its encode canvas fixed
        // and borders the target into it, so "what was asked for" now has its
        // own field and the frame size is asserted per branch below.
        val portrait = webRtc.stats.sync()
        val behavior = portrait.resizeBehavior
        portrait.renderSize shouldBe RenderSize(390, 844)
        portrait.placement.content.height should be > portrait.placement.content.width
        val transmittedAtPortrait = RenderSize(portrait.width, portrait.height)

        TestStreamBrowserSigil.resizePreview(convId, 1280, 820).sync()

        evalString("window.innerWidth + 'x' + window.innerHeight") shouldBe "1280x820"
        evalBoolean("window.mobile") shouldBe false

        // The pipeline was reconfigured, not rebuilt: no second offer, so
        // the viewer's peer connection is never asked to re-handshake
        awaitOffers(webRtc.streamId, atLeast = 2, timeoutMs = 5_000) should have size 1

        TestStreamBrowserSigil.previewStreamsFor(convId).map(_.streamId) should contain(webRtc.streamId)

        val landscape = webRtc.stats.sync()
        landscape.renderSize shouldBe RenderSize(1280, 820)
        RenderSize(landscape.width, landscape.height) shouldBe
          (behavior match {
            case ResizeBehavior.Reconfigure => RenderSize(1280, 820)
            // The encoder was never asked to change, so the viewer keeps decoding
            // the frame shape it negotiated — there is nothing for a per-resolution
            // surface pool to accept silently and then ignore
            case ResizeBehavior.FixedCanvas => transmittedAtPortrait
          })
      } finally
        webRtc.stop.sync()
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
        .streamBrowserController(
          growConv,
          StreamConfig(
            width = Some(390),
            height = Some(844),
            maxWidth = Some(1600),
            maxHeight = Some(1000)
          )).sync()
      controller.run(_.navigate(fixture.url)).sync()
      controller.browser.virtualDisplay.map(_.width).get should be >= 1600
      controller.browser.virtualDisplay.map(_.height).get should be >= 1000

      val session = TestStreamBrowserSigil.previewStreamFor(
        growConv,
        StreamConfig(
          width = Some(390),
          height = Some(844),
          maxFps = 30
        )).sync()
      val webRtc = session match {
        case w: PreviewStreamSession.WebRtc => w
        case other => fail(s"expected a WebRTC session, got $other")
      }
      try {
        // The envelope bounds the render target — the number the caller asked
        // for. It used to be read off stats.width/height, which is the
        // transmitted frame; on a fixed-canvas branch that frame is the canvas
        // and says nothing about what the pane was resized to.
        TestStreamBrowserSigil.resizePreview(growConv, 1600, 1000).sync()
        val grown = webRtc.stats.sync()
        grown.renderSize shouldBe RenderSize(1600, 1000)

        // Beyond the envelope: clamped and served, never aborted.
        TestStreamBrowserSigil.resizePreview(growConv, 3840, 2160).sync()
        val clamped = webRtc.stats.sync()
        clamped.renderSize shouldBe RenderSize(1600, 1000)
        // Whatever the branch, the frame the viewer negotiated is still the
        // frame it is receiving after two resizes
        RenderSize(clamped.width, clamped.height) shouldBe
          (clamped.resizeBehavior match {
            case ResizeBehavior.Reconfigure => RenderSize(1600, 1000)
            case ResizeBehavior.FixedCanvas => RenderSize(grown.width, grown.height)
          })
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
