package sigil.tool.model

import fabric.rw.*
import lightdb.time.Timestamp

/**
 * Typed result for [[sigil.tool.process.ProcessSpawnTool]]. `handle`
 * is the caller-facing identifier used to read output or signal the
 * child; `pid` is the OS process id; `startedAt` is the spawn time.
 */
case class ProcessSpawnOutput(handle: String,
                              pid: Long,
                              startedAt: Timestamp) derives RW
