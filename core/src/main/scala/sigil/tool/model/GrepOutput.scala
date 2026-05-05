package sigil.tool.model

import fabric.rw.*
import sigil.tool.fs.GrepMatch

/** Typed result for [[sigil.tool.fs.GrepTool]]. Agents iterate
  * `matches` — pattern-matching on `lineNumber` / `filePath` —
  * without parsing JSON. `count` is the post-`maxMatches` size for
  * agents deciding whether the result was truncated. */
case class GrepOutput(matches: List[GrepMatch], count: Int) derives RW
