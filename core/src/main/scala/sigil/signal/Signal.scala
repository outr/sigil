package sigil.signal

import sigil.PolyType

/**
 * Root of sigil's external wire vocabulary. Every value that crosses the
 * boundary between the runtime and a subscriber (UI, Slack adapter, audit log,
 * test of behavior) is a `Signal`:
 *
 *   - [[sigil.event.Event]] — durable, stateful, persisted to
 *     [[sigil.db.SigilDB.events]]; carries `conversationId` (every Event
 *     belongs to exactly one conversation).
 *   - [[Delta]] — transient update directive that targets an existing Event
 *     to mutate it; carries `conversationId` (always matches the target's).
 *   - [[Notice]] — transient one-shot pulse for client/server messages that
 *     don't fit the persisted-or-mutate-target shape (conversation lifecycle,
 *     query/snapshot pulls, secret request/reply). May or may not pertain
 *     to a specific conversation; subtypes declare their own fields as
 *     needed.
 *
 * The companion is a [[PolyType]] so every concrete subtype registers into the
 * same discriminator, giving consumers a single deserialization path
 * regardless of which kind a given frame is. Pattern-matching on
 * `case e: Event` / `case d: Delta` / `case n: Notice` tells consumers which
 * side they got.
 */
trait Signal

object Signal extends PolyType[Signal]
