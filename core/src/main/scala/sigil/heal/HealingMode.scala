package sigil.heal

import fabric.rw.*

/**
 * Per-instance toggle for how the framework reacts to a
 * [[HealingStrategy]]-matched error during the agent loop.
 *
 *   - [[Recover]] — production default. The framework logs the
 *     original error loudly, publishes
 *     [[sigil.event.ConversationCorruptionDetected]], runs the
 *     matched strategy, publishes the audit
 *     [[sigil.event.ConversationHealed]] / operational
 *     [[sigil.signal.HealingActivityNotice]] pair, and retries the
 *     agent's iteration once.
 *   - [[Strict]] — dev/CI default (`TestSigil`). The framework still
 *     logs the original error and publishes
 *     `ConversationCorruptionDetected` so the failure is recorded,
 *     then publishes
 *     `HealingActivityNotice(outcome = StrictRefused)` and
 *     re-throws the original error WITHOUT running the strategy. The
 *     developer hits the failure; the corruption is traceable from
 *     the log alone.
 */
enum HealingMode derives RW {
  case Recover
  case Strict
}
