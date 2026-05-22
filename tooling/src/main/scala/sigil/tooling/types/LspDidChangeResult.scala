package sigil.tooling.types

import fabric.rw.*

case class LspDidChangeResult(uri: String) extends sigil.tool.ToolOutput derives RW
