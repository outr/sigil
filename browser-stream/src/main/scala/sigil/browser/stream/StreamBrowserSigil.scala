package sigil.browser.stream

import fabric.rw.*
import lightdb.id.Id
import rapid.{Stream, Task}
import robobrowser.display.VirtualDisplayConfig
import robobrowser.event.ScreencastFrameEvent
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
      browserConfig = base.browserConfig.copy(
        headless = false,
        // Clean-capture profile: display capture records everything
        // Chrome draws, so suppress Chrome's own in-content UI. The
        // save-password bubble is killed at the profile level
        // (passwordManager = false — no CLI switch does this without
        // dragging in the automation infobar; --incognito does NOT
        // suppress it), --test-type hides the infobar and first-run
        // prompts, and the remaining bubbles (translate offers,
        // breach-check dialogs, session-restore) go via features and
        // switches. Overridable like everything else here.
        passwordManager = false,
        testType = true,
        disableFeatures = (base.browserConfig.disableFeatures ++
          List("Translate", "PasswordLeakDetection")).distinct,
        extraArgs = (base.browserConfig.extraArgs ++
          List("--hide-crash-restore-bubble", "--no-first-run", "--no-default-browser-check")).distinct
      ),
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
   * The launch config for a preview browser that will serve `config`.
   *
   * A stream renders and captures a rectangle *within* its virtual
   * display, and Xvfb refuses to resize a running display, so the display
   * is sized once at launch to the larger of [[streamBrowserConfig]]'s
   * size and the request, per dimension. The requested target then fits
   * by construction, and so does any later
   * [[resizePreview]] back up to the configured size — which is what
   * makes a portrait preview able to become a landscape one without
   * relaunching the browser.
   */
  def streamBrowserConfigFor(config: StreamConfig): RoboBrowserConfig = {
    val base = streamBrowserConfig
    base.virtualDisplay match {
      case Some(display) =>
        // The framebuffer envelope honours maxWidth/maxHeight when the
        // request declares them: "render at width x height now, but the
        // display must accommodate growth up to the max" — a preview
        // that starts in a small pane and later goes fullscreen never
        // needs a browser relaunch. Fallback order per dimension:
        // configured display, declared max, requested render target.
        base.copy(virtualDisplay = Some(display.copy(
          width = List(display.width, config.maxWidth.getOrElse(0), config.width.getOrElse(0)).max,
          height = List(display.height, config.maxHeight.getOrElse(0), config.height.getOrElse(0)).max
        )))
      case None => base
    }
  }

  /** Clamp a requested render target to the live display's framebuffer
    * envelope. RandR cannot grow a running Xvfb framebuffer, so a
    * target beyond it (a 4K fullscreen pane against a smaller
    * envelope) is served at the envelope size — the client's video
    * element scales it — rather than aborting the stream. */
  private def clampToEnvelope(controller: StreamBrowserController,
                              width: Int,
                              height: Int): (Int, Int) = {
    val (envW, envH) = controller.displaySize
    val (w, h) = (math.min(width, envW), math.min(height, envH))
    if (w != width || h != height) {
      scribe.warn(
        s"resizePreview target ${width}x$height exceeds the display envelope ${envW}x$envH; " +
          s"serving ${w}x$h — allocate a larger envelope via StreamConfig.maxWidth/maxHeight at stream start.")
    }
    (w, h)
  }

  /**
   * Resolve the per-conversation [[StreamBrowserController]], launching
   * the preview browser on first call. Concurrent callers see the same
   * controller — the loser of the race disposes its own browser.
   */
  final def streamBrowserController(convId: Id[Conversation]): Task[StreamBrowserController] =
    streamBrowserController(convId, StreamConfig())

  /** As [[streamBrowserController]], sizing a *freshly launched* browser's
    * virtual display to `config`'s render target. An already-running
    * browser keeps the display it has; a target within it is served as a
    * capture region, and a larger one fails loudly rather than silently
    * cropping. */
  final def streamBrowserController(convId: Id[Conversation],
                                    config: StreamConfig): Task[StreamBrowserController] = Task.defer {
    Option(StreamBrowserSigil.controllers.get(convId.value)) match {
      case Some(existing) if !existing.isDisposed => Task.pure(existing)
      case _ =>
        RoboBrowser(streamBrowserConfigFor(config)).flatMap { browser =>
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
   *
   * `config.width`/`height` request a render size: the page lays out at
   * exactly that size and exactly that rectangle is captured, so a
   * `390x844` request previews a portrait, mobile-layout page on either
   * rung. A browser launched by this call gets a virtual display sized to
   * the request; an existing one serves any target that fits inside the
   * display it already has.
   */
  def previewStreamFor(convId: Id[Conversation],
                       config: StreamConfig = StreamConfig()): Task[PreviewStreamSession] =
    streamBrowserController(convId, config).flatMap { controller =>
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
      // A resize reconfigures the live pipeline, so this listener carries
      // exactly one offer for the session's whole lifetime.
      controller.register(preview, (width, height) => session.resize(width, height))
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
            queue.synchronized {
              queue.clear()
              queue.offer(None)
            }
            ()
          }
      } else Task.unit
    }

    val preview = PreviewStreamSession.Screencast(convId, streamId, frames, stop)

    // Chrome's frames arrive on the CDP dispatcher, which is free to
    // deliver two concurrently. Stamping and enqueueing under one lock
    // is what makes `sequence` match the order a consumer pulls in;
    // doing them separately lets a later frame overtake an earlier one.
    val enqueue: ScreencastFrameEvent => Unit = frame => queue.synchronized {
      val next = Some(PreviewFrame(frame.data, frame.metadata, sequence.incrementAndGet()))
      if (!queue.offer(next)) {
        queue.poll()
        queue.offer(next)
        ()
      }
    }

    // The screencast rung has no display capture to crop, so the render
    // target is applied where the page is laid out: device-metrics
    // emulation makes Chrome report the target as the frames' device size
    // and resolve responsive CSS against it, which is what makes a
    // portrait target a portrait mobile capture here too. Restarting the
    // screencast re-issues `Page.startScreencast` with the new bounds and
    // keeps feeding the same queue, so `frames` survives a resize.
    def capture(width: Option[Int], height: Option[Int]): Task[Unit] = {
      val layout = (width, height) match {
        case (Some(w), Some(h)) => controller.browser.setViewportSize(w, h)
        case _ => Task.unit
      }
      layout.flatMap(_ => controller.browser.screencast.start(
        onFrame = enqueue,
        format = previewFrameFormat,
        quality = previewFrameQuality,
        maxWidth = config.maxWidth,
        maxHeight = config.maxHeight
      ))
    }

    capture(config.width, config.height).map { _ =>
      controller.register(preview, (width, height) => capture(Some(width), Some(height)))
      preview
    }
  }

  /**
   * Resize a conversation's live preview(s) to `width` x `height`.
   *
   * A WebRTC session reconfigures its live pipeline against the new size:
   * no renegotiation, no second offer, no [[PreviewSignal]] at all. The
   * peer connection the viewer already has stays up and its `<video>`
   * simply starts rendering the same track at the new resolution, which
   * H.264 carries in-band. A screencast session re-lays the page out and
   * restarts capture behind the same
   * [[PreviewStreamSession.Screencast.frames]] stream, so its consumer
   * keeps pulling from the stream it has.
   *
   * A conversation with no live preview is a warn and a no-op: there is
   * nothing to resize, and the next [[previewStreamFor]] carries the size
   * the caller wants anyway.
   */
  final def resizePreview(convId: Id[Conversation], width: Int, height: Int): Task[Unit] = Task.defer {
    val controller = Option(StreamBrowserSigil.controllers.get(convId.value))
    val (w, h) = controller.map(clampToEnvelope(_, width, height)).getOrElse((width, height))
    controller.toList.flatMap(c => c.sessions.flatMap(s => c.resizer(s.streamId))) match {
      case Nil => Task {
        scribe.warn(s"resizePreview(${width}x$height) for ${convId.value}: no live preview session — ignored")
        ()
      }
      case resizers => Task.sequence(resizers.map(_.apply(w, h))).unit
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
