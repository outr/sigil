package sigil.service

import fabric.rw.*

/**
 * Lifecycle state for a [[Service]] surfaced through
 * [[sigil.signal.ServiceStatusSignal]]. Drives the colour /
 * iconography of the persistent chip the consumer renders for each
 * registered service.
 *
 *   - [[Starting]] — the service is initialising (spawning a
 *     subprocess, opening a connection, warming a model). UI shows a
 *     transitional indicator; no actions yet.
 *   - [[Up]] — the service is healthy and accepting work.
 *   - [[Degraded]] — the service is reachable but constrained
 *     (rate-limited, partial backend outage, slow). The `reason` is a
 *     short human-readable label rendered as the chip's secondary
 *     line. Apps should still try to use the service; the state is
 *     advisory.
 *   - [[Down]] — the service is not currently usable. `intentional`
 *     distinguishes user-initiated stops (paused / disabled in
 *     settings) from outages (process crashed, network unreachable);
 *     UI uses the flag to decide whether to surface a "restart" CTA
 *     or a "retry connection" CTA.
 *   - [[Error]] — terminal failure state with a captured message;
 *     differs from [[Down]] in that it carries diagnostic detail the
 *     UI can surface verbatim (stack-trace snippet, last error line).
 *
 * Sealed trait (not Scala 3 enum) because several cases carry
 * payload fields — keeping them as case classes leaves the door
 * open for future fields without enum-ordinal churn.
 */
sealed trait ServiceState derives RW

object ServiceState {
  case object Starting extends ServiceState
  case object Up extends ServiceState
  case class Degraded(reason: String) extends ServiceState
  case class Down(intentional: Boolean, reason: Option[String] = None) extends ServiceState
  case class Error(message: String) extends ServiceState
}
