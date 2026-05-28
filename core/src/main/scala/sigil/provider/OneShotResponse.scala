package sigil.provider

import fabric.rw.*
import lightdb.id.Id
import sigil.tool.model.ResponseContent

/**
 * Sigil #299 — the typed return shape of a [[Provider.batch]] entry.
 * Mirrors what `apply(request).drain` would have collected for the
 * same request: accumulated `content` blocks, `usage` counts when the
 * provider reports them, and `error` populated when the per-request
 * call failed (batch APIs surface partial failures — one bad request
 * shouldn't kill the whole stream).
 *
 * The `requestId` correlates back to the originating
 * [[OneShotRequest.requestId]] so consumers fanning a large input
 * stream through `batch` can re-associate completions to source rows
 * regardless of the order they arrive in (batches complete out-of-
 * order across multiple in-flight chunks; the per-line output of a
 * single OpenAI/Anthropic/Gemini batch is in-order but the framework
 * doesn't promise that across chunks).
 *
 * Tools, citations, structured output blocks all flatten into
 * `content` via the same renderer the streaming `apply` path uses —
 * consumers can pattern-match `content` for `ResponseContent.Code`,
 * `ResponseContent.Image`, etc.
 *
 * @param requestId the originating [[OneShotRequest.requestId]] —
 *                  the correlator consumers use to re-pair responses
 *                  with their inputs when streams arrive out-of-order
 * @param content   accumulated output blocks (text, images, tool calls
 *                  serialized as code-fenced JSON, etc.)
 * @param usage     token-count snapshot when the provider reports one.
 *                  OpenAI Batch returns it per-entry; Anthropic Message
 *                  Batches likewise; some providers (Gemini per its
 *                  current docs) only return aggregate counts and
 *                  leave this `None` per-entry.
 * @param error     present when this individual request failed inside
 *                  the batch (validation error, model refusal, etc.).
 *                  A populated `error` does NOT abort the rest of the
 *                  stream — peer requests continue to flow.
 */
case class OneShotResponse(requestId: Id[ProviderRequest],
                           content: Vector[ResponseContent] = Vector.empty,
                           usage: Option[TokenUsage] = None,
                           error: Option[OneShotResponse.Error] = None) derives RW

object OneShotResponse {

  /** Per-request failure surfaced through a batch. The wire / SDK
    * idiosyncrasies of OpenAI / Anthropic / Gemini batch APIs collapse
    * here so downstream consumers can fan-out on the same shape
    * regardless of provider.
    *
    * @param message    short human-readable cause
    * @param code       provider's error code when present
    *                   (`invalid_request_error`, `rate_limit_error`, …)
    * @param recoverable whether retrying the same request might
    *                   succeed (transient — 429 / 502 / upstream
    *                   timeout) or it's terminal (4xx auth, malformed
    *                   request, content-filter refusal). Consumers
    *                   re-queue recoverable entries.
    */
  case class Error(message: String,
                   code: Option[String] = None,
                   recoverable: Boolean = false) derives RW
}
