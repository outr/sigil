package sigil.pipeline

import rapid.Task
import sigil.Sigil
import sigil.event.{Event, TopicChange}
import sigil.signal.Signal

/**
 * Pre-persist transform that stamps every event with its
 * server-canonical topic, so client-side topic visualisations
 * (per-topic colour strips, dividers, ordinal badges) can trust the
 * event's `topicId` / [[Event.topicIndex]] pair without re-deriving
 * anything. The server is the single source of truth for "which
 * position in the stack does this topicId occupy"; hashing
 * `topicId.value` collides on small palettes (~50% chance by topic 5
 * in a 12-colour palette) and walking `TopicChange` history to
 * reconstruct order duplicates work the server already has.
 *
 * Three canonicalization rules, applied against the conversation's
 * current topic stack (`conversation.topics`):
 *
 *   - **On-stack `topicId`** — `topicIndex` is overwritten with
 *     `topics.indexWhere(_.id == topicId)`. Inbound events that
 *     arrive with a stale or made-up index (placeholder pushed by a
 *     client that hasn't seen the latest stack, regenerated id,
 *     etc.) get the canonical value.
 *   - **Off-stack `topicId`** — the event is re-homed onto the
 *     current topic: both `topicId` and `topicIndex` are replaced.
 *     Clients may stamp a placeholder id on outbound events (they
 *     don't track the topic stack); the framework resolves the real
 *     topic here, so a user Message lands on the active topic
 *     instead of carrying an id no Topic record backs. An id that
 *     fell off the stack via a switch-back truncation folds into
 *     the topic the conversation returned to.
 *   - **[[TopicChange]]** — the change's own index reflects the
 *     stack AFTER it applies, because the projection that mutates
 *     the stack runs post-persist: a Switch to a topic not yet on
 *     the stack gets the position it is about to occupy
 *     (`topics.length`); a switch-back or rename keeps the target's
 *     existing position. Without this, every switch-to-new divider
 *     would carry the off-stack fallback instead of the new topic's
 *     ordinal.
 */
object TopicIndexCanonicalizingTransform extends InboundTransform {

  override def apply(signal: Signal, self: Sigil): Task[Signal] = signal match {
    case e: Event =>
      self.withDB(_.conversations.transaction(_.get(e.conversationId))).map {
        case Some(conv) if conv.topics.nonEmpty =>
          e match {
            case tc: TopicChange =>
              val canonical = conv.topics.indexWhere(_.id == tc.topicId) match {
                case n if n >= 0 => n
                case _ => conv.topics.length
              }
              if (tc.topicIndex == canonical) tc
              else tc.withTopic(tc.topicId, canonical)
            case _ =>
              conv.topics.indexWhere(_.id == e.topicId) match {
                case n if n >= 0 =>
                  if (e.topicIndex == n) e
                  else e.withTopic(e.topicId, n)
                case _ =>
                  val current = conv.currentTopic
                  e.withTopic(current.id, conv.topics.length - 1)
              }
          }
        case _ => e
      }
    case other => Task.pure(other)
  }
}
