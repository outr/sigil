package sigil.provider

/**
 * Structured error metadata parsed from a provider's wire-level error
 * payload. OpenAI-compatible gateways (OpenRouter, DeepInfra,
 * DigitalOcean, llama.cpp) ship a `data: {"error": {...}}` chunk on a
 * 200-OK SSE stream when an upstream subsystem fails mid-generation.
 * The wire decoder lifts the structured fields off `error.metadata`
 * (or equivalent) into this record so downstream layers
 * ([[ErrorClassifier]], retry classifier, OpenRouter routing builder)
 * dispatch on typed fields instead of substring-matching the
 * exception's `getMessage`.
 *
 * `errorType` carries the gateway's categorical reason
 * (`"provider_unavailable"`, `"rate_limited"`, `"upstream_silent"`, …);
 * the retry classifier whitelists the transient categories so a
 * mid-stream upstream timeout fires the framework's existing retry
 * machinery instead of dying the conversation turn.
 *
 * `upstreamProvider` carries the human-readable name of the upstream
 * the gateway routed to (`"Chutes"`, `"Novita"`, …). The
 * [[OpenRouterProvider]] retry path appends this to its
 * `provider.ignore` request field so the next attempt avoids the sick
 * upstream entirely.
 */
case class ProviderErrorMetadata(errorType: Option[String] = None,
                                 upstreamProvider: Option[String] = None)
