package sigil.browser.stream

import lightdb.id.Id
import rapid.{Stream, Task, Unique}
import robobrowser.stream.{SignalMessage, StreamSession, StreamStats}
import sigil.conversation.Conversation

/**
 * A live preview of a conversation's stream browser, in whichever form
 * the host machine can actually deliver.
 *
 * [[PreviewStreamSession.WebRtc]] is the first choice — hardware-encoded
 * H.264 over WebRTC, with input routed back over the session's
 * DataChannel. [[PreviewStreamSession.Screencast]] is the fallback when
 * GStreamer or a virtual display is missing: base64 JPEG frames over
 * CDP, no native dependencies.
 *
 * Both settle on one contract — a `streamId` the signaling vocabulary
 * addresses, and a `stop` that tears the session down. Stopping a
 * session does NOT dispose the browser; the idle reaper
 * ([[StreamBrowserIdleReaper]]) owns that, so a viewer reconnecting
 * moments later resumes against the same page.
 */
sealed trait PreviewStreamSession {
  def conversationId: Id[Conversation]

  /** Session identifier — the address half of [[PreviewSignal]] /
    * [[PreviewSignalReply]]. Unique per session, not per conversation:
    * a conversation may be previewed by several viewers at once. It
    * names a session rather than authorising it — a session started for
    * a viewer answers only to that viewer, whoever holds the id (see
    * [[StreamBrowserSigil.previewStreamFor]]). */
  def streamId: String

  /** Idempotent teardown of this session only. */
  def stop: Task[Unit]
}

object PreviewStreamSession {

  /**
   * WebRTC preview backed by a live [[StreamSession]]. Outbound
   * signaling (offer, ICE, error, bye) is published as
   * [[PreviewSignal]] notices by [[StreamBrowserSigil]]; the viewer's
   * half arrives as [[PreviewSignalReply]] and lands in [[accept]].
   */
  final case class WebRtc(conversationId: Id[Conversation],
                          streamId: String,
                          session: StreamSession) extends PreviewStreamSession {

    /** Feed a viewer-originated signaling message (answer / ICE) into
      * the session. */
    def accept(message: SignalMessage): Task[Unit] = session.fromClient(message)

    /** Encoder, resolution, frame rate, bitrate, RTT and — when the
      * viewer reports it — glass-to-glass latency. */
    def stats: Task[StreamStats] = session.stats

    def stopped: Boolean = session.stopped()

    override def stop: Task[Unit] = session.stop()
  }

  /**
   * CDP-screencast preview. `frames` is a hot stream: frames produced
   * before a consumer starts pulling are buffered up to
   * [[StreamBrowserSigil.previewFrameBuffer]] and then shed oldest-first,
   * so a slow consumer never back-pressures Chrome. The stream completes
   * when [[stop]] runs.
   *
   * No signaling is involved — a consumer forwards these frames over
   * whatever transport it already has.
   */
  final case class Screencast(conversationId: Id[Conversation],
                              streamId: String,
                              frames: Stream[PreviewFrame],
                              stop: Task[Unit]) extends PreviewStreamSession

  private[stream] def newStreamId(): String = Unique()
}
