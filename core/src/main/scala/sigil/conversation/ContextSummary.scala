package sigil.conversation

import fabric.rw.*
import lightdb.doc.{JsonConversion, RecordDocument, RecordDocumentModel}
import lightdb.id.Id
import lightdb.time.Timestamp
import rapid.Unique
import sigil.event.Event

/**
 * A persisted summary of a compressed run of older events in a single
 * conversation. Stored in [[sigil.db.SigilDB.summaries]]; referenced from
 * [[TurnInput.summaries]] by id so the provider resolves content at
 * render time (no stale embedded copies).
 *
 * Summaries are immutable historical artifacts — once written they don't
 * change. Generation is app-driven (call `Sigil.persistSummary`); the
 * framework doesn't auto-summarize on its own — EXCEPT for the
 * intra-turn compactor (sigil #285) which compresses earlier iterations
 * within a long agent loop when budget pressure or natural boundaries
 * fire.
 *
 * `tokenEstimate` lets the curator budget summaries alongside memories
 * and frames without re-tokenizing each turn.
 *
 * `coversEventIds` — sigil #285 — when non-empty, lists every Event
 * id whose frame this summary's text subsumes. The curator filters
 * those events out of [[TurnInput.frames]] on subsequent turns, so the
 * model sees the summary text instead of the original sequence. Empty
 * (the default) for app-driven summaries that paraphrase the whole
 * conversation rather than electively replacing specific events.
 */
case class ContextSummary(text: String,
                          conversationId: Id[sigil.conversation.Conversation],
                          tokenEstimate: Int,
                          coversEventIds: List[Id[Event]] = Nil,
                          created: Timestamp = Timestamp(),
                          modified: Timestamp = Timestamp(),
                          _id: Id[ContextSummary] = ContextSummary.id())
  extends RecordDocument[ContextSummary]

object ContextSummary extends RecordDocumentModel[ContextSummary] with JsonConversion[ContextSummary] {
  implicit override def rw: RW[ContextSummary] = RW.gen

  val conversationId: I[Id[sigil.conversation.Conversation]] = field.index(_.conversationId)

  override def id(value: String = Unique()): Id[ContextSummary] = Id(value)
}
