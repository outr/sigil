package sigil.provider

/**
 * Callback pair the wire layer's silence watchdog uses to relieve
 * server-side starvation of an admitted stream.
 *
 * The slot gate caps on-wire streams at the backend's capacity, but a
 * backend scheduler can still starve one admitted request indefinitely
 * behind a continuously-refilled batch workload (llama.cpp's deferred
 * queue leapfrogged by fresh cache-friendly consults — observed live
 * for 51 minutes). When an admitted stream has received only
 * keepalives past the relief threshold, the watchdog calls [[stall]];
 * the provider's gate then blocks NEW batch admissions while in-flight
 * streams finish, draining the server toward a free slot so the
 * starved task finally gets scheduled. [[clear]] is called when the
 * stream produces meaningful content (or terminates) and batch
 * admission resumes.
 *
 * Implementations must be idempotence-tolerant in aggregate: calls are
 * strictly paired per stream (every `stall` is followed by exactly one
 * `clear`), but multiple streams may stall concurrently — count holds
 * rather than using a boolean.
 */
trait StreamStarvationRelief {
  def stall(): Unit
  def clear(): Unit
}
