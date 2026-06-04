package sigil.tool.provider

import fabric.rw.*
import sigil.tool.ToolInput

/**
 * Read-only introspection — no parameters.
 */
case class CurrentModelInput() extends ToolInput derives RW
