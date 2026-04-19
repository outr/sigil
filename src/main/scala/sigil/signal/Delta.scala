package sigil.signal

import lightdb.id.Id
import sigil.conversation.Conversation
import sigil.event.Event

/**
 * Category marker for transient update directives that target an existing
 * [[sigil.event.Event]] in RocksDB. Applying a Delta on the server:
 *
 *   1. Reads the target Event from the database
 *   2. Mutates its state according to the Delta's fields
 *   3. Writes the updated Event back
 *   4. Broadcasts the Delta to subscribers
 *
 * Deltas never persist as their own records — only their effect on the target
 * Event does. Each Delta carries `conversationId` (redundant with the target's
 * own conversationId) so the broadcast layer can route without a DB lookup.
 */
trait Delta extends Signal {
  def target: Id[? <: Event]
  def conversationId: Id[Conversation]
}
