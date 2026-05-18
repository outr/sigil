package sigil.provider

/**
 * Per-attempt context propagated from the framework's transient-retry
 * wrapper into each subsequent [[Provider#call]] invocation. Carries
 * the parsed metadata from the prior attempt's failure so providers
 * with rotation-capable routing (OpenRouter's `provider.ignore`)
 * exclude the failed upstream from the retry attempt.
 *
 * `lastErrorUpstreamProvider` is the human-readable name of the
 * upstream the prior attempt's failure was attributed to
 * (`"Chutes"`, `"Novita"`, …) — pulled from
 * [[ProviderErrorMetadata.upstreamProvider]] on the prior exception.
 * Providers without per-attempt routing knobs (Anthropic direct,
 * Google direct, llama.cpp local) ignore the field; the framework
 * still threads it through uniformly so any provider can opt in.
 */
case class RetryContext(lastErrorUpstreamProvider: Option[String] = None)
