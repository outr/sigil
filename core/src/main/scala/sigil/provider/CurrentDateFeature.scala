package sigil.provider

import rapid.Task

import java.time.format.DateTimeFormatter
import java.time.{Clock, Instant, ZoneOffset}
import java.util.Locale

/**
 * Tells the model what day it is, and that this value is the only date
 * it may reason from.
 *
 * Without it a model has no clock and answers anyway: asked for today's
 * date it states one, with the same confidence it states a fact, and
 * every downstream computation ("one week out", "is this overdue")
 * inherits the invention. The directive therefore carries as much
 * weight as the value — a model that sees a date but still trusts its
 * own sense of "now" is only accidentally right.
 *
 * Rendered into the [[Placement.VolatileTail]] for two reasons: a
 * long-lived process crosses midnight, so the value is computed per
 * request rather than at boot; and a to-the-minute timestamp in the
 * cacheable prefix would invalidate every turn's prompt cache.
 *
 * Never sheddable. Losing the clock under context pressure would
 * reinstate invented dates on exactly the biggest turns.
 *
 * `clock` is the injection point: apps that source time from somewhere
 * other than the host (a simulation, a replayed session) pass their
 * own, and tests pin a fixed instant so rendered prompts are
 * byte-deterministic. Whatever the clock's zone, the value is read as
 * an instant and formatted in UTC — the rendered day cannot flip with
 * the host's timezone.
 */
case class CurrentDateFeature(clock: Clock = Clock.systemUTC()) extends ContextFeature {
  val id: FeatureId = CurrentDateFeature.Id

  def placement: Placement = Placement.VolatileTail

  def compute(ctx: SectionContext): Task[List[FeatureBody]] =
    Task(List(FeatureBody.prose(CurrentDateFeature.render(clock.instant()))))
}

object CurrentDateFeature {

  val Id: FeatureId = FeatureId("currentDate")

  /** `Locale.US` pins the day and month names: the rendered prompt must
    * not change because the host JVM's default locale did. */
  private val DateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy", Locale.US)
  private val TimeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.US)

  val Header: String = "\n== Current date and time ==\n"

  /** The authoritative-source directive. Stated as a prohibition
    * because the failure mode is not ignorance but confident recall. */
  val Directive: String =
    "Base ALL date and time reasoning on the value above — it is authoritative and current. " +
      "Never infer, recall, or guess today's date from anything else: not from your training data, " +
      "not from dates mentioned in the conversation, not from a previous turn. Resolve \"today\", " +
      "\"now\", \"this week\", and every other relative date against it.\n"

  /** The section text for an instant, normalized to UTC. */
  def render(instant: Instant): String = {
    val utc = instant.atZone(ZoneOffset.UTC)
    Header + s"Today is ${DateFormat.format(utc)}, ${TimeFormat.format(utc)} UTC.\n" + Directive
  }
}
