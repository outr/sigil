package sigil.conversation.compression

import lightdb.id.Id
import rapid.Task
import sigil.Sigil
import sigil.conversation.{ContextFrame, ContextMemory, ConversationView, MemorySource, MemorySpaceId}
import sigil.participant.ParticipantId

/**
 * Default [[MemoryRetriever]]. Produces two buckets for every turn:
 *
 *   - `memories`: top-K semantically similar to a query derived from
 *     the view (default: the latest non-agent message). Uses
 *     [[Sigil.searchMemories]] over the configured [[MemorySpaceId]]s.
 *     Requires vector wiring on the Sigil
 *     ([[sigil.Sigil.embeddingProvider]] + [[sigil.Sigil.vectorIndex]]
 *     both non-NoOp); without it `searchMemories` falls back to a
 *     space-scoped listing with undefined relevance ordering.
 *
 *   - `criticalMemories`: every [[ContextMemory]] in the configured
 *     spaces whose `source` is [[MemorySource.Critical]]. Surfaces
 *     unconditionally every turn — the provider renders them in a
 *     distinct "Critical directives" section, so safety rules and
 *     identity anchors never depend on the LLM's query phrasing.
 *
 * Critical ids are excluded from the `memories` bucket to avoid
 * double-rendering.
 *
 * Knobs:
 *   - [[spaces]]: which [[MemorySpaceId]]s to search. Apps that keep
 *     a single global space pass `Set(globalSpace)`; apps that
 *     partition (per-user, per-project) pass the relevant subset.
 *   - [[limit]]: cap on semantic-search returns per turn (critical
 *     memories are NOT subject to this cap).
 *   - [[queryFrom]]: derives the search query from the view + chain.
 *     Default is [[StandardMemoryRetriever.lastNonAgentMessage]]
 *     (latest Text frame whose participant isn't the acting agent).
 *     Override for richer strategies (concat of N user messages,
 *     topic-label prefixed, etc.).
 *   - [[includeCritical]]: kill switch for the always-on pass. Set
 *     false when the app wants purely query-driven retrieval.
 */
case class StandardMemoryRetriever(spaces: Set[MemorySpaceId],
                                   limit: Int = 5,
                                   queryFrom: StandardMemoryRetriever.QueryBuilder =
                                     StandardMemoryRetriever.lastNonAgentMessage,
                                   includeCritical: Boolean = true) extends MemoryRetriever {

  override def retrieve(sigil: Sigil,
                        view: ConversationView,
                        chain: List[ParticipantId]): Task[MemoryRetrievalResult] = {
    val criticalsTask: Task[Vector[Id[ContextMemory]]] =
      if (includeCritical) loadCriticals(sigil) else Task.pure(Vector.empty)

    val similarTask: Task[Vector[Id[ContextMemory]]] =
      queryFrom(view, chain) match {
        case None | Some("") => Task.pure(Vector.empty)
        case Some(query) =>
          sigil.searchMemories(query, spaces, limit).map(_.map(_._id).toVector)
      }

    for {
      criticals <- criticalsTask
      similar   <- similarTask
    } yield {
      val criticalSet = criticals.toSet
      val deduped = similar.filterNot(criticalSet.contains)
      MemoryRetrievalResult(memories = deduped, criticalMemories = criticals)
    }
  }

  /** Load every Critical-source memory in the configured spaces.
    * Uses `_.list` + in-memory filter because
    * [[MemorySpaceId]] is polymorphic and the Lucene store doesn't
    * support equality on poly fields — same pattern the specs use. */
  private def loadCriticals(sigil: Sigil): Task[Vector[Id[ContextMemory]]] =
    if (spaces.isEmpty) Task.pure(Vector.empty)
    else sigil.withDB(_.memories.transaction(_.list)).map { all =>
      all.iterator
        .filter(m => m.source == MemorySource.Critical && spaces.contains(m.spaceId))
        .map(_._id)
        .toVector
    }
}

object StandardMemoryRetriever {
  /** Function that derives a retrieval query from the turn's view +
    * participant chain. Returning `None` or an empty string skips
    * the retrieval call for this turn. */
  type QueryBuilder = (ConversationView, List[ParticipantId]) => Option[String]

  /** Walk frames back-to-front; take the first `Text` frame whose
    * participant isn't `chain.last` (the agent about to act). That's
    * typically the user's latest message — the most natural retrieval
    * query. */
  val lastNonAgentMessage: QueryBuilder = (view, chain) => {
    val agent = chain.lastOption
    view.frames.reverseIterator.collectFirst {
      case t: ContextFrame.Text if !agent.contains(t.participantId) => t.content
    }
  }
}
