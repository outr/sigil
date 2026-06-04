package sigil.tooling

import rapid.Task
import sigil.Sigil
import sigil.pipeline.SettledEffect
import sigil.signal.{EventState, Signal, ToolDelta}
import sigil.tool.model.{DeleteFileInput, EditAtRangeInput, EditFileInput, WriteFileInput}

/**
 * Post-publish hook that keeps active LSP sessions in sync with
 * filesystem mutations the agent makes through core's edit /
 * write / delete tools. Listens for the settling `ToolDelta` of
 * any tool whose typed input identifies a single file path, then
 * fires `textDocument/didChange` plus `workspace/didChangeWatchedFiles`
 * on every active session whose project root contains the path.
 *
 * Apps wire by composing into `settledEffects`:
 *
 * {{{
 *   override def settledEffects: List[SettledEffect] =
 *     super.settledEffects :+ LspAutoSyncEffect(lspManager)
 * }}}
 *
 * Zero cost for apps that don't wire it — the effect simply isn't
 * in the list. Apps that wire it but have no active LSP sessions
 * pay only the pattern-match overhead per signal (which is cheap
 * — the match fails on the first guard).
 */
final case class LspAutoSyncEffect(manager: LspManager) extends SettledEffect {

  override def apply(signal: Signal, self: Sigil): Task[Unit] =
    extractPath(signal) match {
      case Some(path) =>
        // Fire both updates in parallel — didChange refreshes the
        // server's in-memory model, didChangeWatchedFiles signals the
        // workspace-level event. Fire-and-forget so the publish
        // pipeline isn't blocked on the LSP roundtrip.
        Task {
          manager.notifyDocumentChanged(path).handleError(_ => Task.unit).startUnit()
          manager.notifyFileChanged(path).handleError(_ => Task.unit).startUnit()
          ()
        }
      case None => Task.unit
    }

  private def extractPath(signal: Signal): Option[String] = signal match {
    case td: ToolDelta if td.state.contains(EventState.Complete) =>
      td.input match {
        case Some(e: EditFileInput) => Some(e.filePath)
        case Some(e: EditAtRangeInput) => Some(e.filePath)
        case Some(w: WriteFileInput) => Some(w.filePath)
        case Some(d: DeleteFileInput) => Some(d.filePath)
        case _ => None
      }
    case _ => None
  }
}
