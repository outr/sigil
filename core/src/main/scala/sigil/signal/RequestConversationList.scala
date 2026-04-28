package sigil.signal

import fabric.rw.*

/**
 * Client→server [[Notice]]: "send me the list of conversations I can
 * see." The server's [[sigil.Sigil.handleNotice]] default-replies with
 * a [[ConversationListSnapshot]] targeted at the requesting viewer.
 *
 * Fire-and-forget — the UI can show a loading indicator and react
 * when the snapshot arrives. There's no correlation id; the snapshot
 * type is enough for the UI to bind the response.
 */
case class RequestConversationList() extends Notice derives RW
