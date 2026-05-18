package sigil.tool.process

import fabric.rw.*

/**
 * Result of a `process_output` read. `stdout` / `stderr` are the
 * bytes since `sinceCursor`; `nextCursor` is what the agent passes
 * on the next read to get the delta from this point. `dropped` is
 * true when the requested cursor predates the ring buffer's
 * earliest retained byte (the agent missed some output because the
 * buffer scrolled past it).
 */
case class ProcessOutput(handle: String,
                         stdout: String,
                         stderr: String,
                         sinceCursor: Long,
                         nextCursor: Long,
                         status: ProcessStatus,
                         exitCode: Option[Int],
                         dropped: Boolean)
  derives RW
