package sigil.signal

import fabric.rw.*

/**
 * Client→server [[Notice]]: "send me my persisted UI state for this
 * scope." Fired by clients on app boot or on entering a new view
 * that has its own state slot.
 *
 * `scope` is an app-defined string. Apps with one global state pass
 * a fixed name (`"my-app"`); apps with multiple slots namespace
 * (`"my-app:project:123"`).
 *
 * The framework's default [[sigil.Sigil.handleNotice]] arm replies
 * with a [[ViewerStateSnapshot]] for `(fromViewer, scope)` —
 * `payload = None` when no row exists yet (fresh viewer / scope).
 */
case class RequestViewerState(scope: String) extends Notice derives RW
