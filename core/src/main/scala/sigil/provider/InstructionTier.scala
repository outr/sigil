package sigil.provider

import fabric.rw.*

/**
 * How reliably a model follows multi-step instructions.
 *
 * Drives the framework's oversight cadence: the weaker the tier, the
 * more often the progress checkpoint and planner review a running turn.
 */
enum InstructionTier derives RW {
  case Frontier
  case Capable
  case Small
  case Minimal

  /** Divisor applied to the configured checkpoint / planner cadence.
    * Frontier and Capable models run the app's configured cadence
    * unchanged; weaker tiers are reviewed proportionally more often. */
  def cadenceTightening: Int = this match {
    case Frontier => 1
    case Capable  => 1
    case Small    => 2
    case Minimal  => 4
  }

  /** Ceiling on how many capabilities a `find_capability` roster offers
    * this tier. Weak selectors pick worse from long lists, so the
    * roster is capped by count as well as by bytes. `None` leaves the
    * window-derived cap in charge. */
  def rosterCountCeiling: Option[Int] = this match {
    case Frontier => None
    case Capable  => None
    case Small    => Some(8)
    case Minimal  => Some(5)
  }
}
