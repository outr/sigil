package sigil.tooling.types

import fabric.rw.*

case class LspSignature(label: String,
                         documentation: Option[String],
                         parameters: List[LspSignatureParam]) derives RW
