package spec

import lightdb.id.Id
import sigil.conversation.compression.{CompactionInvariant, IntraTurnCompactor, StandardIntraTurnCompactor, TurnEventsContext}
import sigil.event.Event

/**
 * Compactor that fires at EVERY iteration boundary — the harshest
 * folding cadence an app can configure. Selection delegates to
 * [[StandardIntraTurnCompactor]], so every shipped
 * [[CompactionInvariant]] (notably the recent-tail window) still
 * governs what may be folded; only the trigger is forced, letting a
 * spec exercise real folding without tuning a token threshold.
 */
case class AlwaysFoldingCompactor(invariants: List[CompactionInvariant]) extends IntraTurnCompactor {
  private val delegate = StandardIntraTurnCompactor(invariants = invariants)

  override def shouldCompact(turnEvents: Vector[Event], estimatedTokens: Long, threshold: Long): Boolean = true

  override def selectFoldable(turnEvents: Vector[Event], ctx: TurnEventsContext): List[Id[Event]] =
    delegate.selectFoldable(turnEvents, ctx)
}
