package sigil.db

import fabric.rw.*

/**
 * Provider-recommended default sampling parameters. Each field is `None` when
 * the provider publishes no default; callers should fall back to their own
 * defaults in that case.
 *
 * @param temperature       Sampling temperature (typically 0.0–2.0). Higher = more random.
 * @param topP              Nucleus-sampling cumulative probability cutoff in `(0.0, 1.0]`.
 * @param topK              Cap on the number of highest-probability tokens considered at each step.
 * @param frequencyPenalty  Penalty applied proportional to how often a token has already appeared.
 * @param presencePenalty   Penalty applied once if a token has appeared at all.
 * @param repetitionPenalty Multiplicative penalty discouraging repeated tokens (1.0 = no penalty).
 */
case class ModelDefaultParameters(temperature: Option[Double] = None,
                                  topP: Option[Double] = None,
                                  topK: Option[Int] = None,
                                  frequencyPenalty: Option[Double] = None,
                                  presencePenalty: Option[Double] = None,
                                  repetitionPenalty: Option[Double] = None)
  derives RW
