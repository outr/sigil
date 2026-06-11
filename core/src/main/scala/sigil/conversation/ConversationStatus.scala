package sigil.conversation

import fabric.rw.PolyType

/**
 * App-defined, durable lifecycle/category marker for a conversation
 * (sigil #386). Surfaced on [[Conversation.status]] and indexed by
 * [[key]] for server-side filtering ("list only Resolved").
 *
 * Sigil persists, serializes, and indexes this but assigns it NO meaning
 * — the consuming app defines the concrete subtypes (e.g. `Saved`,
 * `Completed`, `Escalated`) and owns every transition. It mirrors
 * [[sigil.provider.Mode]]: a registered open `PolyType` with a stable
 * string discriminator ([[key]]) the framework uses purely for
 * indexing/codegen, plus whatever data fields the app's subtype carries.
 *
 * [[key]] is the queryable discriminator — `Conversation.statusKey` is a
 * plain `String` index over it, so a category query stays payload-
 * independent even when a subtype carries data (a `Completed(at, by)`
 * still answers "all Completed"). Apps own the key namespace; two
 * subtypes sharing a key collide just as two `Mode`s sharing a name would.
 *
 * Intended for DURABLE, intent-bearing state that a user or app rule
 * sets. Do NOT model transient runtime state here (an agent mid-turn,
 * awaiting user) — derive that from the event log instead; persisting it
 * produces stuck states across crashes.
 *
 * Apps that want two independent axes (e.g. a "pinned" flag coexisting
 * with a lifecycle state) keep them separate — a pin is its own concern,
 * not a status value. A single conversation has exactly one status.
 */
trait ConversationStatus {

  /** Stable, app-defined category discriminator (e.g. `"open"`,
    * `"resolved"`). Indexed on [[Conversation.statusKey]] for server-side
    * filtering; the framework treats it as an opaque token. */
  def key: String
}

object ConversationStatus
    extends PolyType[ConversationStatus]()(using scala.reflect.ClassTag(classOf[ConversationStatus])) {

  /** Framework default — every conversation starts here; apps transition
    * away from it. The framework assigns it no behavior, only the "no app
    * status assigned yet" state. Auto-registered (like
    * [[sigil.GlobalSpace]]); apps don't list it in
    * `Sigil.conversationStatusRegistrations`. */
  case object Open extends ConversationStatus {
    val key: String = "open"
  }
}
