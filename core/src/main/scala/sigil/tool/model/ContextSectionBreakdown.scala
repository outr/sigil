package sigil.tool.model

import fabric.rw.*

/**
 * Per-section token contribution in a [[ContextBreakdownOutput]].
 * `section` names the context region; `tokens` is its heuristic token
 * cost; `count` is the number of items in the region (frame count,
 * memory count, skill count, or 1 for the mode block).
 */
case class ContextSectionBreakdown(section: ContextSectionKind,
                                   tokens: Int,
                                   count: Int)
  derives RW
