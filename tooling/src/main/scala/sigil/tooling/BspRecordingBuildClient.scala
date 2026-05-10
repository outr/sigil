package sigil.tooling

import ch.epfl.scala.bsp4j.*

import java.util.concurrent.atomic.{AtomicLong, AtomicReference}
import java.util.concurrent.{ConcurrentHashMap, ConcurrentLinkedQueue}
import scala.jdk.CollectionConverters.*

/**
 * Build client that captures the things agents care about:
 *
 *   - per-target diagnostics (compile errors / warnings)
 *   - log messages (build-server stderr / stdout)
 *   - run-target stdout / stderr lines
 *   - active task progress (compile started / finished / progress %)
 *
 * Tools snapshot these between calls. Apps that want a stricter
 * silent client (drop everything) replace at [[BspSession.spawn]];
 * apps that want richer routing (push to a Stream, log to scribe)
 * subclass.
 *
 * Thread safety: all collections are concurrent. Log/print queues
 * are bounded only by memory — apps that worry about runaway noise
 * subclass and trim.
 */
class BspRecordingBuildClient extends BuildClient {

  /** Per-build-target diagnostics — keyed by document URI (BSP
    * `PublishDiagnosticsParams.textDocument.uri`), each entry is the
    * latest set the server published for that file. */
  val diagnostics: ConcurrentHashMap[String, java.util.List[Diagnostic]] = new ConcurrentHashMap()

  /** All log messages received since the last clear. Apps that want
    * to render compile output streams subscribe by polling between
    * calls. */
  val logs: ConcurrentLinkedQueue[LogMessageParams] = new ConcurrentLinkedQueue()

  /** Run-target stdout. Captured separately from logs so test/run
    * tools can extract just the program output. */
  val runStdout: ConcurrentLinkedQueue[PrintParams] = new ConcurrentLinkedQueue()

  /** Run-target stderr. */
  val runStderr: ConcurrentLinkedQueue[PrintParams] = new ConcurrentLinkedQueue()

  /** Active task ids → most recent progress / start params. Cleared
    * when `onBuildTaskFinish` arrives for the matching id. */
  val activeTasks: ConcurrentHashMap[String, java.lang.Object] = new ConcurrentHashMap()

  /** Per-call status callback. The active BSP tool (via
    * [[BspToolSupport.withSession]] / `withSessionTyped`) installs a
    * `Some(handler)` that publishes [[sigil.event.ToolProgress]] for
    * the chip; cleared back to `None` on exit. Routes BSP-server
    * progress (log messages, task start/progress/finish) so long
    * builds surface live status instead of looking frozen.
    *
    * Thread-safe; the callback is invoked synchronously from the
    * BSP4J notification thread, so handlers should be cheap and
    * return quickly (typically just a `Task.startUnit` of a
    * `ctx.reportProgress` call). */
  private val statusCallback: AtomicReference[Option[String => Unit]] =
    new AtomicReference(None)

  /** Install (or clear with `None`) the status-update callback for
    * the currently-running tool. Thread-safe; replacement is
    * atomic. */
  def setStatusCallback(cb: Option[String => Unit]): Unit =
    statusCallback.set(cb)

  private def emitStatus(message: String): Unit = {
    val trimmed = Option(message).getOrElse("").trim
    if (trimmed.nonEmpty) statusCallback.get().foreach(_.apply(trimmed))
  }

  /** Wall-clock millis of the most recent incoming server activity
    * (any notification or diagnostic). Read by
    * [[DurableJsonRpc.issueDurable]] to reset the silence window —
    * a long operation that's actively reporting progress isn't
    * stuck. Updated by every notification handler. */
  private val lastActivityAt: AtomicLong = new AtomicLong(System.currentTimeMillis())

  /** Accessor for [[DurableJsonRpc.issueDurable]]. Returns the wall-
    * clock millis of the most recent server-side activity. */
  def lastActivityAtMillis: Long = lastActivityAt.get()

  private def markActivity(): Unit = lastActivityAt.set(System.currentTimeMillis())

  /** Snapshot the current diagnostics map. Useful for tools that
    * want to show what the server has flagged after a compile. */
  def diagnosticsSnapshot: Map[String, List[Diagnostic]] =
    diagnostics.asScala.view.mapValues(_.asScala.toList).toMap

  /** Snapshot and CLEAR the log queue — typical pattern for tools
    * that want "what new logs arrived during this call". */
  def drainLogs(): List[LogMessageParams] = {
    val out = scala.collection.mutable.ListBuffer.empty[LogMessageParams]
    var next = logs.poll()
    while (next != null) {
      out += next
      next = logs.poll()
    }
    out.toList
  }

  def drainRunOutput(): (List[String], List[String]) = {
    def drain(q: ConcurrentLinkedQueue[PrintParams]): List[String] = {
      val out = scala.collection.mutable.ListBuffer.empty[String]
      var next = q.poll()
      while (next != null) {
        Option(next.getMessage).foreach(out += _)
        next = q.poll()
      }
      out.toList
    }
    (drain(runStdout), drain(runStderr))
  }

  // ---- BuildClient overrides ----

  override def onBuildShowMessage(params: ShowMessageParams): Unit = {
    markActivity()
    emitStatus(Option(params.getMessage).getOrElse(""))
  }

  override def onBuildLogMessage(params: LogMessageParams): Unit = {
    markActivity()
    logs.offer(params)
    emitStatus(Option(params.getMessage).getOrElse(""))
  }

  override def onBuildTaskStart(params: TaskStartParams): Unit = {
    markActivity()
    Option(params.getTaskId).map(_.getId).foreach(id => activeTasks.put(id, params))
    emitStatus(Option(params.getMessage).getOrElse(""))
  }

  override def onBuildTaskProgress(params: TaskProgressParams): Unit = {
    markActivity()
    Option(params.getTaskId).map(_.getId).foreach(id => activeTasks.put(id, params))
    val msg = Option(params.getMessage).getOrElse("")
    val total = params.getTotal
    val progress = params.getProgress
    val withPct =
      if (total != null && total > 0L && progress != null) {
        val pct = math.round(progress.toDouble / total.toDouble * 100).toInt
        if (msg.isEmpty) s"$pct%" else s"$msg ($pct%)"
      } else msg
    emitStatus(withPct)
  }

  override def onBuildTaskFinish(params: TaskFinishParams): Unit = {
    markActivity()
    Option(params.getTaskId).map(_.getId).foreach(id => activeTasks.remove(id))
    val msg = Option(params.getMessage).getOrElse("")
    val statusName = Option(params.getStatus).map(_.toString).getOrElse("")
    val combined = (msg, statusName) match {
      case ("", "")   => ""
      case ("", s)    => s
      case (m, "")    => m
      case (m, s)     => s"$m ($s)"
    }
    emitStatus(combined)
  }

  override def onBuildPublishDiagnostics(params: PublishDiagnosticsParams): Unit = {
    markActivity()
    val uri = params.getTextDocument.getUri
    diagnostics.put(uri, params.getDiagnostics)
  }

  override def onBuildTargetDidChange(params: DidChangeBuildTarget): Unit = markActivity()

  override def onRunPrintStdout(params: PrintParams): Unit = {
    markActivity()
    runStdout.offer(params); ()
  }

  override def onRunPrintStderr(params: PrintParams): Unit = {
    markActivity()
    runStderr.offer(params); ()
  }
}
