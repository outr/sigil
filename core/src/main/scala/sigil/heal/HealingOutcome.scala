package sigil.heal

import fabric.rw.*

/**
 * Outcome of a single heal attempt — the operational signal carried
 * on [[sigil.signal.HealingActivityNotice]] so alerting / dashboards
 * can distinguish the three terminal shapes.
 *
 *   - [[Healed]] — the strategy ran in [[HealingMode.Recover]]; the
 *     framework will retry the agent's iteration.
 *   - [[Exhausted]] — the retry that followed a heal also failed.
 *     The agent loop's standard failure path runs (Failure Message
 *     + claim release); the framework will NOT heal again on this
 *     turn.
 *   - [[StrictRefused]] — [[HealingMode.Strict]] is in effect; the
 *     framework recorded the corruption but did not heal, and the
 *     original error is re-thrown so the developer hits the failure.
 */
enum HealingOutcome derives RW {
  case Healed
  case Exhausted
  case StrictRefused
}
