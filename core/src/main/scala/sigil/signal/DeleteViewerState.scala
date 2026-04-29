package sigil.signal

import fabric.rw.*

/**
 * Client→server [[Notice]]: "drop my persisted UI state for this
 * scope." The framework deletes the row keyed by `(fromViewer,
 * scope)` and broadcasts a [[ViewerStateSnapshot]] with
 * `payload = None` to every live session for `fromViewer` so
 * other tabs / devices fall back to defaults in lockstep.
 *
 * Deleting a non-existent scope is a no-op (still broadcasts the
 * `None` snapshot — clients use that to reset their local state).
 */
case class DeleteViewerState(scope: String) extends Notice derives RW
