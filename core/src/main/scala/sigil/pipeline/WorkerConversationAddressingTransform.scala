package sigil.pipeline

import rapid.Task
import sigil.Sigil
import sigil.event.{Message, MessageRole}
import sigil.participant.AgentParticipant
import sigil.signal.Signal

/**
 * Makes the agent-bridge worker conversation (sigil #327) addressing-
 * driven so it terminates. In a *directed worker conversation* — one
 * linked to a parent (`parentConversationId` set) carrying two or more
 * agent participants (the delegating supervisor + the worker) — an
 * agent's outbound Standard Message that carries no explicit
 * `addressees` is rewritten to address the OTHER agent participant(s).
 *
 * Paired with #328's wake-only-when-addressed `TriggerFilter`, this gives
 * the bridge passive co-residence: the worker's response wakes the
 * supervisor (and vice-versa) deliberately, but neither is woken by
 * anything it wasn't addressed on. Termination becomes the supervisor's
 * choice — when the work is done it `relay_message`s the result up to the
 * parent conversation and addresses no one in the worker conversation, so
 * the worker is never re-woken and both settle to Idle. Without this the
 * responses broadcast and each agent wakes the other unconditionally,
 * ping-ponging forever.
 *
 * Only Standard Messages from an agent participant of a directed worker
 * conversation are touched, and only when `addressees` is empty (an
 * explicit address — e.g. the brief `delegate_task` posts — is honored as
 * given). Normal (non-worker) conversations are never modified, so user-
 * facing broadcast semantics are unchanged.
 */
object WorkerConversationAddressingTransform extends InboundTransform {
  override def apply(signal: Signal, self: Sigil): Task[Signal] = signal match {
    case m: Message if m.role == MessageRole.Standard && m.addressees.forall(_.isEmpty) =>
      self.withDB(_.conversations.transaction(_.get(m.conversationId))).map {
        case Some(conv) if conv.parentConversationId.isDefined =>
          val agentIds = conv.participants.collect { case a: AgentParticipant => (a.id: sigil.participant.ParticipantId) }
          if (agentIds.sizeIs >= 2 && agentIds.contains(m.participantId)) {
            val others = agentIds.iterator.filterNot(_ == m.participantId).toSet
            if (others.nonEmpty) m.copy(addressees = Some(others)) else m
          } else m
        case _ => m
      }
    case other => Task.pure(other)
  }
}
