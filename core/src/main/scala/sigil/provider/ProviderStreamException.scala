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
 * model itself crashed mid-generation — the agent has nothing useful
 * to retry with, so the framework lets the error propagate to
 * `runAgentLoop`'s handler where it becomes a user-visible
 * `ResponseContent.Failure` Message (Bug #6).
 *
 * `providerKey` identifies the provider that raised (`"llamacpp"`,
 * `"openai"`, …); `code` is the upstream error code when known
 * (HTTP-style int, or `0` if absent); `message` is the upstream
 * description.
 */
final class ProviderStreamException(val providerKey: String,
                                    val code: Int,
                                    val typ: String,
                                    val message_ : String)
  extends RuntimeException(
    if (typ.nonEmpty) s"$providerKey returned $typ ($code) mid-stream: $message_"
    else s"$providerKey returned error ($code) mid-stream: $message_"
  )
