package sigil.tooling.types

import fabric.rw.*

case class BspInverseSourcesResult(projectRoot: String,
                                   filePath: String,
                                   targets: List[String],
                                   error: Option[String] = None) extends sigil.tool.ToolOutput derives RW
