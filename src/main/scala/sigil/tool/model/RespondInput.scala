package sigil.tool.model

import fabric.rw.*
import sigil.tool.ToolInput

/**
 * Input for the respond tool. The `content` string uses the multipart format
 * documented in the system prompt — each block begins with a `▶<TYPE>` header
 * on its own line, and continues until the next header or end of input.
 *
 * The string is parsed into typed [[ResponseContent]] blocks via
 * [[MultipartParser]] when the tool executes.
 */
case class RespondInput(content: String) extends ToolInput derives RW
