package sigil.skill

import lightdb.Sort
import lightdb.filter.*
import rapid.Task
import sigil.{GlobalSpace, Sigil}
import sigil.tool.DiscoveryRequest

/**
 * BM25 search over [[sigil.db.SigilDB.skills]] — same shape as
 * [[sigil.tool.DbToolFinder]]. Returns the top `maxResults` skills
 * ranked by Lucene relevance against the [[Skill.searchText]] index,
 * post-filtered by:
 *   - **Mode affinity** — `request.mode.id ∈ skill.modes` (or
 *     `skill.modes` empty for "any mode").
 *   - **Space affinity** — `skill.space == GlobalSpace` OR
 *     `request.callerSpaces.contains(skill.space)`.
 *
 * Apps with very large skill catalogs override to push these into a
 * DB-side filter (modeIds / spaceId are indexed for that purpose).
 */
case class DbSkillFinder(sigil: Sigil, maxResults: Int = 10) {

  def apply(request: DiscoveryRequest): Task[List[Skill]] = {
    val tokens = request.keywords.toLowerCase.split("\\s+").filter(_.nonEmpty).toList
    if (tokens.isEmpty) return Task.pure(Nil)

    sigil.withDB(_.skills.transaction { tx =>
      tx.query
        .filter { _ =>
          val keywordClauses = tokens.map { kw =>
            FilterClause(Skill.searchText.exactly(kw), Condition.Should, None)
          }
          Filter.Multi(minShould = 1, filters = keywordClauses)
        }
        .scored
        .sort(Sort.BestMatch())
        .limit(maxResults * 2)
        .toList
    }).map { skills =>
      skills
        .filter(s => passesAffinity(s, request))
        .take(maxResults)
    }
  }

  private def passesAffinity(skill: Skill, request: DiscoveryRequest): Boolean = {
    val passesModeAffinity  = skill.modes.isEmpty || skill.modes.contains(request.mode.id)
    val passesSpaceAffinity = skill.space == GlobalSpace || request.callerSpaces.contains(skill.space)
    passesModeAffinity && passesSpaceAffinity
  }
}
