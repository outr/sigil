package sigil.debug

import fabric.rw.*
import sigil.tool.ToolOutput

/** One thread in the debugged program. */
case class DapThreadInfo(id: Int, name: String) derives RW

/** Typed result of `dap_threads` — the active thread roster. */
case class DapThreadsOutput(threads: List[DapThreadInfo]) extends ToolOutput derives RW
