package sigil.provider

import fabric.rw.*
import lightdb.id.Id
import sigil.db.Model

import scala.concurrent.duration.{Duration, FiniteDuration}

/**
 * One candidate in a [[ProviderStrategy]]'s fallback chain. The
 * runtime tries candidates in order, applying per-candidate
 * `retryCount` / `retryDelay` before moving on; cooldown gates how
 * long a failed candidate is skipped before being retried fresh.
 *
 * `settings` is an optional per-model overlay on the agent's
 * [[GenerationSettings]] — useful when one model needs a different
 * temperature / max-tokens than another in the same chain.
 */
final case class ModelCandidate(modelId: Id[Model],
                                settings: GenerationSettings = GenerationSettings(),
                                retryCount: Int = 3,
                                retryDelayMs: Long = 0L,
                                cooldownMs: Long = 60_000L,
                                supportedComplexity: Set[Complexity] =
                                  Set(Complexity.Low, Complexity.Medium, Complexity.High))
  derives RW {

  def retryDelay: FiniteDuration = FiniteDuration(retryDelayMs, "millis")
  def cooldown: FiniteDuration = FiniteDuration(cooldownMs, "millis")
}

object ModelCandidate {

  /**
   * Sensible defaults for a local-llama candidate serving a
   * reasoning-template model (qwen3.5-9b, DeepSeek-R1 family, etc).
   * Reasoning channel is off (the chain-of-thought channel inflates
   * latency without improving small-task tool selection) and
   * `maxOutputTokens` is capped at 4096 (generous for normal
   * replies, hard wall against reasoning runaway). Apps wiring a
   * local llama.cpp candidate can pass this instead of discovering
   * the failure mode in production (sigil bug #199).
   *
   * {{{
   *   val llamaC = ModelCandidate(
   *     modelId  = llamaId,
   *     settings = ModelCandidate.localReasoningTemplateDefaults
   *   )
   * }}}
   *
   * Distinct from the framework's automatic forced-synthesis
   * override at the request boundary — that protects against the
   * case where the app didn't tune the candidate. This helper is
   * for apps that want the same conservative shape on every turn,
   * not just the recovery turn.
   */
  val localReasoningTemplateDefaults: GenerationSettings = GenerationSettings(
    maxOutputTokens = Some(4096),
    reasoningMode = ReasoningMode.Off
  )
}
