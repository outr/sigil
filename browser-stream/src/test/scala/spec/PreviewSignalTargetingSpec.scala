package spec

import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import robobrowser.stream.{SignalMessage, StreamConfig}
import sigil.browser.stream.{PreviewSignal, PreviewSignalReply, PreviewStreamSession}
import sigil.conversation.Conversation
import sigil.participant.ParticipantId

import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters.*

/**
 * Two people watching one conversation each get their own preview.
 *
 * The signaling half needs no browser: a session addressed to a viewer
 * must reach that viewer's `signalsFor` stream and no other, while an
 * unaddressed one keeps its conversation-wide fan-out. The ownership
 * half — that only the owner's reply is applied — runs against a real
 * session on the screencast rung, which needs Chrome but no GStreamer,
 * and self-skips without it.
 */
class PreviewSignalTargetingSpec extends AnyWordSpec with Matchers with BeforeAndAfterAll {

  TestStreamBrowserSigil.initFor(getClass.getSimpleName)

  private case object ViewerA extends ParticipantId {
    override val value: String = "viewer-a"
  }

  private case object ViewerB extends ParticipantId {
    override val value: String = "viewer-b"
  }

  private val chromeAvailable: Boolean =
    List("/usr/bin/google-chrome", "/usr/bin/google-chrome-stable", "/usr/bin/chromium", "/usr/local/bin/google-chrome")
      .exists(p => new java.io.File(p).canExecute)

  private val convId = Conversation.id("preview-signal-targeting")
  private val toA = new ConcurrentLinkedQueue[PreviewSignal]()
  private val toB = new ConcurrentLinkedQueue[PreviewSignal]()
  private val broadcast = new ConcurrentLinkedQueue[PreviewSignal]()

  private val offer = SignalMessage.Offer("v=0\r\no=- 1 2 IN IP4 127.0.0.1\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\n")

  private def collect(viewer: ParticipantId, into: ConcurrentLinkedQueue[PreviewSignal]): Unit =
    TestStreamBrowserSigil.signalsFor(viewer).foreach {
      case signal: PreviewSignal => into.add(signal); ()
      case _ => ()
    }.drain.startUnit()

  private def awaitSignal(queue: ConcurrentLinkedQueue[PreviewSignal],
                          streamId: String,
                          timeoutMs: Long = 5_000): Option[PreviewSignal] = {
    val deadline = System.currentTimeMillis() + timeoutMs
    var found: Option[PreviewSignal] = None
    while (found.isEmpty && System.currentTimeMillis() < deadline) {
      found = queue.iterator().asScala.find(_.streamId == streamId)
      if (found.isEmpty) Thread.sleep(50)
    }
    found
  }

  private def signalsNamed(queue: ConcurrentLinkedQueue[PreviewSignal], streamId: String): List[PreviewSignal] =
    queue.iterator().asScala.filter(_.streamId == streamId).toList

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    collect(ViewerA, toA)
    collect(ViewerB, toB)
    TestStreamBrowserSigil.signals.foreach {
      case signal: PreviewSignal => broadcast.add(signal); ()
      case _ => ()
    }.drain.startUnit()
    Thread.sleep(250)
  }

  override protected def afterAll(): Unit = {
    if (chromeAvailable) {
      try TestStreamBrowserSigil.disposeStreamBrowserController(convId).sync()
      catch { case _: Throwable => () }
    }
    TestStreamBrowserSigil.shutdown.sync()
    super.afterAll()
  }

  "a session addressed to a viewer" should {

    "deliver its signaling to that viewer and to nobody else" in {
      TestStreamBrowserSigil.publishPreviewSignal(
        PreviewSignal(convId, "stream-owned-by-a", offer, Some(ViewerA))
      ).sync()

      awaitSignal(toA, "stream-owned-by-a").map(_.forViewer) shouldBe Some(Some(ViewerA))
      Thread.sleep(500)
      signalsNamed(toB, "stream-owned-by-a") shouldBe empty
    }

    "stay off the unfiltered firehose, like every other targeted notice" in {
      signalsNamed(broadcast, "stream-owned-by-a") shouldBe empty
    }

    "be withheld from a non-addressee even if it reaches their stream by another path" in {
      val addressed = PreviewSignal(convId, "stream-canSee", offer, Some(ViewerA))
      TestStreamBrowserSigil.canSee(addressed, ViewerA) shouldBe true
      TestStreamBrowserSigil.canSee(addressed, ViewerB) shouldBe false
    }

    "let each of two viewers hold an independently addressed session" in {
      TestStreamBrowserSigil.publishPreviewSignal(PreviewSignal(convId, "stream-a", offer, Some(ViewerA))).sync()
      TestStreamBrowserSigil.publishPreviewSignal(PreviewSignal(convId, "stream-b", offer, Some(ViewerB))).sync()

      awaitSignal(toA, "stream-a") shouldBe defined
      awaitSignal(toB, "stream-b") shouldBe defined
      Thread.sleep(500)
      signalsNamed(toA, "stream-b") shouldBe empty
      signalsNamed(toB, "stream-a") shouldBe empty
    }
  }

  "an unaddressed session" should {

    "keep its conversation-wide fan-out" in {
      TestStreamBrowserSigil.publishPreviewSignal(PreviewSignal(convId, "stream-broadcast", offer)).sync()

      awaitSignal(toA, "stream-broadcast").map(_.forViewer) shouldBe Some(None)
      awaitSignal(toB, "stream-broadcast").map(_.forViewer) shouldBe Some(None)
      awaitSignal(broadcast, "stream-broadcast") shouldBe defined
    }

    "pass canSee for every viewer" in {
      val unaddressed = PreviewSignal(convId, "stream-broadcast", offer)
      TestStreamBrowserSigil.canSee(unaddressed, ViewerA) shouldBe true
      TestStreamBrowserSigil.canSee(unaddressed, ViewerB) shouldBe true
    }
  }

  "a reply to a session owned by another viewer" should {

    "be dropped, leaving the session live for its owner" in {
      if (!chromeAvailable) cancel("Chrome/Chromium not installed — live browser test")
      TestStreamBrowserSigil.usePreviewConfig(TestStreamBrowserSigil.headlessPreviewConfig)

      val session = TestStreamBrowserSigil.previewStreamFor(convId, ViewerA, StreamConfig()).sync()
      try {
        TestStreamBrowserSigil.previewStreamOwner(convId, session.streamId) shouldBe Some(ViewerA)

        TestStreamBrowserSigil.handleNotice(
          PreviewSignalReply(convId, session.streamId, SignalMessage.Bye),
          ViewerB
        ).sync()
        TestStreamBrowserSigil.previewStreamsFor(convId).map(_.streamId) should contain(session.streamId)

        // Nor can an ingress that cannot name the replier spend the id.
        TestStreamBrowserSigil.routePreviewSignal(
          PreviewSignalReply(convId, session.streamId, SignalMessage.Bye)
        ).sync()
        TestStreamBrowserSigil.previewStreamsFor(convId).map(_.streamId) should contain(session.streamId)

        TestStreamBrowserSigil.handleNotice(
          PreviewSignalReply(convId, session.streamId, SignalMessage.Bye),
          ViewerA
        ).sync()
        TestStreamBrowserSigil.previewStreamsFor(convId).map(_.streamId) should not contain session.streamId
      } finally
        session.stop.sync()
    }

    "not apply to an unowned session, which any viewer may still answer" in {
      if (!chromeAvailable) cancel("Chrome/Chromium not installed — live browser test")
      TestStreamBrowserSigil.usePreviewConfig(TestStreamBrowserSigil.headlessPreviewConfig)

      val session = TestStreamBrowserSigil.previewStreamFor(convId).sync()
      session shouldBe a[PreviewStreamSession.Screencast]
      TestStreamBrowserSigil.previewStreamOwner(convId, session.streamId) shouldBe None

      TestStreamBrowserSigil.handleNotice(
        PreviewSignalReply(convId, session.streamId, SignalMessage.Bye),
        ViewerB
      ).sync()
      TestStreamBrowserSigil.previewStreamsFor(convId).map(_.streamId) should not contain session.streamId
    }
  }
}
