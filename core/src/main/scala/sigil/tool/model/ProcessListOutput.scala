package sigil.tool.model

import fabric.rw.*

/**
 * Typed result for [[sigil.tool.process.ProcessListTool]]. `processes`
 * is every registered subprocess matching the requested scope.
 */
case class ProcessListOutput(processes: List[ProcessListEntry]) extends sigil.tool.ToolOutput derives RW
