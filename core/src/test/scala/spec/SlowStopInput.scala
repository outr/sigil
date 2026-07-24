package spec

import fabric.rw.*
import sigil.tool.ToolInput

/**
 * Empty input for the slow-tool Stop fixtures.
 */
final case class SlowStopInput() extends ToolInput derives RW
