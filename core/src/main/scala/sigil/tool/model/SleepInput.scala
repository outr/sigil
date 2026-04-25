package sigil.tool.model

import fabric.rw.*
import sigil.tool.ToolInput

/**
 * Input for the `sleep` tool — pause for a given number of milliseconds
 * before continuing. Useful for pacing (polling loops, rate limiting,
 * waiting between retries) and deliberate delays in multi-step
 * workflows.
 */
case class SleepInput(millis: Long) extends ToolInput derives RW
