package spec

import fabric.rw.*
import sigil.tool.ToolInput

/** Input for the test-only [[ProgressEmittingTool]] used by Bug #7
  * coverage. Carries no fields beyond the empty marker — the tool
  * publishes a fixed sequence of [[sigil.signal.ToolProgress]]
  * pulses then completes. */
case class ToolProgressInput() extends ToolInput derives RW
