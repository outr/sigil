package sigil.signal

import fabric.rw.*
import sigil.viewer.ViewerStatePayload

/**
 * Client→server [[Notice]] requesting a partial update to the
 * persisted [[sigil.viewer.ViewerState]] for the calling viewer +
 * `scope`. Pairs with the existing full-replace [[UpdateViewerState]];
 * use this when the consumer's state subtype is large enough that
 * sending the whole payload per UI tweak (toggle a panel, change
 * theme) is wasteful.
 *
 * The framework deep-merges `patch.json` onto the persisted
 * payload's JSON via fabric's object merge (non-null fields in
 * `patch` overlay; nested objects merge recursively), then decodes
 * the result back through the [[ViewerStatePayload]] polytype RW
 * and persists. Apps make their state subtypes' fields `Option[T]`
 * (or list-typed where append/replace is meaningful) so partial
 * updates are expressible at the type level.
 *
 * After the merge, the framework broadcasts a [[ViewerStateDelta]]
 * to every live session for the viewer so other tabs / devices
 * apply the same patch.
 *
 * If no record exists yet for `(viewer, scope)`, `patch` is
 * persisted as the initial full state — first delta acts like an
 * upsert.
 */
case class UpdateViewerStateDelta(scope: String,
                                  patch: ViewerStatePayload) extends Notice derives RW
