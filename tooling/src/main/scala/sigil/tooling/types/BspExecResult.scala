package sigil.tooling.types

import fabric.rw.*

/**
 * Shared result shape for BSP test / run invocations. `status` is
 * "OK" / "ERROR" / "CANCELLED" / "NO_TARGETS"; `stdout` and `stderr`
 * are the program's captured output during the call.
 */
case class BspExecResult(projectRoot: String,
                         status: String,
                         targetCount: Int,
                         stdout: String,
                         stderr: String)
  derives RW
