package sigil.viewer

import fabric.rw.*
import lightdb.doc.{JsonConversion, RecordDocument, RecordDocumentModel}
import lightdb.id.Id
import lightdb.time.Timestamp
import rapid.Unique
import sigil.participant.ParticipantId
import sigil.participant.ParticipantId.given
import sigil.viewer.ViewerStatePayload.given

/**
 * Persisted per-viewer UI state record. Keyed by `(participantId,
 * scope)` — `scope` is an app-defined string letting one viewer hold
 * multiple state slots (e.g. global vs. per-project).
 *
 * Read / written via the Notice triple
 * [[sigil.signal.RequestViewerState]] /
 * [[sigil.signal.UpdateViewerState]] /
 * [[sigil.signal.ViewerStateSnapshot]] +
 * [[sigil.signal.DeleteViewerState]]. Apps don't normally touch this
 * record directly — they push Notices and the framework handles the
 * persistence.
 *
 * `payload` is a typed [[ViewerStatePayload]] subtype the app
 * registered; the wire form is fabric's poly-discriminated JSON
 * (simple class name) so Dart codegen emits a real class.
 */
case class ViewerState(participantId: ParticipantId,
                       scope: String,
                       payload: ViewerStatePayload,
                       created: Timestamp = Timestamp(),
                       modified: Timestamp = Timestamp(),
                       _id: Id[ViewerState] = ViewerState.id())
  extends RecordDocument[ViewerState]

object ViewerState extends RecordDocumentModel[ViewerState] with JsonConversion[ViewerState] {
  implicit override def rw: RW[ViewerState] = RW.gen

  // Indexed on string projections — `scope` is a plain string;
  // `participantId.value` keeps the Lucene filter generator on the
  // primitive path that polymorphic ParticipantId can't take.
  val participantIdValue: I[String] = field.index(_.participantId.value)
  val scope: I[String] = field.index(_.scope)

  override def id(value: String = Unique()): Id[ViewerState] = Id(value)

  /** Stable id derived from `(participantId, scope)` — one record per
    * viewer-scope pair. The framework's upsert path uses this id so
    * subsequent updates overwrite in place. */
  def idFor(participantId: ParticipantId, scope: String): Id[ViewerState] =
    Id(s"${participantId.value}::${scope}")
}
