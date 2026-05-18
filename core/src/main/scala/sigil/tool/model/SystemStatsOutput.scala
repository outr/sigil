package sigil.tool.model

import fabric.rw.*

/**
 * CPU usage snapshot for [[SystemStatsOutput]]. `usagePct` is
 * computed as `100 - idle%` from a single `top -bn1` sample.
 */
case class CpuStats(usagePct: Double, cores: Int) derives RW

/**
 * Memory usage snapshot. All values are MB. `usagePct` is `used /
 * total * 100`.
 */
case class MemoryStats(totalMb: Long, usedMb: Long, availMb: Long, usagePct: Double) derives RW

/**
 * Per-mount disk row. Values are kept as strings since `df -h` emits
 * human-readable sizes (`12G`, `3.4T`) that don't round-trip cleanly
 * to a numeric.
 */
case class DiskStats(mount: String, size: String, used: String, avail: String, usagePct: String) derives RW

/**
 * 1/5/15-minute load averages from `/proc/loadavg`.
 */
case class LoadAverage(load1: Double, load5: Double, load15: Double) derives RW

/**
 * Typed result for [[sigil.tool.util.SystemStatsTool]]. Each
 * section is `None` when the corresponding `include*` flag was
 * `false` on the input; `disks` defaults to empty for the same
 * reason.
 */
case class SystemStatsOutput(cpu: Option[CpuStats],
                             memory: Option[MemoryStats],
                             disks: List[DiskStats],
                             loadAverage: Option[LoadAverage])
  derives RW
