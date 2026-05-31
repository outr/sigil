package sigil.tool.model

import fabric.rw.*
import sigil.tool.ToolInput

case class GlobInput(basePath: String,
                     pattern: String,
                     maxResults: Int = 1000,
                     @description("Optional reference to a prior grep or glob result — its `callId` (echoed on that result). When set, the listing is restricted to the files that prior output surfaced, intersecting the glob pattern with that file set.")
                     from: Option[String] = None) extends ToolInput derives RW
