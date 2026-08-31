package sigil.tool

/**
 * What calling a tool does to the world — the single source every
 * downstream derivation (read cache, wire warning prefix, checkpoint
 * mutation classification) consults:
 *
 *   - [[ReadOnly]] — no side effect beyond the conversation log; the
 *     [[Freshness]] declares how the result relates to time.
 *   - [[Mutating]] — changes external state; the [[MutationTargeting]]
 *     names what it changes (or declares the target unknowable).
 *   - [[Destructive]] — changes user-visible state irreversibly; the
 *     `consequence` is the warning text rendered into the wire
 *     description (`**<consequence>** …`), so the warning cannot be
 *     forgotten because it cannot be absent.
 */
enum Effect {
  case ReadOnly(freshness: Freshness)
  case Mutating(target: MutationTargeting)
  case Destructive(target: MutationTargeting, consequence: String)

  /**
   * The read freshness, when this effect is a read.
   */
  def readFreshness: Option[Freshness] = this match {
    case ReadOnly(f) => Some(f)
    case _ => None
  }

  /**
   * The mutation targeting, when this effect mutates.
   */
  def targeting: Option[MutationTargeting] = this match {
    case Mutating(t) => Some(t)
    case Destructive(t, _) => Some(t)
    case _ => None
  }

  def isReadOnly: Boolean = this match {
    case ReadOnly(_) => true
    case _ => false
  }

  def isDestructive: Boolean = this match {
    case Destructive(_, _) => true
    case _ => false
  }

  /**
   * True for [[Mutating]] and [[Destructive]] — anything that changes
   * state outside the conversation log.
   */
  def mutates: Boolean = this match {
    case ReadOnly(_) => false
    case _ => true
  }
}
