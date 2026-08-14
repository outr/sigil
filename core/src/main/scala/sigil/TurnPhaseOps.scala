package sigil

import lightdb.id.Id
import rapid.Task
import sigil.conversation.Conversation
import sigil.participant.ParticipantId
import sigil.signal.TurnPhases

import java.util.concurrent.ConcurrentHashMap

/**
 * Per-turn latency instrumentation — the wall-clock counterpart to
 * [[TurnCost]]'s spend attribution. One [[TurnPhaseRecorder]] per live
 * agent claim accumulates the turn's [[TurnPhase]] durations; the claim
 * release publishes them as a transient [[TurnPhases]] Notice.
 *
 * Keyed by conversation, matching the framework's one-live-claim-per-
 * conversation design. Marks against a conversation with no open turn
 * are no-ops, so callers on shared paths (the provider layer, which also
 * serves auxiliary one-shot calls) need no guard of their own.
 */
trait TurnPhaseOps { this: Sigil =>

  private final val turnPhaseRecorders: ConcurrentHashMap[Id[Conversation], TurnPhaseRecorder] =
    new ConcurrentHashMap()

  /** Open a turn's phase accounting. `triggeredAt` is the triggering
    * event's publish instant, so the first phase measures the time the
    * publish pipeline itself spent before the agent could claim. */
  private[sigil] final def openTurnPhases(conversationId: Id[Conversation], triggeredAt: Long): Unit = {
    val recorder = new TurnPhaseRecorder(math.min(triggeredAt, System.currentTimeMillis()))
    turnPhaseRecorders.put(conversationId, recorder)
    recorder.mark(TurnPhase.PublishToClaim)
  }

  /** Attribute everything since the previous mark to `phase`. No-op when
    * `conversationId` has no turn in flight. */
  private[sigil] final def markTurnPhase(conversationId: Id[Conversation], phase: TurnPhase): Unit =
    Option(turnPhaseRecorders.get(conversationId)).foreach(_.mark(phase))

  /** Note that another agent-loop iteration has begun. */
  private[sigil] final def markTurnIteration(conversationId: Id[Conversation]): Unit =
    Option(turnPhaseRecorders.get(conversationId)).foreach(_.nextIteration())

  /** Close the turn's accounting and broadcast the breakdown. Emits
    * nothing when no turn was open. */
  private[sigil] final def closeTurnPhases(conversationId: Id[Conversation],
                                           participantId: ParticipantId): Task[Unit] =
    Option(turnPhaseRecorders.remove(conversationId)) match {
      case None => Task.unit
      case Some(recorder) =>
        recorder.mark(TurnPhase.TerminalToRelease)
        publish(TurnPhases(
          conversationId = conversationId,
          participantId  = participantId,
          iterations     = recorder.iterations,
          totalMs        = recorder.totalMs,
          durations      = recorder.durations
        )).handleError(_ => Task.unit)
    }
}
