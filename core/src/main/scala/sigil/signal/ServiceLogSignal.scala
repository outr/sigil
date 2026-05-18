package sigil.signal

import fabric.rw.*
import lightdb.id.Id
import lightdb.time.Timestamp
import lightdb.util.Nowish
import sigil.service.{Service, ServiceLogLevel}

/**
 * One log line emitted by a registered [[sigil.service.Service]] with
 * `hasStreamingLog = true`. Live-only: never persisted, never
 * replayed on reconnect. Clients with a tail-attached log viewer
 * subscribe to [[sigil.Sigil.signals]] filtered to this Notice keyed
 * to a specific `serviceId`.
 *
 * History beyond the live tail is the service's responsibility — if
 * the service writes a log file, [[sigil.service.Service.logFilePath]]
 * points clients at it. Apps that want a persistent log buffer for a
 * service implement that buffer themselves (in-memory ring, on-disk
 * file, separate Lucene index, …) — the framework deliberately keeps
 * the live channel cheap and asks consumers to take the durability
 * decision on a per-service basis.
 */
case class ServiceLogSignal(serviceId: Id[Service],
                            line: String,
                            level: ServiceLogLevel = ServiceLogLevel.Info,
                            emittedAt: Timestamp = Timestamp(Nowish())) extends Notice derives RW
