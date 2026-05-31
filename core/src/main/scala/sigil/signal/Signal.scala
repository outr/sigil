package sigil.signal

import fabric.rw.PolyType
import lightdb.id.Id
import sigil.conversation.Conversation

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
 *
 * `conversationScope` is the wire-delivery key: `Some(id)` means this signal
 * pertains to one conversation (Events and Deltas always; conversation-bound
 * Notices via [[ConversationNotice]]); `None` means it's global (a catalog
 * snapshot, a viewer-state pulse). [[sigil.transport.SignalTransport]] scopes
 * a per-connection subscription against it — global signals always pass; a
 * scoped signal is delivered only to subscribers watching its conversation.
 */
trait Signal {
  def conversationScope: Option[Id[Conversation]] = None
}

object Signal extends PolyType[Signal]()(using scala.reflect.ClassTag(classOf[Signal]))
