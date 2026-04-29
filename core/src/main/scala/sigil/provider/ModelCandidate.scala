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
                                cooldownMs: Long = 60_000L) derives RW {

  def retryDelay: FiniteDuration = FiniteDuration(retryDelayMs, "millis")
  def cooldown: FiniteDuration   = FiniteDuration(cooldownMs, "millis")
}
