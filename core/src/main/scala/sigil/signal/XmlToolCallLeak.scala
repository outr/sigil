package sigil.signal

import fabric.rw.*
import lightdb.id.Id
import sigil.conversation.Conversation
import sigil.db.Model

/**
 * Diagnostic notice — fires when the framework's content sanitizer
 * detected and replaced XML-format tool-call syntax that a model
 * embedded inside a `respond.content` field (or similar typed
 * content argument). The user-facing Message carries a placeholder
 * where the leaked XML span was; this notice gives operators /
 * client UIs the modelId + conversation + excerpt for diagnostics
 * so a developer can identify which model + context triggered the
 * leak without exposing the raw `<tool_call>` text to the end user.
 *
 * As a [[Notice]], `XmlToolCallLeak` is transient — never persisted
 * to `SigilDB.events`, never replayed on reconnect. Clients that
 * care about the diagnostic subscribe live; the user-facing
 * Message still flows through the standard persisted-event path
 * with the placeholder text in place of the leak.
 */
case class XmlToolCallLeak(conversationId: Id[Conversation],
                           modelId: Option[Id[Model]],
                           leakedSpanCount: Int,
                           firstLeakedExcerpt: String) extends Notice derives RW
