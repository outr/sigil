package spec

import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import robobrowser.stream.{StreamConfig, StreamUnavailable, StreamUnavailableException}
import sigil.browser.stream.{PreviewStreamSession, StreamBrowserIdleReaper}
import sigil.conversation.Conversation

import scala.concurrent.duration.*

/**
 * The screencast rung of the preview ladder, driven against a real
 * Chrome. A preview browser launched without a virtual display can never
 * do WebRTC, so `previewStreamFor` must degrade to the CDP screencast on
 * that same browser — and the frames must keep arriving well past
 * Chrome's un-acked-frame ceiling, which only holds if every frame is
 * being acked.
 *
 * Self-skips when Chrome/Chromium isn't installed.
 */
class PreviewStreamFallbackSpec extends AnyWordSpec with Matchers with BeforeAndAfterAll {

  TestStreamBrowserSigil.initFor(getClass.getSimpleName)

  private val chromeAvailable: Boolean =
    List("/usr/bin/google-chrome", "/usr/bin/google-chrome-stable", "/usr/bin/chromium", "/usr/local/bin/google-chrome")
      .exists(p => new java.io.File(p).canExecute)

  private val convId = Conversation.id("preview-fallback")
  private lazy val fixture: PreviewFixtureServer = new PreviewFixtureServer

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    if (chromeAvailable) {
      TestStreamBrowserSigil.usePreviewConfig(TestStreamBrowserSigil.headlessPreviewConfig)
      fixture.start().sync()
    }
  }

  override protected def afterAll(): Unit = {
    if (chromeAvailable) {
      try TestStreamBrowserSigil.disposeStreamBrowserController(convId).sync()
      catch { case _: Throwable => () }
      fixture.stop().sync()
    }
    TestStreamBrowserSigil.shutdown.sync()
    super.afterAll()
  }

  "previewStreamFor on a browser with no virtual display" should {

    "fall back to the CDP screencast and deliver acked frames" in {
      if (!chromeAvailable) cancel("Chrome/Chromium not installed — live browser test")

      val controller = TestStreamBrowserSigil.streamBrowserController(convId).sync()
      controller.run(_.navigate(fixture.url)).sync()

      TestStreamBrowserSigil.previewStreamAvailability(convId).sync() shouldBe
        Some(StreamUnavailable.NoVirtualDisplay)

      val session = TestStreamBrowserSigil.previewStreamFor(convId).sync()
      val screencast = session match {
        case s: PreviewStreamSession.Screencast => s
        case other => fail(s"expected a screencast fallback, got $other")
      }
      TestStreamBrowserSigil.previewStreamsFor(convId).map(_.streamId) should contain(screencast.streamId)

      try {
        // Chrome buffers only a handful of frames before an un-acked
        // screencast stalls; 24 in a row can only arrive if each one was
        // acked as it landed.
        val frames = screencast.frames.take(24).toList.timeout(2.minutes).sync()
        frames should have size 24
        frames.foreach { f =>
          f.dataBase64 should not be empty
          f.metadata.deviceWidth should be > 0.0
        }
        // Frames reach the consumer in the order they were stamped — the
        // CDP dispatcher can deliver two concurrently.
        frames.map(_.sequence).sliding(2).foreach {
          case List(a, b) => b should be > a
          case _ => ()
        }
      } finally
        screencast.stop.sync()
      TestStreamBrowserSigil.previewStreamsFor(convId).map(_.streamId) should not contain screencast.streamId
      // The stream completes rather than parking forever on an empty queue.
      screencast.frames.toList.timeout(30.seconds).sync() shouldBe empty
    }

    "honour a portrait render target so the fallback is a phone capture too" in {
      if (!chromeAvailable) cancel("Chrome/Chromium not installed — live browser test")

      val controller = TestStreamBrowserSigil.streamBrowserController(convId).sync()
      controller.run(_.navigate(fixture.url)).sync()

      val session = TestStreamBrowserSigil.previewStreamFor(
        convId,
        StreamConfig(
          width = Some(390),
          height = Some(844)
        )).sync()
      val screencast = session match {
        case s: PreviewStreamSession.Screencast => s
        case other => fail(s"expected a screencast fallback, got $other")
      }

      try {
        // Device-metrics emulation is what makes this a phone render
        // rather than a cropped desktop one: the page's own media queries
        // resolve against the target
        controller.run(_.eval("return window.innerWidth + 'x' + window.innerHeight"))
          .map(_("result")("value").asString).sync() shouldBe "390x844"
        controller.run(_.eval("return window.mobile"))
          .map(_("result")("value").asBoolean).sync() shouldBe true

        val frames = screencast.frames.take(4).toList.timeout(2.minutes).sync()
        frames should have size 4
        frames.foreach { f =>
          f.metadata.deviceWidth shouldBe 390.0
          f.metadata.deviceHeight shouldBe 844.0
        }

        // Restarting capture behind the same frame stream: the consumer
        // keeps pulling from the stream it already has
        TestStreamBrowserSigil.resizePreview(convId, 1024, 768).sync()
        controller.run(_.eval("return window.innerWidth + 'x' + window.innerHeight"))
          .map(_("result")("value").asString).sync() shouldBe "1024x768"

        val resized = screencast.frames.dropWhile(_.metadata.deviceWidth != 1024.0)
          .take(2).toList.timeout(2.minutes).sync()
        resized should have size 2
        resized.foreach { f =>
          f.metadata.deviceWidth shouldBe 1024.0
          f.metadata.deviceHeight shouldBe 768.0
        }
      } finally {
        screencast.stop.sync()
        controller.run(_.clearViewportOverride()).sync()
      }
    }

    "raise StreamUnavailableException instead of degrading when the fallback is disabled" in {
      if (!chromeAvailable) cancel("Chrome/Chromium not installed — live browser test")

      TestStreamBrowserSigil.setFallbackToScreencast(false)
      try {
        val thrown = intercept[StreamUnavailableException] {
          TestStreamBrowserSigil.previewStreamFor(convId).sync()
        }
        thrown.reason shouldBe StreamUnavailable.NoVirtualDisplay
        TestStreamBrowserSigil.previewStreamsFor(convId) shouldBe empty
      } finally
        TestStreamBrowserSigil.setFallbackToScreencast(true)
    }

    "let the idle reaper dispose a preview browser nobody is watching" in {
      if (!chromeAvailable) cancel("Chrome/Chromium not installed — live browser test")

      val controller = TestStreamBrowserSigil.streamBrowserController(convId).sync()
      controller.sessions shouldBe empty
      controller.isIdle(thresholdMs = 0L) shouldBe true

      StreamBrowserIdleReaper(idleTimeoutMs = 0L).runOnce(TestStreamBrowserSigil).sync()

      controller.isDisposed shouldBe true
      TestStreamBrowserSigil.previewStreamsFor(convId) shouldBe empty
    }
  }
}
