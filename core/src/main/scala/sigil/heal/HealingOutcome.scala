package sigil.heal

import fabric.rw.*

/**
 * Outcome of a single heal attempt — the operational signal carried
 * on [[sigil.signal.HealingActivityNotice]] so alerting / dashboards
 * can distinguish the terminal shapes.
 *
 *   - [[Healed]] — the strategy ran in [[HealingMode.Recover]] and
 *     landed at least one correction; the framework will retry the
 *     agent's iteration.
 *   - [[Exhausted]] — the retry that followed a heal also failed.
 *     The agent loop's standard failure path runs (Failure Message
 *     + claim release); the framework will NOT heal again on this
 *     turn.
 *   - [[Failed]] — the strategy matched and ran but resolved zero
 *     corrections against non-empty corruption evidence (a no-op
 *     masquerading as a heal). The framework does NOT pretend it
 *     healed, does NOT retry the iteration, and falls through to the
 *     standard failure path so the user sees a Failure bubble rather
 *     than a silent stall. Sigil #314.
 *   - [[StrictRefused]] — [[HealingMode.Strict]] is in effect; the
 *     framework recorded the corruption but did not heal, and the
 *     original error is re-thrown so the developer hits the failure.
 */
enum HealingOutcome derives RW {
  case Healed
  case Exhausted
  case Failed
  case StrictRefused
}
