package sigil.tool

import lightdb.Sort
import lightdb.filter.*
import rapid.Task
import sigil.Sigil

/**
 * Default [[ToolFinder]] in production: BM25-scored search against the
 * tokenized `searchText` index on [[Tool]] (name + description + curated
 * keywords). Returns the top `maxResults` ranked by Lucene relevance,
 * filtered through [[DiscoveryFilter.passesAffinity]] for the active
 * mode + caller's accessible spaces.
 *
 * The static roster's codecs are registered from `Sigil.staticTools`
 * directly and dynamic-record codecs ride `toolRegistrations` /
 * `toolInputRegistrations`, so this finder contributes none of its
 * own. Apps surfacing marketplace-loaded tool classes through a
 * DB-backed finder pass their [[ToolIO]]s via `extraIO`.
 *
 * @param maxResults cap on returned results (default 10). The BM25 ranking
 *                   is what makes this cap useful — without it, every
 *                   tool whose searchText contains any query token would
 *                   come back, defeating the point of `find_capability`.
 */
case class DbToolFinder(sigil: Sigil,
                        extraIO: List[ToolIO[?, ?]] = Nil,
                        maxResults: Int = 10)
  extends ToolFinder {

  override def toolIO: List[ToolIO[?, ?]] = extraIO

  override def apply(request: DiscoveryRequest): Task[List[Tool]] = {
    val tokens = request.keywords.toLowerCase.split("\\s+").filter(_.nonEmpty).toList
    if (tokens.isEmpty) return Task.pure(Nil)

    sigil.withDB(_.tools.transaction { tx =>
      tx.query
        .filter { _ =>
          val keywordClauses = tokens.map { kw =>
            FilterClause(Tool.searchText.exactly(kw), Condition.Should, None)
          }
          Filter.Multi(minShould = 1, filters = keywordClauses)
        }
        .scored
        .sort(Sort.BestMatch())
        .limit(maxResults * 2) // over-fetch; affinity filter trims to maxResults below
        .toList
    }).attempt.flatMap {
      case scala.util.Success(tools) =>
        Task.pure(tools
          .filter(t => DiscoveryFilter.passesAffinity(t, request))
          .take(maxResults))
      case scala.util.Failure(err) =>
        // A row whose polytype is no longer registered (a de-registered
        // tool the boot prune hasn't caught yet) aborts the typed
        // materialization above — a single stale row must not take
        // `find_capability` down. Fall back to a lenient full scan
        // scored in memory.
        scribe.warn(
          s"DbToolFinder: typed catalog read failed (${err.getClass.getSimpleName}: " +
            s"${Option(err.getMessage).getOrElse("")}); falling back to lenient scan")
        sigil.withDB(_.tools.transaction(_.jsonStream.toList)).map { rows =>
          Sigil.decodeToolsLeniently(rows)
            .map(t => t -> DiscoveryFilter.score(t, request.keywords))
            .filter(_._2 > 0.0)
            .sortBy(-_._2)
            .map(_._1)
            .filter(t => DiscoveryFilter.passesAffinity(t, request))
            .take(maxResults)
        }
    }
  }

  override def byName(name: ToolName): Task[Option[Tool]] =
    sigil.withDB(_.tools.transaction { tx =>
      tx.query.filter(_.toolName === name.value).toList.map(_.headOption)
    }).attempt.flatMap {
      case scala.util.Success(result) => Task.pure(result)
      case scala.util.Failure(err) =>
        scribe.warn(
          s"DbToolFinder.byName(${name.value}): typed read failed (${err.getClass.getSimpleName}); " +
            "falling back to lenient scan")
        sigil.withDB(_.tools.transaction(_.jsonStream.toList)).map { rows =>
          Sigil.decodeToolsLeniently(rows).find(_.name == name)
        }
    }
}
