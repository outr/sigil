package sigil.tool.model

import fabric.rw.*
import sigil.diagnostics.ProfileSection

/**
 * Per-section token contribution in a [[ContextBreakdownOutput]].
 * `section` names the context region — the same [[ProfileSection]]
 * taxonomy the renderer and the wire profiler use, so a breakdown and
 * a `WireRequestProfile` name the same regions. `tokens` is its
 * heuristic token cost; `count` is the number of items in the region.
 */
case class ContextSectionBreakdown(section: ProfileSection,
                                   tokens: Int,
                                   count: Int)
  derives RW
