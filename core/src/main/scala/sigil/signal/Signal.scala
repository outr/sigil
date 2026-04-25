package sigil.signal

import lightdb.id.Id
import sigil.PolyType
import sigil.conversation.Conversation

/**
 * Root of sigil's external wire vocabulary. Every value that crosses the
 * boundary between the runtime and a subscriber (UI, Slack adapter, audit log,
 * test of behavior) is a `Signal` — either an [[sigil.event.Event]] (durable,
 * stateful, persisted) or a [[Delta]] (transient update directive).
 *
 * The companion is a [[PolyType]] so every concrete subtype registers into the
 * same discriminator, giving consumers a single deserialization path regardless
 * of whether a given frame is an Event or a Delta. Pattern-matching on
 * `case e: Event` or `case d: Delta` tells consumers which side they got.
 *
 * Every Signal belongs to exactly one conversation. The trait-level
 * `conversationId` lets routing layers (broadcaster, dispatcher) operate on
 * any Signal without needing to know whether it's an Event or a Delta.
 */
trait Signal {
  def conversationId: Id[Conversation]
}

object Signal extends PolyType[Signal]
