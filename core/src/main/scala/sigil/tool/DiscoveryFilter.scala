package sigil.tool

/**
 * Reference predicate + scoring used by in-memory [[ToolFinder]]s and
 * specs. DB-backed finders translate the same semantics into index
 * queries.
 *
 * `matches` returns true iff the tool passes mode affinity, space
 * affinity, and at least one of (name / description / keywords)
 * contains a query keyword (case-insensitive substring match).
 *
 * Space affinity: a tool whose `space` is [[sigil.GlobalSpace]] is
 * visible to every caller; otherwise the tool's `space` must be in
 * the caller's accessible-spaces set. Tools have exactly one space —
 * see [[sigil.SpaceId]] for the single-assignment rule.
 *
 * `score` ranks matched tools — same algorithm shape as the legacy
 * `InMemoryToolFinder`, with curated `keywords` boosting between
 * name-part match (6) and exact-name match (10).
 */
object DiscoveryFilter {

  /** Mode + space affinity — independent of keyword matching. Used by
    * [[DbToolFinder]] as a post-filter on the Lucene-ranked results
    * (Lucene handles keyword scoring; affinity is policy). */
  def passesAffinity(tool: Tool, request: DiscoveryRequest): Boolean = {
    val passesModeAffinity  = tool.modes.contains(request.mode.id)
    val passesSpaceAffinity = tool.space == sigil.GlobalSpace || request.callerSpaces.contains(tool.space)
    passesModeAffinity && passesSpaceAffinity
  }

  /** Policy filter — `Exclusive(names)` and `Scoped(names)` restrict the
    * discovery catalog to `names`; `Discoverable(names)` adds those names
    * to the catalog regardless of [[sigil.tool.Tool.modes]] affinity (so
    * a tool flagged for the parent mode shows up under this mode without
    * needing its `modes` set widened); the rest don't restrict.
    *
    * Discovery-side counterpart to the roster-side fold in
    * [[sigil.Sigil.effectiveToolNames]]. Apps that override
    * [[sigil.Sigil.findCapabilities]] either reuse this helper or
    * implement their own (e.g. for per-tenant catalog gating). */
  def passesPolicy(tool: Tool, policy: sigil.provider.ToolPolicy): Boolean =
    policy match {
      case sigil.provider.ToolPolicy.Standard
         | sigil.provider.ToolPolicy.None
         | sigil.provider.ToolPolicy.PureDiscovery
         | sigil.provider.ToolPolicy.Active(_)
         | sigil.provider.ToolPolicy.Discoverable(_) => true
      case sigil.provider.ToolPolicy.Exclusive(names) => names.contains(tool.name)
      case sigil.provider.ToolPolicy.Scoped(names)    => names.contains(tool.name)
    }

  def matches(tool: Tool, request: DiscoveryRequest): Boolean =
    passesAffinity(tool, request) && score(tool, request.keywords) > 0

  def score(tool: Tool, keywords: String): Double = {
    val kws = keywords.toLowerCase.split("\\s+").filter(_.nonEmpty).toList
    if (kws.isEmpty) return 0.0
    val nameLower = tool.name.value.toLowerCase
    val descLower = tool.description.toLowerCase
    val nameParts = nameLower.split("[_\\-\\s]+").toSet
    val toolKws = tool.keywords.map(_.toLowerCase)
    kws.map { kw =>
      var s = 0.0
      if (nameLower == kw) s += 10.0
      else if (toolKws.contains(kw)) s += 8.0
      else if (nameParts.contains(kw)) s += 6.0
      else if (nameLower.contains(kw)) s += 5.0
      if (descLower.contains(kw)) s += 2.0
      s
    }.sum
  }
}
