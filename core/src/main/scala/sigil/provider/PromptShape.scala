package sigil.provider

import fabric.rw.*

/**
 * How verbosely the section list renders.
 *
 * `Full` is the framework's standard rendering and caps nothing — it is
 * byte-for-byte what the framework renders with no profile declared.
 * `Compact` renders the SAME sections with per-section entry caps: a
 * small model's window is better spent on the task than on a long
 * digest of its own history, and the sections that dominate a long turn
 * (retrieved memories, rolling summaries, skills, the recent-tool
 * digest) get tighter caps than the incidental ones. Directive framing
 * prefixes are identical in both shapes.
 */
enum PromptShape derives RW {
  case Full
  case Compact

  /** Entries a list-shaped section renders, or `None` for no cap. */
  def entryCap: Option[Int] = this match {
    case Full    => None
    case Compact => Some(5)
  }

  /** Retrieved (non-critical) memories. Tighter than [[entryCap]] — the
    * retriever's top few carry most of the value and memory lines are
    * long. Pinned directives are never capped. */
  def memoryCap: Option[Int] = this match {
    case Full    => None
    case Compact => Some(3)
  }

  /** Rolling conversation summaries. The curator already selected
    * newest-first; this keeps the tail short. */
  def summaryCap: Option[Int] = this match {
    case Full    => None
    case Compact => Some(3)
  }

  /** Active skill slots. Skills are whole documents, so the count is
    * the lever that matters — the tightest of the caps. */
  def skillCap: Option[Int] = this match {
    case Full    => None
    case Compact => Some(4)
  }
}
