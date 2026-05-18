package sigil.provider

/**
 * Raised by a provider when it detects an unrecoverable error embedded
 * in a streaming response (typically an inline `error` event in an SSE
 * stream that returned HTTP 200 OK).
 *
 * Distinct from HTTP-status errors (handled by spice's `streamLines`
 * path) and from validator / tool-arg failures (which the orchestrator
 * surfaces as Tool-role Messages so the agent can retry on the next
 * iteration). This exception covers the case where the provider /
 * model itself crashed mid-generation — most flavors are
 * candidate-level failures the strategy routes around (the typed
 * dispatch in [[ErrorClassifier.Default]] classifies as Fallthrough by
 * default), but transient upstream-side flakes carry typed
 * [[ProviderErrorMetadata.errorType]] markers
 * (`"provider_unavailable"`, `"upstream_silent"`) the classifier
 * promotes to `Retry`.
 *
 * `providerKey` identifies the provider that raised (`"llamacpp"`,
 * `"openai"`, …); `code` is the upstream error code when known
 * (HTTP-style int, or `0` if absent); `message` is the upstream
 * description; `status` mirrors `code` as an `Option` for callers
 * that prefer the typed-option shape over the sentinel `0`;
 * `errorMetadata` carries the structured upstream details (error
 * category, named upstream provider); `cause` is the underlying
 * throwable when this wraps one.
 */
final class ProviderStreamException(val providerKey: String,
                                    val code: Int,
                                    val typ: String,
                                    val message_ : String,
                                    val status: Option[Int] = None,
                                    val errorMetadata: Option[ProviderErrorMetadata] = None,
                                    val cause: Option[Throwable] = None)
  extends RuntimeException(
    if (typ.nonEmpty) s"$providerKey returned $typ ($code) mid-stream: $message_"
    else s"$providerKey returned error ($code) mid-stream: $message_",
    cause.orNull
  )
