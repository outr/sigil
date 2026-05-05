package sigil.tool.output

import fabric.rw.*

/** One match within a [[ToolOutputSearchResult.Found]]. `lineNumber`
  * is 1-based to match what users see in editors / IDEs.
  * `match_` is the matched line itself; `context` is `match_` plus
  * any surrounding context lines requested via `contextLines`. */
case class ToolOutputSearchHit(lineNumber: Long,
                               match_ : String,
                               context: String) derives RW

/** Typed result for [[ToolOutputSearchTool]]. */
enum ToolOutputSearchResult derives RW {
  case Found(outputId: String,
             pattern: String,
             totalLines: Long,
             matches: List[ToolOutputSearchHit],
             truncated: Boolean)
  case InvalidPattern(outputId: String, pattern: String, error: String)
  case NotFound(outputId: String, error: String)
}
