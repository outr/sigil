package sigil.conversation

import fabric.rw.*
import lightdb.doc.{JsonConversion, RecordDocument, RecordDocumentModel}
import lightdb.id.Id
import lightdb.time.Timestamp
import sigil.provider.ToolPolicy

/**
 * Per-conversation [[ToolPolicy]] overlay — additive on top of the
 * mode + role policies already folded into the agent's effective
 * roster. When `start_metals` succeeds, it installs an
 * `Active(metals/lsp/bsp tool names)` overlay so subsequent turns
 * can call those tools directly without a `find_capability`
 * round-trip. Generalises to any "I just enabled a capability"
 * tool — webcam, browser session, secrets vault, slack, etc.
 *
 * Sigil bug #97. Multiple overlays merge: the framework folds
 * each one through [[sigil.Sigil.effectiveToolNames]] in
 * `installedAtMs` order so an `Active(a)` + `Active(b)` overlay
 * pair contributes both sets of names.
 *
 * `source` is a free-form discriminator the installer chooses
 * ("start_metals", "skill:web-research", "user-pin:rules-engine")
 * so [[sigil.Sigil.removeConversationToolOverlay]] can drop a
 * specific one without touching peers.
 */
case class ConversationToolOverlay(conversationId: Id[Conversation],
                                   source: String,
                                   policy: ToolPolicy,
                                   installedAt: Timestamp = Timestamp(),
                                   created: Timestamp = Timestamp(),
                                   modified: Timestamp = Timestamp(),
                                   _id: Id[ConversationToolOverlay] =
                                     ConversationToolOverlay.idFor(Conversation.id(""), ""))
  extends RecordDocument[ConversationToolOverlay]

object ConversationToolOverlay extends RecordDocumentModel[ConversationToolOverlay] with JsonConversion[ConversationToolOverlay] {
  implicit override def rw: RW[ConversationToolOverlay] = RW.gen

  val conversationId: I[Id[Conversation]] = field.index(_.conversationId)
  val source: I[String]                    = field.index(_.source)

  /** Deterministic id so a second install with the same `(conversationId,
    * source)` upserts in place. */
  def idFor(conversationId: Id[Conversation], source: String): Id[ConversationToolOverlay] =
    Id(s"${conversationId.value}:$source")

  override def id(value: String): Id[ConversationToolOverlay] = Id(value)
}
