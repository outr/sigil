package sigil.tool.model

import fabric.rw.*
import sigil.tool.ToolInput

case class SystemStatsInput(includeCpu: Boolean = true,
                            includeMemory: Boolean = true,
                            includeDisk: Boolean = true,
                            includeLoadAvg: Boolean = true) extends ToolInput derives RW
