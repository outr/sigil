package sigil.provider

import fabric.rw.*

/**
 * Per-call token usage counts. `isEstimated = true` marks a synthetic
 * mid-stream estimate emitted by the wire decoder so consumer UIs can
 * render live token tickers during long reasoning / content streams;
 * the final authoritative emission from the provider's `usage` chunk
 * carries `isEstimated = false`. Default is `false` so non-streaming
 * callers stay back-compat.
 */
case class TokenUsage(promptTokens: Int,
                      completionTokens: Int,
                      totalTokens: Int,
                      isEstimated: Boolean = false) derives RW
