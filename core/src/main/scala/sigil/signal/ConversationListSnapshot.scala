package sigil.signal

import fabric.rw.*
import sigil.conversation.Conversation

/**
 * Server→client [[Notice]] carrying the list of conversations the
 * recipient viewer can see. Sent in response to a
 * [[RequestConversationList]] (the framework's default
 * [[sigil.Sigil.handleNotice]] arm) but also valid as an unsolicited
 * push when state changes — the client UI handles snapshots uniformly
 * regardless of why one arrived.
 */
case class ConversationListSnapshot(conversations: List[Conversation]) extends Notice derives RW
