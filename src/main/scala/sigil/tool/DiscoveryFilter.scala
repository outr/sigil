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
 * `score` ranks matched tools — same algorithm shape as the legacy
 * `InMemoryToolFinder`, with curated `keywords` boosting between
 * name-part match (6) and exact-name match (10).
 */
object DiscoveryFilter {

  def matches(tool: Tool, request: DiscoveryRequest): Boolean = {
    val passesModeAffinity  = tool.modes.contains(request.mode.id)
    val passesSpaceAffinity = tool.spaces.isEmpty || tool.spaces.intersect(request.callerSpaces).nonEmpty
    if (!(passesModeAffinity && passesSpaceAffinity)) false
    else score(tool, request.keywords) > 0
  }

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
