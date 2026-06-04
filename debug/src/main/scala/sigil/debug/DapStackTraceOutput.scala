package sigil.debug

import fabric.rw.*
import sigil.tool.ToolOutput

/**
 * One frame in a stopped thread's call stack. `id` is what the agent
 * passes to `dap_scopes` to inspect locals at that frame.
 */
case class DapStackFrameInfo(id: Int,
                             name: String,
                             source: Option[String],
                             line: Option[Int])
  derives RW

/**
 * Typed result of `dap_stack_trace` — the requested call-stack window.
 */
case class DapStackTraceOutput(frames: List[DapStackFrameInfo]) extends ToolOutput derives RW
