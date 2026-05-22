package sigil.tooling.types

import fabric.rw.*

/** Typed [[sigil.tool.ToolOutput]] for `lsp_hover`. `hover` is `None`
  * when the server returned no hover information at the requested
  * position — a normal answer, distinct from a server error. */
case class LspHoverResult(hover: Option[LspHover])
  extends sigil.tool.ToolOutput derives RW
