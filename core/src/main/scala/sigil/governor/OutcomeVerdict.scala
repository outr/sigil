package sigil.governor

import sigil.signal.Signal

/** An [[OutcomeGovernor]]'s verdict on one drained iteration.
  *
  * `Emit`'s signals join the turn's own stream, so they are published
  * inside the iteration rather than at the boundary after it. That is the
  * difference that keeps these guards correct: a Tool-role diagnostic
  * published inside the iteration is what the agent loop's post-drain
  * continuation check sees, and — for the naked-text decision — the
  * verdict decides how the turn's own streamed Message settles.
  */
enum OutcomeVerdict {

  /** Nothing to act on in this outcome. */
  case Proceed

  /** Append these signals to the turn's stream, in fold order. */
  case Emit(signals: List[Signal])
}
