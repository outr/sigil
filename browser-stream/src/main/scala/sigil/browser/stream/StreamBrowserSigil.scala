package sigil.browser.stream

import fabric.rw.*
import lightdb.id.Id
import rapid.{Stream, Task}
import robobrowser.display.VirtualDisplayConfig
import robobrowser.stream.{SignalMessage, StreamConfig, StreamUnavailable, StreamUnavailableException, Stream as RoboStream}
import robobrowser.{RoboBrowser, RoboBrowserConfig}
import sigil.browser.BrowserSigil
import sigil.conversation.Conversation
import sigil.maintenance.MaintenanceTask
import sigil.participant.ParticipantId
import sigil.signal.Notice

import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong}
import java.util.concurrent.{ConcurrentHashMap, LinkedBlockingQueue}
import scala.jdk.CollectionConverters.*

/**
 * Sigil refinement that adds live preview streaming of a conversation's
 * browser. Apps mix it in on top of [[BrowserSigil]]:
 *
 * {{{
 *   class MyAppSigil extends StreamBrowserSigil { ... }
 * }}}
 *
 * The preview runs on its own per-conversation browser — headful,
 * rendering to a dedicated Xvfb display — so the pooled headless
 * browsers the agent's tools drive are untouched. Resolution comes from
 * [[streamBrowserConfig]]'s `virtualDisplay`; the browser is launched
 * kiosk-fullscreen on it, making viewport pixels display pixels.
 *
 * [[previewStreamFor]] walks a two-rung ladder. When GStreamer and the
 * display are both present it starts a WebRTC session — hardware H.264,
 * single-digit-millisecond latency on a LAN, with viewer input routed
 * back over the session's DataChannel. When they aren't, it logs why and
 * falls back to the CDP screencast on the same browser, which has no
 * native dependencies. Apps that would rather fail than degrade set
 * [[streamFallbackToScreencast]] to `false`.
 *
 * WebRTC signaling rides the notice vocabulary: the framework publishes
 * [[PreviewSignal]] for every offer / ICE candidate / error / bye the
 * session produces, and routes the viewer's [[PreviewSignalReply]] back
 * into it. Screencast previews involve no signaling — a consumer forwards
 * [[PreviewFrame]]s over whatever transport it already has.
 *
 * See `browser-stream/README.md` for the runtime requirements (GStreamer
 * plugin sets, an H.264 encoder, Xvfb, and TURN reality-checks).
 */
trait StreamBrowserSigil extends BrowserSigil {

  /**
   * Config for the per-conversation preview browser. Defaults to
   * [[BrowserSigil.browserConfig]] made headful and bound to a 1080p
   * virtual display — display capture is what makes WebRTC streaming
   * possible, and a browser without one can only ever fall back to the
   * screencast.
   *
   * Apps override to change the resolution, the depth, or the launch
   * flags. Overriding it away from `virtualDisplay = Some(...)` is a
   * deliberate choice for screencast-only hosts.
   */
  def streamBrowserConfig: RoboBrowserConfig = {
    val base = browserConfig
    base.copy(
      browserConfig = base.browserConfig.copy(headless = false),
      virtualDisplay = base.virtualDisplay.orElse(Some(VirtualDisplayConfig()))
    )
  }

  /** How long to keep a preview browser nobody is watching before
    * disposing it. Defaults to the automation browser's timeout; a
    * headful Chrome plus an Xvfb display is heavier still, so apps
    * serving many short previews may want it shorter. */
  def streamBrowserIdleTimeoutMs: Long = browserIdleTimeoutMs

  /** Whether an unavailable WebRTC stack degrades to the CDP screencast
    * (default) or raises [[StreamUnavailableException]]. */
  def streamFallbackToScreencast: Boolean = true

  /** Frames buffered for a screencast preview whose consumer hasn't
    * caught up. On overflow the oldest frame is shed — a preview is
    * worth less the staler it is, and Chrome must never be
    * back-pressured (it stalls the screencast rather than queueing). */
  def previewFrameBuffer: Int = 120

  /** Screencast image encoding — `"jpeg"` (default) or `"png"`. */
  def previewFrameFormat: String = "jpeg"

  /** Screencast JPEG quality, 0-100. */
  def previewFrameQuality: Int = 70

  /**
   * Resolve the per-conversation [[StreamBrowserController]], launching
   * the preview browser on first call. Concurrent callers see the same
   * controller — the loser of the race disposes its own browser.
   */
  final def streamBrowserController(convId: Id[Conversation]): Task[StreamBrowserController] = Task.defer {
    Option(StreamBrowserSigil.controllers.get(convId.value)) match {
      case Some(existing) if !existing.isDisposed => Task.pure(existing)
      case _ =>
        RoboBrowser(streamBrowserConfig).flatMap { browser =>
          val fresh = new StreamBrowserController(convId, browser)
          val winner = StreamBrowserSigil.controllers.compute(convId.value, (_, prior) =>
            if (prior != null && !prior.isDisposed) prior else fresh
          )
          if (winner eq fresh) Task.pure(fresh) else fresh.dispose.map(_ => winner)
        }
    }
  }

  /** Stop every live preview and dispose this conversation's stream
    * browser (and its Xvfb display). Idempotent. */
  final def disposeStreamBrowserController(convId: Id[Conversation]): Task[Unit] = {
    val removed = StreamBrowserSigil.controllers.remove(convId.value)
    if (removed == null) Task.unit else removed.dispose
  }

  /** Why WebRTC streaming can't run for this conversation, or `None`
    * when it can. Launches the preview browser if it isn't running —
    * the answer depends on the browser having a virtual display, not
    * just on the host's GStreamer install. Consumers render the reason
    * ("preview degraded because ...") rather than guessing. */
  final def previewStreamAvailability(convId: Id[Conversation]): Task[Option[StreamUnavailable]] =
    streamBrowserController(convId).map(c => RoboStream(c.browser).availability)

  /** Live preview sessions for a conversation — one per viewer. */
  final def previewStreamsFor(convId: Id[Conversation]): List[PreviewStreamSession] =
    Option(StreamBrowserSigil.controllers.get(convId.value)).map(_.sessions).getOrElse(Nil)

  /**
   * Start a preview of this conversation's browser, launching it if
   * needed. Returns a [[PreviewStreamSession.WebRtc]] when the WebRTC
   * stack is available and a [[PreviewStreamSession.Screencast]] on the
   * same browser when it isn't (unless
   * [[streamFallbackToScreencast]] is `false`, in which case the
   * unavailability reason is raised as [[StreamUnavailableException]]).
   *
   * Each call yields an independent session, so several viewers can
   * watch one conversation.
   */
  def previewStreamFor(convId: Id[Conversation],
                       config: StreamConfig = StreamConfig()): Task[PreviewStreamSession] =
    streamBrowserController(convId).flatMap { controller =>
      Task(RoboStream(controller.browser).availability).flatMap {
        case None => startWebRtcPreview(controller, config)
        case Some(reason) if streamFallbackToScreencast =>
          Task {
            scribe.info(s"WebRTC preview unavailable for ${convId.value} (${reason.message}) — " +
              "falling back to the CDP screencast")
          }.flatMap(_ => startScreencastPreview(controller, config))
        case Some(reason) => Task.error(StreamUnavailableException(reason))
      }
    }

  private def startWebRtcPreview(controller: StreamBrowserController,
                                 config: StreamConfig): Task[PreviewStreamSession] = {
    val convId = controller.conversationId
    RoboStream(controller.browser).start(config).flatMap { session =>
      val preview = PreviewStreamSession.WebRtc(convId, PreviewStreamSession.newStreamId(), session)
      controller.register(preview)
      // `connect` flushes anything the session emitted while it was
      // starting — the offer fires from webrtcbin moments after PLAYING,
      // typically before this call lands.
      session.connect { message =>
        if (message == SignalMessage.Bye) controller.deregister(preview.streamId)
        publish(PreviewSignal(convId, preview.streamId, message)).startUnit()
      }.map(_ => preview)
    }
  }

  private def startScreencastPreview(controller: StreamBrowserController,
                                     config: StreamConfig): Task[PreviewStreamSession] = {
    val convId = controller.conversationId
    val streamId = PreviewStreamSession.newStreamId()
    val queue = new LinkedBlockingQueue[Option[PreviewFrame]](math.max(1, previewFrameBuffer))
    val sequence = new AtomicLong(0L)
    val stopped = new AtomicBoolean(false)

    val frames: Stream[PreviewFrame] = Stream.unfoldStreamEval(queue) { q =>
      Task(q.take()).map {
        case Some(frame) => Some((Stream.emit(frame), q))
        case None => None
      }
    }

    val stop: Task[Unit] = Task.defer {
      if (stopped.compareAndSet(false, true)) {
        controller.browser.screencast.stop()
          .handleError(t => Task(scribe.warn(s"Stopping screencast preview $streamId failed: ${t.getMessage}")))
          .map { _ =>
            controller.deregister(streamId)
            queue.clear()
            queue.offer(None)
            ()
          }
      } else Task.unit
    }

    val preview = PreviewStreamSession.Screencast(convId, streamId, frames, stop)
    controller.browser.screencast.start(
      onFrame = frame => {
        val next = Some(PreviewFrame(frame.data, frame.metadata, sequence.incrementAndGet()))
        if (!queue.offer(next)) {
          queue.poll()
          queue.offer(next)
          ()
        }
      },
      format = previewFrameFormat,
      quality = previewFrameQuality,
      maxWidth = config.maxWidth,
      maxHeight = config.maxHeight
    ).map { _ =>
      controller.register(preview)
      preview
    }
  }

  /**
   * Route a viewer's signaling message into the session it addresses.
   * `bye` stops any session kind; answers and ICE candidates only mean
   * something to a WebRTC session. An unknown `streamId` is warned about
   * and dropped — the viewer's session was reaped, and it should ask for
   * a fresh stream.
   */
  final def routePreviewSignal(reply: PreviewSignalReply): Task[Unit] = Task.defer {
    val controller = Option(StreamBrowserSigil.controllers.get(reply.conversationId.value))
    controller.flatMap(_.session(reply.streamId)) match {
      case None => Task {
        scribe.warn(s"PreviewSignalReply for unknown stream ${reply.streamId} " +
          s"in conversation ${reply.conversationId.value} — ignored")
        ()
      }
      case Some(session) => reply.message match {
        case SignalMessage.Bye => session.stop
        case other => session match {
          case webRtc: PreviewStreamSession.WebRtc => webRtc.accept(other)
          case _ => Task {
            scribe.warn(s"Ignoring $other for screencast preview ${reply.streamId}: " +
              "the screencast fallback does not use WebRTC signaling")
            ()
          }
        }
      }
    }
  }

  override def handleNotice(notice: Notice, fromViewer: ParticipantId): Task[Unit] = notice match {
    case reply: PreviewSignalReply => routePreviewSignal(reply)
    case other => super.handleNotice(other, fromViewer)
  }

  /** Auto-register the module's signaling notices so they round-trip
    * through fabric's polymorphic discriminator. */
  override protected def noticeRegistrations: List[RW[? <: Notice]] =
    super.noticeRegistrations ++ List(summon[RW[PreviewSignal]], summon[RW[PreviewSignalReply]])

  override def maintenanceTasks: List[MaintenanceTask] =
    super.maintenanceTasks :+ StreamBrowserIdleReaper(streamBrowserIdleTimeoutMs)

  /** Dispose this conversation's preview browser before its records are
    * purged, then delegate to the standard cascade. */
  override def deleteConversation(conversationId: Id[Conversation]): Task[Unit] =
    disposeStreamBrowserController(conversationId)
      .handleError(_ => Task.unit)
      .flatMap(_ => super.deleteConversation(conversationId))

  /** Tear down every live preview browser on `Sigil.shutdown`, then
    * chain the rest of the module stack. */
  override protected def onShutdown: Task[Unit] =
    StreamBrowserSigil.disposeAll.flatMap(_ => super.onShutdown)
}

object StreamBrowserSigil {

  /** Per-(JVM, conversation) registry of live preview browsers. Keyed by
    * conversation-id string, mirroring `BrowserSigil.controllers`, so the
    * map survives a `Sigil` restart inside one JVM. */
  private[stream] val controllers: ConcurrentHashMap[String, StreamBrowserController] =
    new ConcurrentHashMap[String, StreamBrowserController]()

  /** Dispose every live preview browser. Idempotent. */
  def disposeAll: Task[Unit] = Task.defer {
    val all = controllers.values().asScala.toList
    controllers.clear()
    Task.sequence(all.map(_.dispose.handleError(_ => Task.unit))).unit
  }
}
