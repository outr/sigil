package sigil.tool.model

import fabric.rw.*
import lightdb.id.Id
import sigil.conversation.Conversation
import sigil.tool.ToolInput

/**
 * Input for `relay_message` — post a Message authored by the calling
 * agent into another conversation it is a participant of (sigil #329).
 * The bridge mechanism for the agent-bridge delegation model (#327):
 * an agent that belongs to both a parent conversation and a worker
 * sub-conversation relays a question up to the user, or an answer back
 * down to the worker.
 *
 * `conversationId` is the target conversation. The emit is authorized
 * only when the calling agent is a participant of it.
 *
 * `content` is the message text.
 *
 * `addressees` optionally directs the relayed message at specific
 * participants of the target conversation (by participant-id value) —
 * pairs with #328 addressed triggering so only the named participant is
 * woken. Each value must resolve to a participant of the target
 * conversation. Omitted / empty = broadcast to every co-participant.
 */
case class RelayMessageInput(conversationId: Id[Conversation],
                             content: String,
                             addressees: Option[List[String]] = None) extends ToolInput derives RW
