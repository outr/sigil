package sigil.tooling.types

import fabric.rw.*

case class LspWorkspaceSymbol(kind: String,
                               name: String,
                               container: Option[String],
                               uri: String,
                               position: Option[LspPosition]) derives RW
