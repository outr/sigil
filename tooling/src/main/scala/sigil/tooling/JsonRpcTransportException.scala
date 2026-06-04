package sigil.tooling

import scala.concurrent.duration.FiniteDuration

/**
 * Raised when a BSP/LSP request's JSON-RPC response fails to arrive
 * after the framework's silence-window retries exhaust. The
 * underlying tool call's *work* may have completed (the server
 * processed the request), but the response was lost on the wire and
 * recovery via idempotent retry didn't fix it.
 *
 * The framework surfaces this as a *transport* exception so tool
 * code (and the agent's error-context classifier) can distinguish
 * "the wire is broken" from "the tool's job genuinely failed".
 *
 *   - `operation` — JSON-RPC method name (e.g. `"buildTarget/dependencyModules"`).
 *   - `attempts` — total request attempts made (typically 2: the original
 *     plus one idempotent retry).
 *   - `silenceWindow` — the per-attempt silence threshold that fired.
 */
final class JsonRpcTransportException(val operation: String,
                                      val attempts: Int,
                                      val silenceWindow: FiniteDuration,
                                      message: String)
  extends RuntimeException(message)

object JsonRpcTransportException {

  /**
   * Convenience constructor with a structured default message.
   */
  def silenceExhausted(operation: String,
                       attempts: Int,
                       silenceWindow: FiniteDuration): JsonRpcTransportException =
    new JsonRpcTransportException(
      operation = operation,
      attempts = attempts,
      silenceWindow = silenceWindow,
      message = s"$operation: no response after $attempts attempt(s), silence window $silenceWindow. " +
        "Either the wire is broken (try the matching reload tool) or the operation genuinely takes longer than the silence window."
    )
}
