package sigil.conversation.compression

/**
 * Raised by a compressor when an input is too large to fit any
 * registered model's context AND/OR the wire-protocol body ceiling
 * (`maxChunkBytes`) cannot be satisfied even after chunking — i.e.
 * a single frame's rendered size exceeds the byte cap on its own.
 *
 * Callers (typically [[StandardContextCurator.budgetResolve]])
 * pattern-match and decide whether to skip compression entirely or
 * fall back to "summarize the most-recent N frames" rather than
 * hitting the provider with a guaranteed-to-fail request. Distinct
 * from `RequestOverBudgetException` (which fires from the
 * provider's pre-flight gate after translation) — this one fires
 * before any wire call is attempted, so no quota is burned.
 */
final class CompressionTooLargeException(val estimatedTokens: Long,
                                         val estimatedBytes: Long,
                                         val maxModelContext: Long,
                                         val maxChunkBytes: Long,
                                         val frameCount: Int)
  extends RuntimeException(
    s"Compression input ($frameCount frames, ~$estimatedTokens tokens, $estimatedBytes bytes) " +
      s"exceeds the largest registered model's context ($maxModelContext tokens) " +
      s"AND the per-chunk byte ceiling ($maxChunkBytes bytes). " +
      s"Skip compression for this turn or summarize the most-recent N frames only."
  )
