package sigil.heal

import fabric.rw.*

/**
 * Outcome of a single [[HealingStrategy.apply]] run. The strategy
 * publishes any necessary events into the durable log itself (via
 * [[sigil.Sigil.publish]]) and returns this descriptor for the
 * framework to fold into the audit trail and the
 * [[sigil.signal.HealingActivityNotice]] pulse.
 *
 * @param corrections    one [[HealAction]] per repair landed.
 *                       Empty when the strategy matched the error
 *                       but couldn't fix anything (`remainingIssues`
 *                       should explain why).
 * @param remainingIssues plain-English statements of what stayed
 *                        broken — informs the operator that the
 *                        heal was partial. Carried verbatim onto
 *                        [[sigil.event.ConversationHealed]] so a
 *                        partial fix is visible, not hidden.
 */
case class HealResult(corrections: List[HealAction],
                      remainingIssues: List[String] = Nil)
  derives RW
