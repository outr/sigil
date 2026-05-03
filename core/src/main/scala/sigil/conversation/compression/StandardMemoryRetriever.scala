package sigil.conversation.compression

import lightdb.Sort
import lightdb.filter.*
import lightdb.id.Id
import lightdb.time.Timestamp
import rapid.Task
import sigil.{GlobalSpace, Sigil, SpaceId}
import sigil.conversation.{ContextFrame, ContextMemory, ConversationView}
import sigil.participant.ParticipantId

/**
 * Default [[MemoryRetriever]]. Produces two buckets for every turn:
 *
 *   - `criticalMemories`: every pinned [[ContextMemory]] in the
 *     spaces the chain can access. Surfaces unconditionally every turn —
 *     the provider renders them in a distinct "Pinned directives"
 *     section, so always-applies rules don't depend on the LLM's query
 *     phrasing.
 *
 *   - `memories`: top-K most relevant non-pinned memories, ranked by
 *     a hybrid Lucene + vector retrieval. The query signal is built
 *     from the active topic's `label` + `summary` + the conversation's
 *     `currentKeywords` (set by the agent's last `respond` call) plus
 *     the most recent non-agent message (default fallback). Vector
 *     search uses [[Sigil.searchMemories]] (requires
 *     [[Sigil.embeddingProvider]] + [[Sigil.vectorIndex]] both
 *     non-NoOp); Lucene uses the [[ContextMemory.searchText]] index
 *     directly. The two ranked lists are combined via Reciprocal Rank
 *     Fusion so a memory that's strong on either signal surfaces, with
 *     memories strong on both ranking highest.
 *
 * Pinned memories are excluded from `memories` so they never double-
 * render — they already appear in the Pinned section.
 *
 * **Spaces are resolved per turn** from
 * [[Sigil.accessibleSpaces(chain)]] plus [[GlobalSpace]] (universally
 * accessible). Apps that need a different scope override
 * `accessibleSpaces` (where the authz lives anyway).
 *
 * Knobs:
 *   - [[limit]]: cap on retrieved-bucket returns per turn (pinned
 *     bucket is NOT subject to this cap).
 *   - [[queryFrom]]: optional override of the per-turn query text.
 *     The default builds from `topic.label + topic.summary +
 *     conversation.currentKeywords + lastNonAgentMessage`. Override
 *     for app-specific signal composition.
 *   - [[includePinned]]: kill switch for the always-on pass.
 *   - [[rrfK]]: RRF smoothing constant (default 60, the standard).
 *     Higher means rankings further down still contribute meaningfully;
 *     lower emphasises top-of-list.
 */
case class StandardMemoryRetriever(limit: Int = 5,
                                   queryFrom: Option[StandardMemoryRetriever.QueryBuilder] = None,
                                   includePinned: Boolean = true,
                                   rrfK: Int = 60) extends MemoryRetriever {

  override def retrieve(sigil: Sigil,
                        view: ConversationView,
                        chain: List[ParticipantId]): Task[MemoryRetrievalResult] =
    sigil.cachedMemoryRetrieve(view.conversationId, computeFresh(sigil, view, chain))

  /** The uncached retrieval path — runs once per (conversation,
    * cache lifetime) under [[Sigil.cachedMemoryRetrieve]]. */
  private def computeFresh(sigil: Sigil,
                           view: ConversationView,
                           chain: List[ParticipantId]): Task[MemoryRetrievalResult] = {
    val now = Timestamp()
    for {
      spaces    <- resolveSpaces(sigil, chain)
      criticals <- if (includePinned) loadPinned(sigil, spaces, now) else Task.pure(Vector.empty)
      regular   <- buildQuery(sigil, view, chain).flatMap {
        case None        => Task.pure(Vector.empty)
        case Some(query) => hybridSearch(sigil, query, spaces, now).map(_.filterNot(criticals.toSet.contains))
      }
    } yield MemoryRetrievalResult(memories = regular, criticalMemories = criticals)
  }

  /** Resolve the per-turn space set: caller's accessible spaces plus
    * [[GlobalSpace]] (universally accessible — pinned memories in
    * Global render across every conversation that can see them). */
  private def resolveSpaces(sigil: Sigil, chain: List[ParticipantId]): Task[Set[SpaceId]] =
    sigil.accessibleSpaces(chain).map(_ + GlobalSpace)

  /** Compose the per-turn retrieval query. Caller-supplied
    * [[queryFrom]] takes precedence; otherwise read the topic state
    * from the conversation + the last non-agent message from the view. */
  private def buildQuery(sigil: Sigil,
                         view: ConversationView,
                         chain: List[ParticipantId]): Task[Option[String]] =
    queryFrom match {
      case Some(builder) => Task.pure(builder(view, chain))
      case None          =>
        sigil.withDB(_.conversations.transaction(_.get(view.conversationId))).map {
          case None => StandardMemoryRetriever.lastNonAgentMessage(view, chain)
          case Some(conv) =>
            val topic = conv.topics.lastOption
            val parts = scala.collection.mutable.ListBuffer.empty[String]
            topic.foreach { t =>
              if (t.label.nonEmpty)   parts += t.label
              if (t.summary.nonEmpty) parts += t.summary
            }
            if (conv.currentKeywords.nonEmpty) parts += conv.currentKeywords.mkString(" ")
            StandardMemoryRetriever.lastNonAgentMessage(view, chain).foreach(parts += _)
            val joined = parts.iterator.map(_.trim).filter(_.nonEmpty).mkString(" ")
            if (joined.nonEmpty) Some(joined) else None
        }
    }

  /** Run vector + Lucene retrieval in parallel, fuse via RRF. Drops
    * memories outside `spaces`, expired records, and pinned memories
    * (those render in the Pinned section already; topical retrieval
    * mustn't double-render). The Lucene leg pushes the `pinned ==
    * false` filter into the index; the vector leg filters post-fetch
    * since the vector payload doesn't carry pinned status. */
  private def hybridSearch(sigil: Sigil,
                           query: String,
                           spaces: Set[SpaceId],
                           now: Timestamp): Task[Vector[Id[ContextMemory]]] = {
    val candidatePool = math.max(limit * 4, 10)
    for {
      vectorHits <- sigil.searchMemories(query, spaces, candidatePool)
      lexicalHits <- luceneHits(sigil, query, spaces, candidatePool)
    } yield {
      val vectorIds  = vectorHits.iterator
        .filterNot(_.pinned)
        .filterNot(StandardMemoryRetriever.isExpired(_, now))
        .map(_._id).toList
      val lexicalIds = lexicalHits.iterator
        .filterNot(StandardMemoryRetriever.isExpired(_, now))
        .map(_._id).toList
      // Confidence shapes the fused score: a 0.5-confidence memory's
      // RRF contributions count for half a 1.0-confidence peer's, so
      // ties break toward higher-confidence facts. Default 1.0 (the
      // norm) means apps that don't write confidence get RRF-only
      // ranking — backward-compatible.
      val confidenceById: Map[Id[ContextMemory], Double] =
        (vectorHits.iterator ++ lexicalHits.iterator).map(m => m._id -> m.confidence).toMap
      val weight: Id[ContextMemory] => Double = id => confidenceById.getOrElse(id, 1.0)
      StandardMemoryRetriever.rrfFuse(List(vectorIds, lexicalIds), rrfK, weight).take(limit).toVector
    }
  }

  /** Lucene BM25 query over `ContextMemory.searchText`. Splits `query`
    * into whitespace tokens and OR-matches; result order is BM25
    * relevance. Filters to the supplied spaces and excludes pinned
    * memories (those render in the Pinned section already). */
  private def luceneHits(sigil: Sigil,
                         query: String,
                         spaces: Set[SpaceId],
                         candidatePool: Int): Task[List[ContextMemory]] = {
    val tokens = query.toLowerCase.split("\\s+").iterator.map(_.trim).filter(_.nonEmpty).toList
    if (tokens.isEmpty || spaces.isEmpty) Task.pure(Nil)
    else sigil.withDB(_.memories.transaction { tx =>
      tx.query
        .filter { _ =>
          val clauses = tokens.map { kw =>
            FilterClause(ContextMemory.searchText.exactly(kw), Condition.Should, None)
          }
          Filter.Multi(minShould = 1, filters = clauses) &&
            (ContextMemory.pinned === false)
        }
        .scored
        .sort(Sort.BestMatch())
        .limit(candidatePool)
        .toList
    }).map(_.filter(m => spaces.contains(m.spaceId)))
  }

  /** Load every pinned memory in the supplied spaces. Pushes the
    * filter into Lucene via the indexed `pinned` boolean and the
    * `spaceIdValue` string projection: `pinned == true AND spaceIdValue
    * IN spaces`. Expiry is filtered in-memory on the (small) result.
    * O(N_pinned_in_accessible_spaces) per turn instead of
    * O(N_total_memories). */
  private def loadPinned(sigil: Sigil, spaces: Set[SpaceId], now: Timestamp): Task[Vector[Id[ContextMemory]]] =
    if (spaces.isEmpty) Task.pure(Vector.empty)
    else sigil.withDB(_.memories.transaction { tx =>
      val spaceClauses = spaces.toList.map { space =>
        FilterClause(ContextMemory.spaceIdValue === space.value, Condition.Should, None)
      }
      tx.query
        .filter(_ =>
          Filter.Multi(minShould = 1, filters = spaceClauses) &&
            (ContextMemory.pinned === true)
        )
        .toList
    }).map { rows =>
      rows.iterator.filterNot(StandardMemoryRetriever.isExpired(_, now)).map(_._id).toVector
    }
}

object StandardMemoryRetriever {
  /** Function that derives a retrieval query from the turn's view +
    * participant chain. Returning `None` or an empty string skips
    * the retrieval call for this turn. */
  type QueryBuilder = (ConversationView, List[ParticipantId]) => Option[String]

  /** Memory is expired (and should be skipped on retrieval) when its
    * `expiresAt` field is set and not in the future. Records with
    * `expiresAt = None` never expire. The store row stays — only the
    * per-turn surfaced set excludes it. Apps that want hard eviction
    * (DB-level deletion) wire a separate sweep effect. */
  def isExpired(m: ContextMemory, now: Timestamp): Boolean =
    m.expiresAt.exists(_.value <= now.value)

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

  /** Reciprocal Rank Fusion of N ranked id lists. Standard formula:
    * `score(d) = sum over rankers r of weight(d) / (k + rank_r(d))`,
    * where rank starts at 1. A document only ranked by one signal
    * still contributes; documents ranked highly across multiple
    * signals accumulate the most score. The `weightOf` hook lets
    * callers shape the fused score with per-document signals like
    * confidence — default 1.0 reproduces the standard RRF formula.
    * Returns ids in descending fused-score order. */
  def rrfFuse[A](rankings: List[List[A]], k: Int, weightOf: A => Double = (_: A) => 1.0): List[A] = {
    val accum = scala.collection.mutable.LinkedHashMap.empty[A, Double]
    rankings.foreach { ranking =>
      ranking.iterator.zipWithIndex.foreach { case (id, idx) =>
        val rank = idx + 1
        val contribution = weightOf(id) / (k + rank)
        accum.updateWith(id) {
          case Some(v) => Some(v + contribution)
          case None    => Some(contribution)
        }
      }
    }
    accum.toList.sortBy { case (_, score) => -score }.map(_._1)
  }
}
