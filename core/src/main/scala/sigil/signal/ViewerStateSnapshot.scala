package sigil.signal

import fabric.rw.*
import sigil.viewer.ViewerStatePayload
import sigil.viewer.ViewerStatePayload.given

/**
 * Server→client [[Notice]] carrying the current persisted UI state
 * for a `(viewer, scope)` pair. Sent in reply to a
 * [[RequestViewerState]], and pushed unsolicited to every live
 * session for the viewer when [[UpdateViewerState]] /
 * [[DeleteViewerState]] mutates the record (so a second tab / device
 * picks up the change live).
 *
 * `payload = None` means "no state persisted for this scope yet" —
 * the client should fall back to defaults.
 *
 * The wire payload is fabric's poly-discriminated JSON of the
 * registered [[ViewerStatePayload]] subtype; clients work in typed
 * land via the Dart codegen's per-subtype classes.
 */
case class ViewerStateSnapshot(scope: String,
                               payload: Option[ViewerStatePayload]) extends Notice derives RW
