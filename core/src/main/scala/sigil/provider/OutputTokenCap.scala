package sigil.provider

import fabric.rw.*

/**
 * How a turn's `max_tokens` is sized. Replaces the old
 * `GenerationSettings.maxOutputTokens: Option[Int]` (sigil #276).
 *
 * The pre-fix field conflated two intents:
 *
 *   1. "let the model produce up to its own max" (`None`) — but for
 *      providers where the field is required (Anthropic), `None`
 *      silently fell through to a hostile 4096 default that truncated
 *      any real long-form output.
 *
 *   2. "deliberate cap below the model's max for cost / latency /
 *      conciseness" (`Some(N)`) — which forced consumers to look up the
 *      model-specific ceiling out-of-band, hardcode the number into
 *      their settings, and watch the value silently go wrong every time
 *      the model changed.
 *
 * The typed form disambiguates:
 *
 *   - [[ModelMax]] — let the model produce up to its registered max
 *     (`Model.topProvider.maxCompletionTokens`, with provider-side
 *     fallbacks). The default — apps without an explicit cost / latency
 *     constraint never specify a number.
 *   - [[Below]] — a deliberate cap LOWER than the model's max.
 *     Providers clamp values that exceed the registered max with a
 *     scribe warning, so a stale `Below(64000)` against a 32K-output
 *     model degrades gracefully instead of producing a wire reject.
 */
sealed trait OutputTokenCap derives RW

object OutputTokenCap {
  case object ModelMax extends OutputTokenCap

  /** Cap the model's output at `tokens`, even if the model's registered
    * max is higher. Use for deliberate cost / latency / conciseness
    * constraints — NOT to pin the model's max (that's [[ModelMax]]).
    *
    * Providers clamp `tokens` to the model's registered max when the
    * caller asked for a larger value; the warning is server-side, not
    * a hard failure, so swapping to a smaller-output model doesn't
    * break callers. */
  case class Below(tokens: Int) extends OutputTokenCap
}
