package sigil.signal

import lightdb.id.Id
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
 * Event does. `conversationId` is inherited from [[Signal]] (every Delta
 * lives in some conversation) and is typically duplicated from the target's
 * own conversationId so routing layers don't need a DB lookup.
 */
trait Delta extends Signal {
  def target: Id[? <: Event]

  /**
   * Apply this delta's mutation semantics to the given target Event,
   * returning the updated Event. The function is pure — it does not perform
   * the persistence write itself; the caller (typically a per-app
   * subscriber) is responsible for loading the target from the DB, calling
   * `apply`, and saving the result.
   *
   * If the delta doesn't match the target type (e.g. a `MessageDelta` aimed
   * at a `ToolInvoke`), implementations return `target` unchanged.
   */
  def apply(target: Event): Event
}
