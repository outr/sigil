package sigil.transport

import fabric.rw.*
import rapid.Task
import sigil.signal.Signal
import spice.http.durable.DurableSession

/**
 * [[SignalSink]] backed by a single spice [[DurableSession]] whose
 * channel is typed over the full [[Signal]] sum. Every Signal —
 * Event, Delta, Notice — flows through `protocol.push` so the wire
 * frame carries a sequenced, ack-tracked envelope and the receiving
 * end's typed channel reconstructs the same `Signal` subtype the
 * server pushed.
 *
 * `protocol.sendEphemeral` is reserved for non-Signal wire-protocol
 * housekeeping (ping/pong, debug telemetry) outside Sigil's purview;
 * Sigil itself never emits ephemeral payloads.
 *
 * Pair with a [[SigilDbEventLog]] when constructing the
 * `DurableSocketServer` so the durable channel's `append`/`replay`
 * are sourced from `SigilDB.events` rather than spice's in-memory
 * log.
 *
 * Closing the sink closes the underlying durable session.
 */
final class DurableSocketSink[Id: RW, Info: RW](
  session: DurableSession[Id, Signal, Info]
) extends SignalSink {

  override def push(signal: Signal): Task[Unit] = session.protocol.push(signal).unit

  override def close: Task[Unit] = Task(session.protocol.close())
}
