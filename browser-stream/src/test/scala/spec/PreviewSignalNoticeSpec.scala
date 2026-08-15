package spec

import fabric.rw.RW
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import robobrowser.stream.SignalMessage
import sigil.browser.stream.{PreviewSignal, PreviewSignalReply}
import sigil.conversation.Conversation
import sigil.participant.{ParticipantId, WorkerParticipantId}
import sigil.signal.Signal

/**
 * The signaling vocabulary, without a browser: both notices must survive
 * the polymorphic `Signal` discriminator for every [[SignalMessage]]
 * shape (an SDP that doesn't round-trip is a preview that never
 * negotiates), stay scoped to their conversation, and a reply naming a
 * dead session must be dropped rather than thrown.
 */
class PreviewSignalNoticeSpec extends AnyWordSpec with Matchers {

  // Phase-1 only — no DB open; this populates RW[Signal] with the
  // module's notices exactly as a real boot would.
  TestStreamBrowserSigil.polymorphicRegistrations.sync()

  private case object SpecViewer extends ParticipantId {
    override val value: String = "spec-viewer"
  }

  private val convId = Conversation.id("preview-signal-notice")
  private val signalRW: RW[Signal] = summon[RW[Signal]]

  private val messages: List[SignalMessage] = List(
    SignalMessage.Offer("v=0\r\no=- 1 2 IN IP4 127.0.0.1\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\n"),
    SignalMessage.Answer("v=0\r\no=- 3 4 IN IP4 127.0.0.1\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\n"),
    SignalMessage.Ice(0, "candidate:1 1 UDP 2130706431 192.168.1.5 54321 typ host"),
    SignalMessage.Error("pipeline error from encoder"),
    SignalMessage.Bye
  )

  "PreviewSignal / PreviewSignalReply" should {

    "round-trip through the polymorphic Signal RW for every SignalMessage case" in {
      messages.foreach { message =>
        val outbound: Signal = PreviewSignal(convId, "stream-1", message)
        signalRW.write(signalRW.read(outbound)) shouldBe outbound

        val inbound: Signal = PreviewSignalReply(convId, "stream-1", message)
        signalRW.write(signalRW.read(inbound)) shouldBe inbound
      }
    }

    "carry a viewer-addressed signal's addressee across the wire" in {
      // A registered ParticipantId subtype — an addressed signal is only
      // as serializable as the id it names.
      val addressee = WorkerParticipantId("preview-owner")
      val addressed: Signal = PreviewSignal(convId, "stream-1", messages.head, Some(addressee))
      signalRW.write(signalRW.read(addressed)) shouldBe addressed
    }

    "be registered in the framework's notice roster" in {
      TestStreamBrowserSigil.noticeSubtypeNames should contain allOf ("PreviewSignal", "PreviewSignalReply")
    }

    "scope delivery to their conversation" in {
      PreviewSignal(convId, "stream-1", SignalMessage.Bye).conversationScope shouldBe Some(convId)
      PreviewSignalReply(convId, "stream-1", SignalMessage.Bye).conversationScope shouldBe Some(convId)
    }
  }

  "a reply naming an unknown stream" should {

    "be dropped rather than raised — through handleNotice" in {
      val reply = PreviewSignalReply(convId, "no-such-stream", SignalMessage.Answer("v=0\r\n"))
      TestStreamBrowserSigil.handleNotice(reply, SpecViewer).sync() shouldBe ()
    }

    "be dropped rather than raised — through routePreviewSignal" in {
      val reply = PreviewSignalReply(convId, "no-such-stream", SignalMessage.Bye)
      TestStreamBrowserSigil.routePreviewSignal(reply).sync() shouldBe ()
    }
  }
}
