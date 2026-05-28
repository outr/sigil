package sigil.signal

import fabric.rw.*
import lightdb.id.Id
import sigil.conversation.Conversation

/**
 * Sigil #300 — client→server [[Notice]]: "clear this conversation's
 * history." The default [[sigil.Sigil.handleNotice]] arm invokes
 * [[sigil.Sigil.clearConversation]] with `clearedBy = fromViewer`,
 * which advances the conversation's `clearedAt` watermark, evicts
 * per-participant projections + encoded-context caches, and
 * broadcasts the authoritative [[ConversationCleared]] back to every
 * viewer.
 *
 * Mirrors the [[RequestConversationList]] / [[ConversationListSnapshot]]
 * pattern: the inbound `Request*` Notice is the verb (client asks for
 * the action); the outbound non-`Request*` Notice
 * ([[ConversationCleared]]) is the broadcast confirmation. Splitting
 * the two directions across two types avoids the direction-dependent
 * overloading that drove this bug — pre-fix, the client pushed an
 * outbound-shaped [[ConversationCleared]] which the framework had no
 * handler for, so the action silently no-op'd.
 */
case class RequestConversationClear(conversationId: Id[Conversation]) extends Notice derives RW
