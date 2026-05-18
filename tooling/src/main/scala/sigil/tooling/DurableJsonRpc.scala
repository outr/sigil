package sigil.tooling

import rapid.Task

import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong}
import scala.concurrent.duration.{DurationLong, FiniteDuration}

/**
 * Durable JSON-RPC request wrapper. Treats a dropped response as a
 * *wire problem* recovered via idempotent retry, not a tool failure.
 *
 * The contract: a request that races the silence-window detector.
 * If the response arrives first, the call succeeds normally. If
 * the silence window elapses without the future completing AND
 * without any incoming server activity (notification, log line)
 * resetting the clock, the framework cancels the in-flight future
 * and re-issues the request. Server returns the cached result
 * (BSP/LSP queries Sigil performs are idempotent), the future
 * completes this time, and the tool sees a normal result.
 *
 * Two consecutive silence expirations raise
 * [[JsonRpcTransportException]] — the caller (tool wrapper)
 * surfaces it to the agent so it can decide whether to reload the
 * build server or escalate.
 *
 * Activity sources that reset the clock:
 *   - the request future completing (normal path)
 *   - any incoming server notification (handled by
 *     [[BspRecordingBuildClient]] / [[LspRecordingClient]] which
 *     bump the shared `lastActivityAtMillis` accessor)
 *
 * Tuning knobs:
 *   - `silenceWindow` — per-attempt silence threshold. Default 60s
 *     suits most BSP / LSP requests; heavy ones (`compile`,
 *     `dependencyModules`) override to several minutes.
 *   - `maxAttempts` — total request attempts. Default 2 (the
 *     original + one retry).
 *   - `pollInterval` — how often the watcher checks
 *     `lastActivityAtMillis`. Default 200ms.
 */
object DurableJsonRpc {

  /**
   * Issue `makeRequest` with silence-window detection + idempotent
   * retry on response loss. `activitySource` is the
   * [[#lastActivityAtMillis]] accessor on the session's recording
   * client.
   */
  def issueDurable[T](operation: String,
                      silenceWindow: FiniteDuration = 60.seconds,
                      maxAttempts: Int = 2,
                      pollInterval: FiniteDuration =
                        200.millis)(activitySource: () => Long)(makeRequest: () => CompletableFuture[T]): Task[T] = {

    def attempt(n: Int): Task[T] = Task.defer {
      val startedAt = System.currentTimeMillis()
      val outcome = Task.completable[Either[Throwable, T]]
      val resolved = new AtomicBoolean(false)
      val future = makeRequest()

      // Bridge future → outcome.
      future.whenComplete { (value, error) =>
        if (!resolved.compareAndSet(false, true)) ()
        else if (error != null) {
          val unwrapped = error match {
            case ce: java.util.concurrent.CompletionException if ce.getCause != null => ce.getCause
            case other => other
          }
          outcome.success(Left(unwrapped))
        } else outcome.success(Right(value))
      }

      // Watcher fiber — polls for silence.
      val watcher: Task[Unit] = {
        def loop: Task[Unit] = Task.sleep(pollInterval).flatMap { _ =>
          if (resolved.get()) Task.unit
          else {
            val now = System.currentTimeMillis()
            val lastActivity = math.max(activitySource(), startedAt)
            if (now - lastActivity > silenceWindow.toMillis) {
              if (resolved.compareAndSet(false, true)) {
                // Cancel the in-flight future best-effort; the
                // server may still finish processing but its
                // response is no longer relevant to this Task.
                try future.cancel(true)
                catch { case _: Throwable => () }
                outcome.success(Left(SilenceMarker))
              }
              Task.unit
            } else loop
          }
        }
        loop
      }

      watcher.startUnit()

      outcome.flatMap {
        case Right(value) => Task.pure(value)
        case Left(SilenceMarker) =>
          scribe.warn(s"$operation: silence window expired ($silenceWindow) on attempt $n. " +
            s"Suspected JSON-RPC response loss; retrying.")
          if (n >= maxAttempts)
            Task.error(JsonRpcTransportException.silenceExhausted(operation, n, silenceWindow))
          else attempt(n + 1)
        case Left(e) => Task.error(e)
      }
    }

    attempt(1)
  }

  /**
   * Sentinel inside `outcome`'s `Left` branch — distinguishes
   * "silence detector fired" from "future failed with a real
   * error". Local marker only; never leaves the wrapper.
   */
  private object SilenceMarker extends Throwable
}
