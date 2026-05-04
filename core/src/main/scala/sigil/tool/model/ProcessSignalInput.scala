package sigil.tool.model

import fabric.rw.*
import sigil.tool.ToolInput

/**
 * Input for `process_signal` — send a signal to a registered
 * subprocess. `signal` accepts `terminate` (default — SIGTERM,
 * grace, then SIGKILL), `interrupt`, or `kill`.
 */
case class ProcessSignalInput(handle: String,
                              signal: String = "terminate") extends ToolInput derives RW
