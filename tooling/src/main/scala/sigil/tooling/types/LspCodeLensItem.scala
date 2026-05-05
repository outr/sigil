package sigil.tooling.types

import fabric.rw.*

case class LspCodeLensItem(position: LspPosition, title: Option[String], hasCommand: Boolean) derives RW
