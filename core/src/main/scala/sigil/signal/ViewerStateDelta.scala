package sigil.signal

import fabric.rw.*
import sigil.viewer.ViewerStatePayload

/**
 * Server→client [[Notice]] broadcast when a partial update was just
 * applied to a viewer's persisted [[sigil.viewer.ViewerState]] via
 * [[UpdateViewerStateDelta]]. Other sessions for the same viewer
 * apply the same patch via deep-merge of `patch` onto their local
 * state copy.
 *
 * `scope` matches the persisted record's scope. `patch` is the same
 * polytype as the full state — the Dart codegen emits a real typed
 * class, no `Map<String, dynamic>` round-trip on the wire.
 *
 * Sessions that just connected and haven't yet received an initial
 * snapshot from [[ViewerStateSnapshot]] (or
 * [[sigil.Sigil.publishViewerStatesTo]]) can ignore the delta
 * safely — they'll get the merged state when their snapshot arrives.
 */
case class ViewerStateDelta(scope: String,
                            patch: ViewerStatePayload) extends Notice derives RW
