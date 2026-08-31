package sigil.signal

import fabric.rw.*
import lightdb.id.Id
import sigil.TurnPhase
import sigil.conversation.Conversation
import sigil.participant.ParticipantId

/**
 * Per-turn latency breakdown, emitted once when an agent releases its
 * claim. `durations` carries every [[TurnPhase]] in temporal order with
 * the milliseconds that phase accumulated across the turn's iterations;
 * the values sum to `totalMs`, the wall clock from the triggering
 * publish to the release.
 *
 * `ModelStream` is the turn's model time. Every other phase is framework
 * cost, so `totalMs - ModelStream` is the number a consumer watches for
 * orchestration-overhead regressions — the counterpart to what
 * [[sigil.TurnCost]] does for spend.
 *
 * Transient like every other operational pulse: broadcast to live
 * subscribers, never persisted, never replayed.
 */
case class TurnPhases(conversationId: Id[Conversation],
                      participantId: ParticipantId,
                      iterations: Int,
                      totalMs: Long,
                      durations: List[(TurnPhase, Long)])
  extends ConversationNotice derives RW {

  /**
   * Milliseconds attributed to `phase` this turn.
   */
  def apply(phase: TurnPhase): Long = durations.collectFirst { case (p, ms) if p == phase => ms }.getOrElse(0L)

  /**
   * The turn's wall clock minus its model time — the orchestration
   * overhead a consumer tracks for regressions.
   */
  def overheadMs: Long = totalMs - apply(TurnPhase.ModelStream)
}
