package sigil.tool

import rapid.Task

/**
 * In-memory [[ToolFinder]] backed by a fixed list — suitable for
 * tests and apps that don't need DB-backed dynamic tool creation.
 * Production apps use [[DbToolFinder]] instead.
 *
 * Filtering uses [[DiscoveryFilter]] — same semantics as the DB-backed
 * finder, but inline. [[byName]] is exact-match, matching
 * [[DbToolFinder]]'s indexed lookup: a tool name resolved
 * case-insensitively here but not in production is a seam that only
 * shows up after deploy.
 */
case class InMemoryToolFinder(tools: List[Tool]) extends ToolFinder {

  override val toolIO: List[ToolIO[?, ?]] = tools.map(_.io)

  override def apply(request: DiscoveryRequest): Task[List[Tool]] = Task {
    tools
      .filter(t => DiscoveryFilter.matches(t, request))
      .map(t => t -> DiscoveryFilter.score(t, request.keywords))
      .sortBy(-_._2)
      .map(_._1)
  }

  override def byName(name: ToolName): Task[Option[Tool]] =
    Task(tools.find(_.name == name))
}
