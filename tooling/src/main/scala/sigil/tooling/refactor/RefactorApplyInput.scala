package sigil.tooling.refactor

import fabric.rw.*
import sigil.tool.ToolInput

/** Input for the apply step of the refactor session — takes the
  * opaque `sessionId` returned by the prepare step. */
case class RefactorApplyInput(sessionId: String) extends ToolInput derives RW
