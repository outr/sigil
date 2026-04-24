package bench

/**
 * Wraps a benchmark `main` body with a final try/catch that
 * force-exits the JVM on any unhandled exception.
 *
 * Background: sigil's HTTP layer (Spice + Netty) spins up non-daemon
 * event-loop threads. When the main thread throws, those threads
 * keep the JVM alive indefinitely — the benchmark appears to
 * "hang for an hour" with no output. Calling `System.exit(1)` on
 * any Throwable terminates the JVM cleanly and surfaces the
 * crash immediately.
 *
 * Usage (in each benchmark's `main`):
 * {{{
 *   def main(args: Array[String]): Unit = BenchmarkMain.guard {
 *     ...actual body...
 *     System.exit(0)
 *   }
 * }}}
 *
 * The final `System.exit(0)` is still required on successful
 * completion — `guard` only intervenes on failure.
 */
object BenchmarkMain {
  def guard(body: => Unit): Unit = {
    try body
    catch {
      case e: Throwable =>
        System.err.println(s"FATAL: ${e.getClass.getSimpleName}: ${e.getMessage}")
        e.printStackTrace(System.err)
        System.exit(2)
    }
  }
}
