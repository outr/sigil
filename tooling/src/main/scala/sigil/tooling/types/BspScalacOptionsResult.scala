package sigil.tooling.types

import fabric.rw.*

case class BspScalacOptionsResult(projectRoot: String,
                                  items: List[BspTargetScalacOptions],
                                  error: Option[String] = None)
  extends sigil.tool.ToolOutput derives RW
