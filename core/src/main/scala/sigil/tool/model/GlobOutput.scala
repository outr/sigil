package sigil.tool.model

import fabric.rw.*

/** Typed result for [[sigil.tool.fs.GlobTool]]. `paths` is the
  * matched relative-path list (capped by `maxResults`); `count` is
  * the post-cap size for agents detecting truncation. */
case class GlobOutput(paths: List[String], count: Int) derives RW
