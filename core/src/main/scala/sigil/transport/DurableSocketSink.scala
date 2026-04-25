package sigil.transport

import fabric.rw.*
import rapid.Task
import sigil.event.Event
import sigil.signal.{Delta, Signal}
import spice.http.durable.DurableSession

/**
 * [[SignalSink]] backed by a single spice [[DurableSession]]. Events
 * go through the session's durable channel (`protocol.push`) so they
 * become resume-able from the eventLog; Deltas are sent as ephemeral
 * frames (`sendEphemeral`) since they describe in-flight state and
 * shouldn't be replayed on reconnect.
 *
 * Pair with a [[SigilDbEventLog]] when constructing the
 * `DurableSocketServer` so the durable channel's `append`/`replay`
 * are sourced from `SigilDB.events` rather than spice's
 * in-memory log.
 *
 * Closing the sink closes the underlying durable session.
 */
final class DurableSocketSink[Id: RW, Info: RW](
  session: DurableSession[Id, Event, Info]
) extends SignalSink {

  override def push(signal: Signal): Task[Unit] = signal match {
    case e: Event =>
      session.protocol.push(e).unit
    case d: Delta =>
      Task { session.protocol.sendEphemeral(d.json(using summon[RW[Signal]])) }
  }

  override def close: Task[Unit] = Task { session.protocol.close() }
}
