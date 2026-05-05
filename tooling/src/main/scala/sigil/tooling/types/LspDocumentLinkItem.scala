package sigil.tooling.types

import fabric.rw.*

case class LspDocumentLinkItem(position: LspPosition, target: Option[String]) derives RW
