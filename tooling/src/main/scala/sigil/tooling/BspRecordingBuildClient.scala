package sigil.tooling

import ch.epfl.scala.bsp4j.*

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

  override def onBuildShowMessage(params: ShowMessageParams): Unit = ()

  override def onBuildLogMessage(params: LogMessageParams): Unit = {
    logs.offer(params); ()
  }

  override def onBuildTaskStart(params: TaskStartParams): Unit = {
    Option(params.getTaskId).map(_.getId).foreach(id => activeTasks.put(id, params))
  }

  override def onBuildTaskProgress(params: TaskProgressParams): Unit = {
    Option(params.getTaskId).map(_.getId).foreach(id => activeTasks.put(id, params))
  }

  override def onBuildTaskFinish(params: TaskFinishParams): Unit = {
    Option(params.getTaskId).map(_.getId).foreach(id => activeTasks.remove(id))
  }

  override def onBuildPublishDiagnostics(params: PublishDiagnosticsParams): Unit = {
    val uri = params.getTextDocument.getUri
    diagnostics.put(uri, params.getDiagnostics)
  }

  override def onBuildTargetDidChange(params: DidChangeBuildTarget): Unit = ()

  override def onRunPrintStdout(params: PrintParams): Unit = {
    runStdout.offer(params); ()
  }

  override def onRunPrintStderr(params: PrintParams): Unit = {
    runStderr.offer(params); ()
  }
}
