package sigil.conversation

import fabric.rw.*
import lightdb.doc.{JsonConversion, RecordDocument, RecordDocumentModel}
import lightdb.id.Id
import lightdb.time.Timestamp
import rapid.Unique

/**
 * A persisted summary of a compressed run of older events in a single
 * conversation. Stored in [[sigil.db.SigilDB.summaries]]; referenced from
 * [[TurnInput.summaries]] by id so the provider resolves content at
 * render time (no stale embedded copies).
 *
 * Summaries are immutable historical artifacts — once written they don't
 * change. Generation is app-driven (call `Sigil.persistSummary`); the
 * framework doesn't auto-summarize on its own.
 *
 * `tokenEstimate` lets the curator budget summaries alongside memories
 * and frames without re-tokenizing each turn.
 */
case class ContextSummary(text: String,
                          conversationId: Id[sigil.conversation.Conversation],
                          tokenEstimate: Int,
                          created: Timestamp = Timestamp(),
                          modified: Timestamp = Timestamp(),
                          _id: Id[ContextSummary] = ContextSummary.id())
  extends RecordDocument[ContextSummary]

object ContextSummary extends RecordDocumentModel[ContextSummary] with JsonConversion[ContextSummary] {
  implicit override def rw: RW[ContextSummary] = RW.gen

  val conversationId: I[Id[sigil.conversation.Conversation]] = field.index(_.conversationId)

  override def id(value: String = Unique()): Id[ContextSummary] = Id(value)
}
