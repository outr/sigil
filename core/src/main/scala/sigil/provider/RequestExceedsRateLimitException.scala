package sigil.provider

import lightdb.id.Id
import sigil.db.Model

/**
 * Thrown by the framework's pre-flight rate-limit guard (sigil #283)
 * when a single request's estimated input-token count exceeds the
 * model's published `inputTokensPerMinute` ceiling even after the
 * provider's emergency shed has run. Retrying such a request against
 * the same per-minute budget can't succeed — the framework refuses to
 * send it rather than burning attempts under the existing 429-retry
 * loop.
 *
 * Distinct from [[RequestOverBudgetException]]: that one fires when
 * the request exceeds the model's static `contextLength`; this one
 * fires when a single request would exceed the provider's per-minute
 * **input-token rate**. A request can fit comfortably in
 * `contextLength` but still trip the per-minute ceiling on
 * provider-side rate enforcement.
 *
 * Classified as a fatal (non-retryable) error by
 * [[ErrorClassifier.Default]] — apps that surface this error typically
 * suggest lowering `inlineContentThreshold`, tightening compression
 * triggers, or switching to a model whose `inputTokensPerMinute` is
 * higher.
 */
final class RequestExceedsRateLimitException(val estimatedTokens: Int,
                                              val inputTokensPerMinute: Long,
                                              val safetyMargin: Double,
                                              val modelId: Id[Model])
  extends RuntimeException(
    s"Provider request estimated at $estimatedTokens input tokens exceeds the per-request rate ceiling for " +
      s"model ${modelId.value} (${(inputTokensPerMinute * safetyMargin).toLong} tokens — $inputTokensPerMinute " +
      s"input tokens/minute × $safetyMargin safety margin). A single request larger than the per-minute budget " +
      s"can't succeed by itself; retrying against the same ceiling is wasted work. Lower the framework's inline " +
      s"thresholds, tighten compression triggers, or pick a model with a higher inputTokensPerMinute."
  )
