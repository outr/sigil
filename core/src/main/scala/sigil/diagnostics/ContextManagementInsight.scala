package sigil.diagnostics

import fabric.rw.*

/**
 * An actionable observation about the current request's context shape —
 * computed alongside [[RequestProfile]] and shipped on every
 * [[sigil.signal.WireRequestProfile]] notice.
 *
 * Insights answer "what's notable about how this turn's context is
 * being spent?" — token usage alone is just the bill; insights are the
 * diagnosis. Apps surface them next to the always-visible context
 * gauge, agents read them via `context_breakdown` to decide whether
 * to mention concerns to the user.
 *
 * @param level           severity / nudge intensity
 * @param category        which subsystem the insight relates to
 * @param message         one-line human-readable summary
 * @param suggestedAction optional tool-call hint the agent (or app)
 *                        can act on (e.g. `list_pinned_memories`)
 */
case class ContextManagementInsight(level: InsightLevel,
                                    category: InsightCategory,
                                    message: String,
                                    suggestedAction: Option[String] = None) derives RW

/** How urgent / actionable an insight is. */
enum InsightLevel derives RW {
  /** Background telemetry — useful to display, no action needed. */
  case Info

  /** Worth a glance — something is trending toward needing attention. */
  case Warning

  /** Specific change worth considering — paired with a `suggestedAction`. */
  case Recommendation
}

/** Which subsystem an insight relates to. */
enum InsightCategory derives RW {
  case Memory
  case Tools
  case Frames
  case Skills
  case Budget
}
