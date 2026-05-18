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

  /**
   * Single fact, simple syntax, one-line answer. The trivial
   * "what time is it" / "what's 2+2" tier.
   */
  case Low

  /**
   * Multi-step reasoning, focused task, ~5 reasoning steps. The
   * default tier when no classifier runs.
   */
  case Medium

  /**
   * Architectural, cross-file, deep reasoning, security analysis.
   * The default "frontier-when-needed" tier for hosted reasoning
   * models.
   */
  case High

  /**
   * The hardest work — long-context multi-file refactors, deep
   * design audits, work that justifies the most expensive frontier
   * model available. Distinct from `High` so apps with a real
   * top-tier candidate (GPT-5.5 / Opus 4.7) can route narrowly to
   * it without `High` getting all the same traffic. Reserve for
   * cases where `High` would actually run on a smaller reasoning
   * model and would clearly leave capability on the table.
   */
  case VeryHigh
}

object Complexity {

  /**
   * Tier order for escalation arithmetic. Index 0 = Low, 1 =
   * Medium, 2 = High, 3 = VeryHigh.
   */
  val ordered: List[Complexity] = List(Low, Medium, High, VeryHigh)

  /**
   * Bump `current` one tier up; `VeryHigh` stays `VeryHigh`. Used by
   * `RequestEscalationTool` and the cap-hit forced-synthesis
   * composition.
   */
  def bumpUp(current: Complexity): Complexity = current match {
    case Low => Medium
    case Medium => High
    case High => VeryHigh
    case VeryHigh => VeryHigh
  }
}
