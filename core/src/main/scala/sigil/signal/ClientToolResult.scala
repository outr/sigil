package sigil.signal

import fabric.rw.*
import lightdb.id.Id
import sigil.conversation.Conversation
import sigil.event.Event

/**
 * Client→server [[Notice]]: the UI's answer to a round-trip client
 * tool call (a [[sigil.tool.client.ClientToolSpec]] registered with
 * `expectsResult = true`). The UI observed the
 * [[sigil.event.ToolInvoke]] on its signal stream, performed the
 * interaction, and reports back — `invokeId` pairs the answer to the
 * parked call. First answer wins; duplicates (a second tab answering
 * late, a retry) are ignored. `isError = true` settles the call as a
 * recoverable tool failure carrying `content` as the reason.
 *
 * Fire-and-forget tools never send this — their invoke settles at
 * dispatch.
 */
case class ClientToolResult(conversationId: Id[Conversation],
                            invokeId: Id[Event],
                            content: String,
                            isError: Boolean = false) extends ConversationNotice derives RW
