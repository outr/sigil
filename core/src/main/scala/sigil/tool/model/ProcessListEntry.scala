package sigil.tool.model

import fabric.rw.*
import lightdb.time.Timestamp

/**
 * One subprocess row in a [[ProcessListOutput]]. `id` is the
 * caller-facing handle; `pid` the OS process id; `startedAt` the
 * spawn time; `command` the command line the process was spawned
 * with.
 */
case class ProcessListEntry(id: String,
                            pid: Long,
                            startedAt: Timestamp,
                            command: String) derives RW
