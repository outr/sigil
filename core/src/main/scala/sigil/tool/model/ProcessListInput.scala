package sigil.tool.model

import fabric.rw.*
import sigil.tool.ToolInput

/**
 * Input for `process_list`. `scope = "current"` (default) restricts
 * the listing to processes spawned by this conversation; `scope =
 * "all"` returns every registered handle.
 */
case class ProcessListInput(scope: String = "current") extends ToolInput derives RW
