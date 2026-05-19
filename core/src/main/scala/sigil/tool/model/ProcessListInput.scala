package sigil.tool.model

import fabric.rw.*
import sigil.tool.ToolInput

/**
 * Input for `process_list`. `scope` defaults to
 * [[ProcessListScope.Current]] (processes spawned by this
 * conversation); [[ProcessListScope.All]] returns every registered
 * handle.
 */
case class ProcessListInput(scope: ProcessListScope = ProcessListScope.Current) extends ToolInput derives RW
