package sigil.tool.model

import fabric.rw.*
import sigil.tool.ToolInput

/** Input for the respond tool. The model calls this to send its response to
  * the user. Content is a vector of structured blocks; the UI renders each
  * block by its variant type. */
case class RespondInput(
  content: Vector[ResponseContent],
  title: Option[String] = None
) extends ToolInput derives RW
