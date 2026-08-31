package spec.collideb

import fabric.rw.*
import sigil.tool.ToolInput

/**
 * Shares its SIMPLE class name with `spec.collidea.CollidingProbeInput`.
 * See that type's doc — this pair is the fixture for the boot pass's
 * simple-name collision check.
 */
case class CollidingProbeInput(value: String) extends ToolInput derives RW
