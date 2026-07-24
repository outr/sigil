package spec

import fabric.rw.*
import sigil.tool.ToolInput

/**
 * Input for the test-only [[FailingTool]] used by worker error-
 * handling coverage. Carries no fields beyond the empty marker —
 * the tool unconditionally throws when executed.
 */
case class FailingToolInput() extends ToolInput derives RW
