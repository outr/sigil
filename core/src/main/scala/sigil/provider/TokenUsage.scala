package sigil.provider

import fabric.Json
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

object TokenUsage {

  /** Build a [[TokenUsage]] from a provider's `usage` JSON object.
    *
    * Each provider names the three int fields differently
    * (`prompt_tokens` vs `input_tokens` vs `promptTokenCount`, etc.);
    * the caller supplies the key names. When `totalKey` is `None` the
    * total is computed as `prompt + completion` — some providers
    * (notably Anthropic) don't carry a total field on the wire.
    * Missing fields read as `0`. */
  def fromJson(json: Json,
               promptKey: String,
               completionKey: String,
               totalKey: Option[String] = None): TokenUsage = {
    val prompt = json.get(promptKey).map(_.asInt).getOrElse(0)
    val completion = json.get(completionKey).map(_.asInt).getOrElse(0)
    val total = totalKey match {
      case Some(key) => json.get(key).map(_.asInt).getOrElse(0)
      case None      => prompt + completion
    }
    TokenUsage(promptTokens = prompt, completionTokens = completion, totalTokens = total)
  }
}
