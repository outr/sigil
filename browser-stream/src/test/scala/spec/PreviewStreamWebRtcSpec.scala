package spec

import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import robobrowser.stream.SignalMessage
import robobrowser.stream.gst.GstEngine
import sigil.browser.stream.{PreviewSignal, PreviewSignalReply, PreviewStreamSession}
import sigil.conversation.Conversation
import sigil.participant.ParticipantId

import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters.*

/**
 * The WebRTC rung, end to end on a real headful Chrome under Xvfb: the
 * session negotiates, its offer reaches the conversation as a
 * [[PreviewSignal]], and a viewer's `bye` [[PreviewSignalReply]] tears
 * it down.
 *
 * Self-skips (with the reason) when the host can't stream — no Chrome,
 * no Xvfb, no GStreamer, missing plugins, or no usable H.264 encoder.
 * That is the same shape the fallback ladder reports at runtime, so a
 * skip here is exactly the condition that would have produced a
 * screencast preview instead.
 */
class PreviewStreamWebRtcSpec extends AnyWordSpec with Matchers with BeforeAndAfterAll {

  TestStreamBrowserSigil.initFor(getClass.getSimpleName)

  private case object SpecViewer extends ParticipantId {
    override val value: String = "spec-viewer"
  }

  private val chromeAvailable: Boolean =
    List("/usr/bin/google-chrome", "/usr/bin/google-chrome-stable", "/usr/bin/chromium", "/usr/local/bin/google-chrome")
      .exists(p => new java.io.File(p).canExecute)

  private val xvfbAvailable: Boolean =
    sys.env.getOrElse("PATH", "").split(java.io.File.pathSeparatorChar)
      .exists(dir => new java.io.File(dir, "Xvfb").canExecute)

  private val gstReason: Option[String] = GstEngine.initResult match {
    case Left(error) => Some(s"GStreamer unavailable: $error")
    case Right(_) =>
      if (GstEngine.missingElements.nonEmpty)
        Some(s"missing GStreamer elements: ${GstEngine.missingElements.mkString(", ")}")
      else if (GstEngine.selectedEncoder.isEmpty)
        Some(s"no usable H.264 encoder (tried ${GstEngine.encoderPreference.mkString(", ")})")
      else None
  }

  private val skipReason: Option[String] =
    if (!chromeAvailable) Some("Chrome/Chromium not installed")
    else if (!xvfbAvailable) Some("Xvfb not installed — a virtual display is required for WebRTC capture")
    else gstReason

  private val convId = Conversation.id("preview-webrtc")
  private lazy val fixture: PreviewFixtureServer = new PreviewFixtureServer
  private val received = new ConcurrentLinkedQueue[PreviewSignal]()

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    if (skipReason.isEmpty) {
      TestStreamBrowserSigil.usePreviewConfig(TestStreamBrowserSigil.virtualDisplayPreviewConfig)
      fixture.start().sync()
      // Background collector — ends naturally when `shutdown` closes the hub.
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

  private def awaitSignal(streamId: String, timeoutMs: Long)
                         (matches: SignalMessage => Boolean): Option[PreviewSignal] = {
    val deadline = System.currentTimeMillis() + timeoutMs
    var found: Option[PreviewSignal] = None
    while (found.isEmpty && System.currentTimeMillis() < deadline) {
      found = received.iterator().asScala.find(s => s.streamId == streamId && matches(s.message))
      if (found.isEmpty) Thread.sleep(100)
    }
    found
  }

  "previewStreamFor on a virtual-display browser" should {

    "negotiate a WebRTC session, publish its offer, and stop on the viewer's bye" in {
      skipReason.foreach(reason => cancel(s"Skipping WebRTC preview test: $reason"))

      val controller = TestStreamBrowserSigil.streamBrowserController(convId).sync()
      controller.run(_.navigate(fixture.url)).sync()

      TestStreamBrowserSigil.previewStreamAvailability(convId).sync() shouldBe None

      val session = TestStreamBrowserSigil.previewStreamFor(convId).sync()
      val webRtc = session match {
        case w: PreviewStreamSession.WebRtc => w
        case other => fail(s"expected a WebRTC session, got $other")
      }
      TestStreamBrowserSigil.previewStreamsFor(convId).map(_.streamId) should contain (webRtc.streamId)

      val offer = awaitSignal(webRtc.streamId, timeoutMs = 60_000) {
        case _: SignalMessage.Offer => true
        case _ => false
      }
      offer.map(_.message) match {
        case Some(SignalMessage.Offer(sdp)) =>
          sdp should include ("m=video")
          sdp should include ("m=application")   // the input DataChannel
        case other => fail(s"no offer PreviewSignal arrived for ${webRtc.streamId} (saw $other)")
      }
      offer.get.conversationId shouldBe convId

      val stats = webRtc.stats.sync()
      stats.encoder should not be empty
      stats.width should be > 0

      TestStreamBrowserSigil.handleNotice(
        PreviewSignalReply(convId, webRtc.streamId, SignalMessage.Bye), SpecViewer
      ).sync()

      val deadline = System.currentTimeMillis() + 30_000
      while (!webRtc.stopped && System.currentTimeMillis() < deadline) Thread.sleep(100)
      webRtc.stopped shouldBe true
      TestStreamBrowserSigil.previewStreamsFor(convId).map(_.streamId) should not contain webRtc.streamId

      awaitSignal(webRtc.streamId, timeoutMs = 10_000) {
        case SignalMessage.Bye => true
        case _ => false
      } shouldBe defined
    }
  }
}
