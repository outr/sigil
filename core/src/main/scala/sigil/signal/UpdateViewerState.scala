package sigil.signal

import fabric.rw.*
import sigil.viewer.ViewerStatePayload
import sigil.viewer.ViewerStatePayload.given

/**
 * Client→server [[Notice]]: "persist this UI state for me under this
 * scope." The framework upserts keyed by `(fromViewer, scope)`,
 * bumps the record's `modified` timestamp, and broadcasts a
 * [[ViewerStateSnapshot]] to every live session for `fromViewer` so
 * other tabs / devices converge on the new state without polling.
 */
case class UpdateViewerState(scope: String,
                             payload: ViewerStatePayload) extends Notice derives RW
