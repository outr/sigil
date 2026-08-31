package sigil.tool.model

import fabric.rw.*

/**
 * Typed result for [[sigil.tool.process.ProcessSignalTool]]. `handle`
 * is the targeted subprocess; `signal` is the signal that was
 * delivered; `delivered` is `true` when the registry found the handle
 * and signalled the child.
 */
case class ProcessSignalOutput(handle: String,
                               signal: ProcessSignal,
                               delivered: Boolean)
  extends sigil.tool.ToolOutput derives RW
