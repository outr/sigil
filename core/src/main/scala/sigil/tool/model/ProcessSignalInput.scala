package sigil.tool.model

import fabric.rw.*
import sigil.tool.ToolInput

/**
 * Input for `process_signal` — send a signal to a registered
 * subprocess. `signal` defaults to [[ProcessSignal.Terminate]]
 * (SIGTERM, grace, then SIGKILL).
 */
case class ProcessSignalInput(handle: String,
                              signal: ProcessSignal = ProcessSignal.Terminate)
  extends ToolInput derives RW
