package sigil.tool

import fabric.rw.RW
import rapid.Task
import sigil.participant.ParticipantId

/**
 * Default [[ToolFinder]] backed by an in-memory list of tools. Scoring is
 * a simple weighted keyword match against each tool's `schema.name` and
 * `schema.description`:
 *
 *   - exact name match: 10
 *   - keyword as a name-part (split on `_`, `-`, whitespace): 6
 *   - keyword substring of name: 5
 *   - keyword substring of description: 2
 *
 * Scores sum across keywords. Tools with score 0 are dropped; the rest are
 * returned in descending score order. Ties resolve by original list order.
 *
 * Suitable for apps with a fixed, statically known tool catalog. Apps with
 * DB-backed or marketplace-loaded tools should implement their own
 * `ToolFinder`.
 */
case class InMemoryToolFinder(tools: List[Tool[? <: ToolInput]]) extends ToolFinder {

  override val toolInputRWs: List[RW[? <: ToolInput]] =
    tools.map(_.inputRW).distinctBy(_.definition.className)

  override def apply(keywords: String, participants: List[ParticipantId]): Task[List[Tool[? <: ToolInput]]] = Task {
    val kws = keywords.toLowerCase.split("\\s+").filter(_.nonEmpty).toList
    if (kws.isEmpty) Nil
    else tools
      .map(t => t -> score(t, kws))
      .filter(_._2 > 0)
      .sortBy(-_._2)
      .map(_._1)
  }

  private def score(tool: Tool[? <: ToolInput], keywords: List[String]): Double = {
    val schema = tool.schema
    val nameLower = schema.name.toLowerCase
    val descLower = schema.description.toLowerCase
    val nameParts = nameLower.split("[_\\-\\s]+").toSet
    keywords.map { kw =>
      var s = 0.0
      if (nameLower == kw) s += 10.0
      else if (nameParts.contains(kw)) s += 6.0
      else if (nameLower.contains(kw)) s += 5.0
      if (descLower.contains(kw)) s += 2.0
      s
    }.sum
  }
}
