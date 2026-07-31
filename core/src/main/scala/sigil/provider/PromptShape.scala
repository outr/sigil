package sigil.provider

import fabric.rw.*

/**
 * How verbosely the section list renders.
 *
 * `Full` is the framework's standard rendering. `Compact` renders the
 * same sections with per-section entry caps — a small model's window is
 * better spent on the task than on a long digest of its own history.
 * Directive framing prefixes are identical in both shapes.
 */
enum PromptShape derives RW {
  case Full
  case Compact

  /** Entries a list-shaped section renders, or `None` for no cap. */
  def entryCap: Option[Int] = this match {
    case Full    => None
    case Compact => Some(5)
  }
}
