package spec

import fabric.rw.*
import sigil.tool.ToolInput

/**
 * Empty input — the latch tool takes no arguments.
 */
final case class EagerActiveLatchInput() extends ToolInput derives RW
