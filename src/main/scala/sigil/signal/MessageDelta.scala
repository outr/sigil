package sigil.signal

import fabric.rw.*
import lightdb.id.Id
import sigil.conversation.Conversation
import sigil.event.Event
import sigil.provider.TokenUsage

/**
 * A transient update to an active [[sigil.event.Message]]. Carries whatever
 * subset of mutations arrive together: content appending, usage accumulation,
 * state transition.
 *
 * The orchestrator applies a MessageDelta by (1) reading the target Message
 * from RocksDB, (2) mutating its content / usage / state fields per the
 * non-empty options, (3) writing the updated Message back, (4) broadcasting
 * the delta itself to subscribers so they can update their own views.
 */
case class MessageDelta(target: Id[Event],
                        conversationId: Id[Conversation],
                        content: Option[ContentDelta] = None,
                        usage: Option[TokenUsage] = None,
                        state: Option[EventState] = None) extends Delta derives RW
