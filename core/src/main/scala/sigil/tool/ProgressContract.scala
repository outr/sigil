package sigil.tool

import scala.concurrent.duration.FiniteDuration

/**
 * How a detachable tool's detached run surfaces liveness. Declaring
 * [[Execution.Detachable]] requires one — a long-running tool with no
 * progress story leaves the user staring at a silent tracking handle.
 *
 * @param story          human description of what the tool reports while
 *                       detached (e.g. "reports imported-row counts via
 *                       `ctx.reportProgress` every batch")
 * @param expectedCadence rough interval between progress pulses, when
 *                        the tool can promise one; `None` for
 *                        event-driven reporting with no fixed rhythm
 */
final case class ProgressContract(story: String,
                                  expectedCadence: Option[FiniteDuration] = None)
