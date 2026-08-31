package sigil.signal

import fabric.rw.*
import lightdb.id.Id
import sigil.conversation.Conversation
import sigil.tool.client.ClientToolSpec

/**
 * Client→server [[Notice]]: register UI interaction tools for a
 * conversation. The frontend sends this on conversation load,
 * exposing its screens / panels / actions to the agent without a
 * backend change — the tools become discoverable via
 * `find_capability` and callable like any server tool, with
 * execution observed by the UI on its signal stream (see
 * [[sigil.tool.client.ClientToolSpec]] for the fire-and-forget vs
 * round-trip semantics).
 *
 * `sessionId` is client-generated and scopes the registration's
 * lifetime: re-sending with the same id replaces the session's prior
 * set (the reconnect flow — the fresh registration IS the set), and
 * the transport drops the session's tools on detach. The framework
 * answers with a [[ClientToolsRegistered]] ack naming what was
 * accepted and, per rejected tool, why.
 */
case class RegisterClientTools(conversationId: Id[Conversation],
                               sessionId: String,
                               tools: List[ClientToolSpec],
                               replace: Boolean = true)
  extends ConversationNotice derives RW
