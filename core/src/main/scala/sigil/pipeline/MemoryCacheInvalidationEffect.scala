package sigil.pipeline

import lightdb.id.Id
import rapid.Task
import sigil.Sigil
import sigil.event.{Event, Message, TopicChange, TopicChangeKind}
import sigil.participant.AgentParticipantId
import sigil.signal.{Delta, EventState, Signal}

/**
 * Default [[SettledEffect]] that invalidates the per-conversation
 * non-critical memory retrieval cache on the two boundaries the
 * retriever's inter-message-stable contract requires:
 *
 *   - A non-agent [[Message]] settling — a user (or external participant)
 *     said something, the next agent turn should re-derive memories.
 *   - A [[TopicChange]] of kind [[TopicChangeKind.Switch]] settling —
 *     the topic genuinely shifted (the two-stage classifier already
 *     distinguished `Refine` from `Switch`); rebuild against the new
 *     topic state.
 *
 * Refines / Renames don't invalidate — the topic identity didn't move,
 * the keyword set is still relevant. The cache lives until one of the
 * two boundaries above. See
 * [[sigil.conversation.compression.MemoryRetrievalCache]] for the
 * caching contract itself.
 */
object MemoryCacheInvalidationEffect extends SettledEffect {
  override def apply(signal: Signal, self: Sigil): Task[Unit] =
    resolveSettledEvent(signal, self).map {
      case Some(m: Message)
        if m.state == EventState.Complete && !m.participantId.isInstanceOf[AgentParticipantId] =>
          self.invalidateMemoryRetrievalCache(m.conversationId)
      case Some(tc: TopicChange) if tc.state == EventState.Complete =>
        tc.kind match {
          case _: TopicChangeKind.Switch => self.invalidateMemoryRetrievalCache(tc.conversationId)
          case _                         => ()
        }
      case _ => ()
    }

  private def resolveSettledEvent(signal: Signal, self: Sigil): Task[Option[Event]] = signal match {
    case e: Event if e.state == EventState.Complete => Task.pure(Some(e))
    case d: Delta =>
      self.withDB(_.events.transaction(_.get(d.target.asInstanceOf[Id[Event]])))
        .map(_.filter(_.state == EventState.Complete))
    case _ => Task.pure(None)
  }
}
