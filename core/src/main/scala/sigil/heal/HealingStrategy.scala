package sigil.heal

import lightdb.id.Id
import rapid.Task
import sigil.Sigil
import sigil.conversation.Conversation

/**
 * One reactive self-heal — a pair of (does this error match a known
 * corruption pattern?) + (apply the durable-log repair). The framework
 * iterates [[Sigil.healingStrategies]] looking for the first match,
 * then runs the strategy with the structured evidence + a handle to
 * the host Sigil.
 *
 * Implementations split detection from application so the agent-loop
 * handler can build a forensics payload
 * ([[sigil.event.ConversationCorruptionDetected]]) BEFORE the durable
 * repair lands. The two halves both run, in order, when
 * [[Sigil.healingMode]] is `Recover`.
 *
 * Apps add their own strategies via `Sigil.healingStrategies` —
 * concatenate with `super.healingStrategies` to keep the framework
 * default(s).
 *
 * Strategies must be idempotent at apply: a heal that's already been
 * applied (the orphan ToolInvoke already has a paired settle) should
 * return an empty corrections list rather than double-settling.
 */
trait HealingStrategy {

  /** Stable identifier — used as the audit-log strategy name and the
    * polymorphic discriminator when an app registers more than one
    * strategy. Lowercase camelCase by convention
    * (e.g. `"MissingToolResultStrategy"`). */
  def name: String

  /** Does this error look like the corruption shape this strategy
    * heals? Cheap predicate — runs against every classified error,
    * synchronous. Walking strategies is in agent-loop hot-path
    * territory; keep it predicate-only (no DB reads, no allocations). */
  def matches(error: Throwable): Boolean

  /** Extract structured corruption evidence from the error. Called
    * AFTER [[matches]] returns `true`. Returning an empty list means
    * "I match the error shape but have no corruption to describe"
    * — apply will still run with the empty list. */
  def detect(error: Throwable): List[CorruptionEvidence]

  /** Repair the durable log. The strategy publishes whatever Events /
    * Deltas are needed via the host's `publish` (so the same
    * persist → projection → broadcast pipeline runs); the framework
    * collects the returned [[HealResult]] into the audit
    * [[sigil.event.ConversationHealed]] event and the operational
    * [[sigil.signal.HealingActivityNotice]] pulse. */
  def apply(corruption: List[CorruptionEvidence],
            convId: Id[Conversation],
            host: Sigil): Task[HealResult]
}
