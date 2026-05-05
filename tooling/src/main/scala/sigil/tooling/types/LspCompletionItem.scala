package sigil.tooling.types

import fabric.rw.*

case class LspCompletionItem(label: String, kind: Option[String], detail: Option[String]) derives RW
