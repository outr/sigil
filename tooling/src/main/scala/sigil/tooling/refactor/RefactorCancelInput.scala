package sigil.tooling.refactor

import fabric.rw.*
import sigil.tool.ToolInput

/** Input for the cancel step of the refactor session. Takes the
  * opaque `sessionId` returned at preparation time. */
case class RefactorCancelInput(sessionId: String) extends ToolInput derives RW
