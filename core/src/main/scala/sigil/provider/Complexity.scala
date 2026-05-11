package sigil.provider

import fabric.rw.*

/**
 * Per-turn complexity tier — the second routing dimension alongside
 * [[WorkType]]. Drives candidate filtering in
 * [[ProviderStrategy.routed]] via
 * [[ModelCandidate.supportedComplexity]]: candidates whose set doesn't
 * include the inferred tier are skipped, with natural fallthrough to
 * the next-tier model in the chain.
 *
 * The agent's `request_escalation` tool bumps the cached tier one
 * step up (Low → Medium → High) for subsequent iterations of the
 * same user turn; the cap-hit (#125) forced-synthesis path can also
 * auto-bump via [[sigil.Sigil.escalateOnCapHit]].
 *
 * Apps that classify per-message wire an `inferComplexity` callback
 * on `ProviderStrategy.routed`; the strategy precomputes whether the
 * routing chain has tier variation and skips the classifier round-
 * trip when it can't change the candidate.
 */
enum Complexity derives RW {

  /** Single fact, simple syntax, one-line answer. The trivial
    * "what time is it" / "what's 2+2" tier. */
  case Low

  /** Multi-step reasoning, focused task, ~5 reasoning steps. The
    * default tier when no classifier runs. */
  case Medium

  /** Architectural, cross-file, deep reasoning, security analysis.
    * The "frontier-when-needed" tier. */
  case High
}

object Complexity {

  /** Tier order for escalation arithmetic. Index 0 = Low, 1 =
    * Medium, 2 = High. */
  val ordered: List[Complexity] = List(Low, Medium, High)

  /** Bump `current` one tier up; `High` stays `High`. Used by
    * `RequestEscalationTool` and the cap-hit forced-synthesis
    * composition. */
  def bumpUp(current: Complexity): Complexity = current match {
    case Low    => Medium
    case Medium => High
    case High   => High
  }
}
