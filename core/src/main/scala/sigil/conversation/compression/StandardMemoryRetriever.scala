package sigil.conversation.compression

import lightdb.filter.*
import lightdb.id.Id
import lightdb.time.Timestamp
import rapid.Task
import sigil.{GlobalSpace, Sigil, SpaceId}
import sigil.conversation.{ContextFrame, ContextMemory, Conversation, MemoryStatus}
import sigil.conversation.compression.retrieval.{BudgetStage, FuseStage, GateStage, MemoryReranker, MemoryRetrievalContext,
  MemoryRetrievalStage, MemoryRetrievalState, RecallStage, RecordStage, RerankStage}
import sigil.participant.ParticipantId
import sigil.provider.Mode

/**
 * Default [[MemoryRetriever]]. Produces two buckets for every turn:
 *
 *   - `criticalMemories`: every pinned [[ContextMemory]] in the
 *     spaces the chain can access. Surfaces unconditionally every turn —
 *     the provider renders them in a distinct "Pinned directives"
 *     section, so always-applies rules don't depend on the LLM's query
 *     phrasing.
 *
 *   - `memories`: top-K most relevant non-pinned memories, produced by
 *     the declared retrieval pipeline
 *     ([[sigil.conversation.compression.retrieval.MemoryRetrievalStage]]):
 *     Recall (lexical ∥ vector, space-pushed-down) → Gate (recallable +
 *     mode affinity) → Fuse (confidence-weighted RRF × recency ×
 *     reinforcement) → Rerank (optional) → Budget (count + token cap)
 *     → Record (access marking). The query signal is built from the
 *     active topic's `label` + `summary` + the conversation's
 *     `currentKeywords` plus the most recent non-agent message.
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
 *   - [[recencyWeight]] / [[reinforcementWeight]] /
 *     [[recencyHalfLifeMs]]: the Fuse stage's bounded tiebreak terms
 *     (see [[retrieval.FuseStage]]). Both weights at `0.0` reproduce
 *     pure confidence-weighted RRF ordering exactly.
 *   - [[reranker]]: optional Rerank stage backend (default `None` —
 *     the stage is an identity pass). Wire
 *     [[retrieval.LLMMemoryReranker]] with a configured model to
 *     opt in.
 *   - [[tokenBudget]]: optional cap on the retrieved bucket's rendered
 *     token cost, applied best-first after the count cap.
 *   - [[recordAccess]]: whether the Record stage batch-bumps
 *     `accessCount` / `lastAccessedAt` on surfaced memories
 *     (fire-and-forget; default on).
 *   - [[pipeline]]: full stage-list replacement. `None` (default)
 *     derives the standard six stages from the knobs above via
 *     [[StandardMemoryRetriever.defaultStages]]; apps that swap a
 *     stage start from that helper and replace the entry.
 */
case class StandardMemoryRetriever(limit: Int = 5,
                                   queryFrom: Option[StandardMemoryRetriever.QueryBuilder] = None,
                                   includePinned: Boolean = true,
                                   rrfK: Int = 60,
                                   /** Per-signal weight on the Lucene leg of RRF.
                                     * Default 2.0 — BM25 scores are semantically
                                     * grounded (keyword match means keyword match),
                                     * while vector signal quality depends on
                                     * embedding model + dimension count. When the
                                     * pools are small (a few candidates), pure-
                                     * symmetric ranking lets a noisy vector
                                     * embedding override a clear lexical match;
                                     * giving Lucene more weight prevents that
                                     * inversion. Apps with high-quality semantic
                                     * embeddings can set both to 1.0 for
                                     * traditional RRF. */
                                   lexicalWeight: Double = 2.0,
                                   /** Per-signal weight on the vector leg of RRF.
                                     * Default 1.0. Pair with `lexicalWeight`. */
                                   vectorWeight: Double = 1.0,
                                   recencyWeight: Double = FuseStage.DefaultRecencyWeight,
                                   reinforcementWeight: Double = FuseStage.DefaultReinforcementWeight,
                                   recencyHalfLifeMs: Long = FuseStage.DefaultRecencyHalfLifeMs,
                                   reranker: Option[MemoryReranker] = None,
                                   tokenBudget: Option[Int] = None,
                                   recordAccess: Boolean = true,
                                   pipeline: Option[List[MemoryRetrievalStage]] = None) extends MemoryRetriever {

  override def retrieve(sigil: Sigil,
                        conversationId: Id[Conversation],
                        frames: Vector[ContextFrame],
                        chain: List[ParticipantId]): Task[MemoryRetrievalResult] =
    sigil.cachedMemoryRetrieve(conversationId, computeFresh(sigil, conversationId, frames, chain))

  /** The stage list the pipeline runs — [[pipeline]] when supplied,
    * otherwise the standard six derived from this retriever's knobs. */
  def stages: List[MemoryRetrievalStage] = pipeline.getOrElse(
    StandardMemoryRetriever.defaultStages(
      limit = limit,
      rrfK = rrfK,
      lexicalWeight = lexicalWeight,
      vectorWeight = vectorWeight,
      recencyWeight = recencyWeight,
      reinforcementWeight = reinforcementWeight,
      recencyHalfLifeMs = recencyHalfLifeMs,
      reranker = reranker,
      tokenBudget = tokenBudget,
      recordAccess = recordAccess
    )
  )

  /** The uncached retrieval path — runs once per (conversation,
    * cache lifetime) under [[Sigil.cachedMemoryRetrieve]]. */
  private def computeFresh(sigil: Sigil,
                           conversationId: Id[Conversation],
                           frames: Vector[ContextFrame],
                           chain: List[ParticipantId]): Task[MemoryRetrievalResult] = {
    val now = Timestamp()
    for {
      spaces       <- resolveSpaces(sigil, chain, conversationId)
      currentMode  <- currentModeOf(sigil, conversationId)
      criticals    <- if (includePinned) loadPinned(sigil, spaces, currentMode, now) else Task.pure(Vector.empty)
      regular      <- buildQuery(sigil, conversationId, frames, chain).flatMap {
        case None        => Task.pure(Vector.empty)
        case Some(query) =>
          val ctx = MemoryRetrievalContext(
            sigil = sigil,
            conversationId = conversationId,
            query = query,
            spaces = spaces,
            currentMode = currentMode,
            now = now,
            limit = limit,
            candidatePool = math.max(limit * 4, 10),
            exclude = criticals.toSet
          )
          stages
            .foldLeft(Task.pure(MemoryRetrievalState())) { (acc, stage) =>
              acc.flatMap(state => stage.run(state, ctx))
            }
            .map(_.ranked.map(_._id))
      }
    } yield MemoryRetrievalResult(memories = regular, criticalMemories = criticals)
  }

  /** Read the conversation's current mode for the per-turn
    * [[ContextMemory.modeAffinity]] gate. `None` when the
    * conversation row has been deleted out from under the retriever
    * (the modeAffinity filter then degrades to "universal-only" —
    * mode-scoped memories drop out). */
  private def currentModeOf(sigil: Sigil, conversationId: Id[Conversation]): Task[Option[Id[Mode]]] =
    sigil.withDB(_.conversations.transaction(_.get(conversationId)))
      .map(_.map(_.currentMode.id))

  /** Resolve the per-turn space set: caller's accessible spaces plus
    * [[GlobalSpace]] (universally accessible — pinned memories in
    * Global render across every conversation that can see them). */
  private def resolveSpaces(sigilArg: Sigil,
                            chain: List[ParticipantId],
                            conversationId: lightdb.id.Id[sigil.conversation.Conversation]): Task[Set[SpaceId]] =
    sigilArg.accessibleSpaces(chain, conversationId).map(_ + GlobalSpace)

  /** Compose the per-turn retrieval query. Caller-supplied
    * [[queryFrom]] takes precedence; otherwise read the topic state
    * from the conversation + the last non-agent message from frames. */
  private def buildQuery(sigil: Sigil,
                         conversationId: Id[Conversation],
                         frames: Vector[ContextFrame],
                         chain: List[ParticipantId]): Task[Option[String]] =
    queryFrom match {
      case Some(builder) => Task.pure(builder(frames, chain))
      case None          =>
        sigil.withDB(_.conversations.transaction(_.get(conversationId))).map {
          case None => StandardMemoryRetriever.lastNonAgentMessage(frames, chain)
          case Some(conv) =>
            val topic = conv.topics.lastOption
            val parts = scala.collection.mutable.ListBuffer.empty[String]
            topic.foreach { t =>
              if (t.label.nonEmpty)   parts += t.label
              if (t.summary.nonEmpty) parts += t.summary
            }
            if (conv.currentKeywords.nonEmpty) parts += conv.currentKeywords.mkString(" ")
            StandardMemoryRetriever.lastNonAgentMessage(frames, chain).foreach(parts += _)
            val joined = parts.iterator.map(_.trim).filter(_.nonEmpty).mkString(" ")
            if (joined.nonEmpty) Some(joined) else None
        }
    }

  /** Load every pinned memory in the supplied spaces. Pushes the
    * filter into Lucene via the indexed `pinned` boolean, the
    * `statusName` projection, and the `spaceIdValue` string
    * projection: `pinned == true AND status == Approved AND
    * spaceIdValue IN spaces`. The remainder of the recall gate
    * ([[ContextMemory.isRecallable]] — current version + expiry) is
    * filtered in-memory on the (small) result.
    * O(N_pinned_in_accessible_spaces) per turn instead of
    * O(N_total_memories). */
  private def loadPinned(sigil: Sigil,
                         spaces: Set[SpaceId],
                         currentMode: Option[Id[Mode]],
                         now: Timestamp): Task[Vector[Id[ContextMemory]]] =
    if (spaces.isEmpty) Task.pure(Vector.empty)
    else sigil.withDB(_.memories.transaction { tx =>
      val spaceClauses = spaces.toList.map { space =>
        FilterClause(ContextMemory.spaceIdValue === space.value, Condition.Should, None)
      }
      tx.query
        .filter(_ =>
          Filter.Multi(minShould = 1, filters = spaceClauses) &&
            (ContextMemory.pinned === true) &&
            (ContextMemory.statusName === MemoryStatus.Approved.toString)
        )
        .toList
    }).map { rows =>
      rows.iterator
        .filter(_.isRecallable(now))
        .filter(GateStage.matchesMode(_, currentMode))
        .map(_._id).toVector
    }
}

object StandardMemoryRetriever {
  /** Function that derives a retrieval query from the turn's frames +
    * participant chain. Returning `None` or an empty string skips
    * the retrieval call for this turn. */
  type QueryBuilder = (Vector[ContextFrame], List[ParticipantId]) => Option[String]

  /** The standard six-stage pipeline, assembled from the retriever's
    * knobs. Apps that want to swap one stage build on this — replace
    * the entry and pass the list as
    * `StandardMemoryRetriever(pipeline = Some(...))`. */
  def defaultStages(limit: Int = 5,
                    rrfK: Int = 60,
                    lexicalWeight: Double = 2.0,
                    vectorWeight: Double = 1.0,
                    recencyWeight: Double = FuseStage.DefaultRecencyWeight,
                    reinforcementWeight: Double = FuseStage.DefaultReinforcementWeight,
                    recencyHalfLifeMs: Long = FuseStage.DefaultRecencyHalfLifeMs,
                    reranker: Option[MemoryReranker] = None,
                    tokenBudget: Option[Int] = None,
                    recordAccess: Boolean = true): List[MemoryRetrievalStage] = List(
    RecallStage(),
    GateStage(),
    FuseStage(
      rrfK = rrfK,
      lexicalWeight = lexicalWeight,
      vectorWeight = vectorWeight,
      recencyWeight = recencyWeight,
      reinforcementWeight = reinforcementWeight,
      recencyHalfLifeMs = recencyHalfLifeMs
    ),
    RerankStage(reranker),
    BudgetStage(limit = limit, tokenBudget = tokenBudget),
    RecordStage(recordAccess)
  )

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
  val lastNonAgentMessage: QueryBuilder = (frames, chain) => {
    val agent = chain.lastOption
    frames.reverseIterator.collectFirst {
      case t: ContextFrame.Text if !agent.contains(t.participantId) => t.content
    }
  }
}
