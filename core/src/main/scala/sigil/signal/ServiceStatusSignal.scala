package sigil.signal

import fabric.rw.*
import lightdb.id.Id
import sigil.service.{Service, ServiceState}

/**
 * Status pulse for a registered [[sigil.service.Service]]. Emitted by
 * the owning subsystem (provider rate-limit observer, MCP connection
 * watcher, subprocess supervisor) whenever the service transitions
 * state, plus on demand when a downstream consumer needs the current
 * snapshot.
 *
 * Framework-level latest-state caching: every published
 * `ServiceStatusSignal` is captured into a per-service-id
 * `AtomicReference` map on [[sigil.Sigil]] and replayed to fresh
 * clients on connect through [[sigil.transport.SignalTransport.attach]].
 * Consumers see the current state immediately on subscribe rather
 * than having to wait for the next state transition.
 *
 * Notice, not Delta — service status doesn't target a persisted
 * [[sigil.event.Event]] (Delta's contract is "mutate a target
 * Event in `db.events`"). Notices are the right channel for
 * transient state pulses; the latest-state cache + replay gives
 * the same "current value visible to new clients" guarantee Delta
 * subscribers get for ongoing event mutations.
 *
 * `primaryLine` is the main chip-rendered label (typically the
 * service name or a short state description). `secondaryLine` is the
 * optional muted-grey subtitle (model name, host, last-error
 * snippet). `metrics` is an arbitrary string-keyed map for
 * service-specific gauges (request rate, queue depth, active
 * connections); the framework treats the map as opaque, clients
 * decide whether / how to surface it.
 */
case class ServiceStatusSignal(serviceId: Id[Service],
                               state: ServiceState,
                               primaryLine: Option[String] = None,
                               secondaryLine: Option[String] = None,
                               metrics: Map[String, String] = Map.empty) extends Notice derives RW
