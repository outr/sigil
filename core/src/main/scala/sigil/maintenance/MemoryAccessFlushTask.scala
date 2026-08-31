package sigil.maintenance

import rapid.Task
import sigil.Sigil

import scala.concurrent.duration.*

/**
 * Drains the in-memory `accessCount` / `lastAccessedAt` accumulator
 * ([[sigil.Sigil.flushMemoryAccesses]]) on a fixed cadence.
 *
 * Retrieval marks access on every fresh compute. Writing that through
 * synchronously cost a store commit per turn for a pure ranking
 * signal, so the bumps accumulate and land here instead. A process
 * killed between flushes loses at most one interval's worth of
 * counts — the memories themselves are untouched.
 *
 * In [[sigil.Sigil.maintenanceTasks]] by default; the cadence comes
 * from [[sigil.Sigil.memoryAccessFlushInterval]].
 */
case class MemoryAccessFlushTask(override val interval: FiniteDuration) extends MaintenanceTask {
  override def name: String = "memory-access-flush"

  /**
   * Nothing has been retrieved at boot — the first drain waits a full
   * interval rather than opening a transaction for an empty map.
   */
  override def runImmediatelyOnStart: Boolean = false

  override def runOnce(host: Sigil): Task[Unit] = host.flushMemoryAccesses.unit
}
