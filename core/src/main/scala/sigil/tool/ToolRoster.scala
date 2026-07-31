package sigil.tool

/**
 * The single name→tool resolution source for one provider request.
 *
 * Constructed once from the request's effective tool list and threaded — as
 * the same instance — to every consumer that needs to resolve a wire-level
 * tool name: the streaming accumulator, the orchestrator's dispatch path,
 * the token estimator, and the request-cache key. One roster per request
 * means the decode side and the dispatch side cannot disagree about which
 * `Tool` a name refers to.
 */
final class ToolRoster private (val tools: Vector[Tool]) {
  private val byName: Map[String, Tool] = tools.iterator.map(t => t.name.value -> t).toMap

  def resolve(name: String): Option[Tool] = byName.get(name)

  def contains(name: String): Boolean = byName.contains(name)

  def isEmpty: Boolean = tools.isEmpty

  def nonEmpty: Boolean = tools.nonEmpty

  def size: Int = tools.size
}

object ToolRoster {
  val empty: ToolRoster = new ToolRoster(Vector.empty)

  def apply(tools: Vector[Tool]): ToolRoster = if (tools.isEmpty) empty else new ToolRoster(tools)
}
