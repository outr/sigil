package sigil.tool

import fabric.rw.RW
import lightdb.filter.*
import rapid.Task
import sigil.Sigil

/**
 * Default [[ToolFinder]] in production: queries `SigilDB.tools` for
 * keyword + mode + space filtering, scores via [[DiscoveryFilter]].
 *
 * `toolInputRWs` is computed once at construction time from the set of
 * tool subtypes the app registered with `Sigil.staticTools` /
 * `Sigil.toolRegistrations` — these are the inputs LLM tool calls may
 * deserialize as. Apps with marketplace-loaded tool classes should
 * augment this list.
 */
case class DbToolFinder(sigil: Sigil,
                        override val toolInputRWs: List[RW[? <: ToolInput]]) extends ToolFinder {

  override def apply(request: DiscoveryRequest): Task[List[Tool]] = {
    // Filter inline in Scala — DiscoveryFilter handles mode + space affinity.
    // Apps with very large tool catalogs can override the finder to push
    // these into a DB-side filter.
    sigil.withDB(_.tools.transaction(_.list)).map { candidates =>
      candidates
        .filter(t => DiscoveryFilter.matches(t, request))
        .map(t => t -> DiscoveryFilter.score(t, request.keywords))
        .sortBy(-_._2)
        .map(_._1)
    }
  }

  override def byName(name: ToolName): Task[Option[Tool]] =
    sigil.withDB(_.tools.transaction { tx =>
      tx.query.filter(_.toolName === name.value).toList.map(_.headOption)
    })
}
