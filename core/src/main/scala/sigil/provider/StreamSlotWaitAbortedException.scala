package sigil.provider

import scala.concurrent.duration.FiniteDuration

/**
 * Raised while waiting for a live-stream slot on a provider with
 * [[Provider.gateStreamingCalls]] enabled, when the wait ends without
 * a permit. Two shapes, distinguished by [[timedOut]]:
 *
 *   - **Timeout** (`timedOut = true`): no slot freed within
 *     [[Provider.streamSlotAcquireTimeout]]. Either a permit leak (a
 *     prior stream's `guarantee` release never fired) or genuinely
 *     more sustained demand than the backend's slot count can drain —
 *     raise the timeout or add capacity.
 *   - **Stop** (`timedOut = false`): the user stopped the conversation
 *     while this call was queued. Abandoning the wait costs nothing —
 *     no wire request was ever issued. The agent loop's stop handling
 *     ends the turn quietly.
 */
final class StreamSlotWaitAbortedException(providerKey: String,
                                           maxConcurrent: Int,
                                           val timedOut: Boolean,
                                           waited: FiniteDuration)
  extends RuntimeException(
    if (timedOut)
      s"$providerKey: no stream slot (maxConcurrent=$maxConcurrent) freed within ${waited.toMillis}ms — " +
        "permit leak from a prior stream, or sustained demand beyond the backend's capacity."
    else
      s"$providerKey: abandoned queued stream-slot wait (maxConcurrent=$maxConcurrent) — " +
        "stop requested for the conversation before a slot freed."
  )
