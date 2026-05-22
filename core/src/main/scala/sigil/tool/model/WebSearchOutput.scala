package sigil.tool.model

import fabric.rw.*

/**
 * Typed result for [[sigil.tool.web.WebSearchTool]]. `results` is the
 * ranked hit list (capped by the caller's `maxResults`); `count` is
 * its size, surfaced as a top-level field for convenience.
 */
case class WebSearchOutput(results: List[WebSearchResult], count: Int) extends sigil.tool.ToolOutput derives RW
