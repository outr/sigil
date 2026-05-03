package sigil.conversation.compression

import lightdb.Sort
import lightdb.filter.*
import lightdb.id.Id
import lightdb.time.Timestamp
import rapid.Task
import sigil.{Sigil, SpaceId}
import sigil.conversation.{ContextFrame, ContextMemory, ConversationView, MemorySource}
import sigil.participant.ParticipantId

/**
 * Default [[MemoryRetriever]]. Produces two buckets for every turn:
 *
 *   - `criticalMemories`: every [[ContextMemory]] in the configured
 *     spaces whose `source` is [[MemorySource.Critical]]. Surfaces
 *     unconditionally every turn — the provider renders them in a
 *     distinct "Critical directives" section, so safety rules and
 *     identity anchors never depend on the LLM's query phrasing.
 *
 *   - `memories`: top-K most relevant non-critical memories, ranked by
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
 * Critical ids are excluded from `memories` to avoid double-rendering
 * (Critical already renders in its own prompt section).
 *
 * Knobs:
 *   - [[spaces]]: which [[SpaceId]]s to search.
 *   - [[limit]]: cap on retrieved-bucket returns per turn (Critical
 *     bucket is NOT subject to this cap).
 *   - [[queryFrom]]: optional override of the per-turn query text.
 *     The default builds from `topic.label + topic.summary +
 *     conversation.currentKeywords + lastNonAgentMessage`. Override
 *     for app-specific signal composition (last N user messages,
 *     mode-prefixed, etc.).
 *   - [[includeCritical]]: kill switch for the always-on pass.
 *   - [[rrfK]]: RRF smoothing constant (default 60, the standard).
 *     Higher means rankings further down still contribute meaningfully;
 *     lower emphasises top-of-list.
 */
case class StandardMemoryRetriever(spaces: Set[SpaceId],
                                   limit: Int = 5,
                                   queryFrom: Option[StandardMemoryRetriever.QueryBuilder] = None,
                                   includeCritical: Boolean = true,
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
    val criticalsTask: Task[Vector[Id[ContextMemory]]] =
      if (includeCritical) loadCriticals(sigil, now) else Task.pure(Vector.empty)

    for {
      criticals <- criticalsTask
      regular   <- buildQuery(sigil, view, chain).flatMap {
        case None        => Task.pure(Vector.empty)
        case Some(query) => hybridSearch(sigil, query, now).map(_.filterNot(criticals.toSet.contains))
      }
    } yield MemoryRetrievalResult(memories = regular, criticalMemories = criticals)
  }

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
    * memories outside [[spaces]] and expired records. */
  private def hybridSearch(sigil: Sigil,
                           query: String,
                           now: Timestamp): Task[Vector[Id[ContextMemory]]] = {
    val candidatePool = math.max(limit * 4, 10)
    for {
      vectorHits <- sigil.searchMemories(query, spaces, candidatePool)
      lexicalHits <- luceneHits(sigil, query, candidatePool)
    } yield {
      val vectorIds  = vectorHits.iterator.filterNot(StandardMemoryRetriever.isExpired(_, now)).map(_._id).toList
      val lexicalIds = lexicalHits.iterator.filterNot(StandardMemoryRetriever.isExpired(_, now)).map(_._id).toList
      StandardMemoryRetriever.rrfFuse(List(vectorIds, lexicalIds), rrfK).take(limit).toVector
    }
  }

  /** Lucene BM25 query over `ContextMemory.searchText`. Splits `query`
    * into whitespace tokens and OR-matches; result order is BM25
    * relevance (lightdb's `Sort.BestMatch`). Filters to configured
    * spaces. */
  private def luceneHits(sigil: Sigil,
                         query: String,
                         candidatePool: Int): Task[List[ContextMemory]] = {
    val tokens = query.toLowerCase.split("\\s+").iterator.map(_.trim).filter(_.nonEmpty).toList
    if (tokens.isEmpty || spaces.isEmpty) Task.pure(Nil)
    else sigil.withDB(_.memories.transaction { tx =>
      tx.query
        .filter { _ =>
          val clauses = tokens.map { kw =>
            FilterClause(ContextMemory.searchText.exactly(kw), Condition.Should, None)
          }
          Filter.Multi(minShould = 1, filters = clauses)
        }
        .scored
        .sort(Sort.BestMatch())
        .limit(candidatePool)
        .toList
    }).map(_.filter(m => spaces.contains(m.spaceId)))
  }

  /** Load every Critical-source memory in the configured spaces.
    * Uses `_.list` + in-memory filter because [[SpaceId]] is polymorphic
    * and the Lucene store doesn't support equality on poly fields.
    * Expired records are skipped. */
  private def loadCriticals(sigil: Sigil, now: Timestamp): Task[Vector[Id[ContextMemory]]] =
    if (spaces.isEmpty) Task.pure(Vector.empty)
    else sigil.withDB(_.memories.transaction(_.list)).map { all =>
      all.iterator
        .filter(m => m.source == MemorySource.Critical
                  && spaces.contains(m.spaceId)
                  && !StandardMemoryRetriever.isExpired(m, now))
        .map(_._id)
        .toVector
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
    * `score(d) = sum over rankers r of 1 / (k + rank_r(d))`, where rank
    * starts at 1. A document only ranked by one signal still
    * contributes; documents ranked highly across multiple signals
    * accumulate the most score. Returns ids in descending fused-score
    * order. */
  def rrfFuse[A](rankings: List[List[A]], k: Int): List[A] = {
    val accum = scala.collection.mutable.LinkedHashMap.empty[A, Double]
    rankings.foreach { ranking =>
      ranking.iterator.zipWithIndex.foreach { case (id, idx) =>
        val rank = idx + 1
        val contribution = 1.0 / (k + rank)
        accum.updateWith(id) {
          case Some(v) => Some(v + contribution)
          case None    => Some(contribution)
        }
      }
    }
    accum.toList.sortBy { case (_, score) => -score }.map(_._1)
  }
}
