package sigil.conversation.compression

import lightdb.id.Id
import rapid.Task
import sigil.Sigil
import sigil.conversation.{ContextFrame, ContextMemory, ConversationView, MemorySpaceId}
import sigil.participant.ParticipantId

/**
 * Default [[MemoryRetriever]] — embeds a query derived from the view
 * and calls [[Sigil.searchMemories]] over the configured
 * [[MemorySpaceId]]s. The returned memory ids land in the curator's
 * `TurnInput.memories`, which the provider renders into the system
 * prompt's Memories section.
 *
 * Requires vector wiring on the Sigil
 * ([[sigil.Sigil.embeddingProvider]] + [[sigil.Sigil.vectorIndex]]
 * both non-NoOp). When not wired, `searchMemories` falls back to a
 * space-scoped listing; this retriever still returns results but
 * relevance ordering is undefined.
 *
 * Knobs:
 *   - [[spaces]]: which [[MemorySpaceId]]s to search. Apps that keep
 *     a single global space pass `Set(globalSpace)`; apps that
 *     partition (per-user, per-project) pass the relevant subset.
 *   - [[limit]]: cap on returned ids per turn.
 *   - [[queryFrom]]: derives the search query from the view + chain.
 *     Default is [[StandardMemoryRetriever.lastNonAgentMessage]]
 *     (latest Text frame whose participant isn't the acting agent).
 *     Override for richer strategies (concat of N user messages,
 *     topic-label prefixed, etc.).
 */
case class StandardMemoryRetriever(spaces: Set[MemorySpaceId],
                                   limit: Int = 5,
                                   queryFrom: StandardMemoryRetriever.QueryBuilder =
                                     StandardMemoryRetriever.lastNonAgentMessage) extends MemoryRetriever {

  override def retrieve(sigil: Sigil,
                        view: ConversationView,
                        chain: List[ParticipantId]): Task[Vector[Id[ContextMemory]]] = {
    queryFrom(view, chain) match {
      case None | Some("") => Task.pure(Vector.empty)
      case Some(query) =>
        sigil.searchMemories(query, spaces, limit).map(_.map(_._id).toVector)
    }
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
