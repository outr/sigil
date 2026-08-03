package sigil

import fabric.rw.*
import lightdb.id.Id
import lightdb.lucene.LuceneStore
import lightdb.postgresql.PostgreSQLStoreManager
import lightdb.rocksdb.RocksDBSharedStore
import lightdb.sql.connect.{HikariConnectionManager, SQLConfig}
import lightdb.store.CollectionManager
import lightdb.store.split.SplitStoreManager
import lightdb.time.Timestamp
import lightdb.util.Nowish
import profig.Profig
import rapid.{Stream, Task, logger}
import sigil.conversation.{ActiveSkillSlot, ContextFrame, ContextKey, ContextMemory, ContextSummary, Conversation, EncodedContext, FrameBuilder, MemorySource, MemoryStatus, ParticipantProjection, ProgressContext, SkillSource, ToolCallState, Topic, TopicEntry, TopicShiftResult, TurnInput, TurnPlan, UpsertMemoryResult}
import sigil.SpaceId
import sigil.cache.ModelRegistry
import sigil.controller.OpenRouter
import sigil.embedding.{EmbeddingProvider, NoOpEmbeddingProvider}
import sigil.governor.{BudgetDirective, BudgetGovernor, CheckpointIntervention, GovernorContext}
import sigil.governor.{DegenerateGenerationGovernor, GovernorVote, OutcomeGovernor, PlainTextReplyGovernor,
  ProgressGovernor, TurnDecisionGovernor, TurnGovernor}
import sigil.transport.SignalTransport

import java.nio.file.Path
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import sigil.tool.consult.{ConsultTool, TopicClassifierTool}
import sigil.provider.{GenerationSettings, TokenUsage}
import sigil.db.{DefaultSigilDB, Model, SigilDB}
import sigil.dispatcher.{StopFlag, TriggerFilter}
import sigil.event.{AgentState, CapabilityResults, Event, EventsPage, Message, MessageRole, MessageVisibility, ModeChange, Stop, ToolInvoke, TopicChange, TopicChangeKind}
import sigil.role.Role
import sigil.orchestrator.{BudgetScope, Directive, Orchestrator}
import sigil.provider.{Complexity, ConversationMode, ConversationRequest, Mode, ProviderStrategy, ReasoningMode, ToolPolicy, WorkType}
import sigil.information.Information
import sigil.participant.{AgentParticipant, AgentParticipantId, DefaultAgentParticipant, Participant, ParticipantId}
import sigil.pipeline.{ContentExternalizationTransform, GeocodingEnrichmentEffect, InboundTransform, LocationCaptureTransform, MemoryCacheInvalidationEffect, MessageIndexingEffect, RedactLocationTransform, RespondOptionsSelectionFramingTransform, SettledEffect, SignalHub, TopicIndexCanonicalizingTransform, ViewerTransform, WorkerConversationAddressingTransform}
import sigil.render.{ContentRenderer, HtmlRenderer, MarkdownRenderer, PlainTextRenderer, SlackMrkdwnRenderer}
import sigil.provider.Provider
import sigil.provider.{ContextSection, ContextSections, InstructionTier, ModelProfile, PromptShape, Reliability, ResolvedReferences}
import sigil.service.Service
import sigil.signal.{AgentActivity, AgentStateDelta, CoreSignals, Delta, EventState, LocationDelta, Notice, ServiceLogSignal, ServiceStatusSignal, Signal, ToolDelta, TopicDelta}
import sigil.spatial.{Geocoder, NoOpGeocoder, Place}
import sigil.tool.Tool
import sigil.tool.fs.{FileSystemContext, LocalFileSystemContext}
import sigil.tool.core.{CoreTools, FindCapabilityTool}
import sigil.tool.model.ResponseContent
import sigil.tool.{ToolFinder, ToolInput}
import sigil.vector.{NoOpVectorIndex, VectorIndex, VectorPoint, VectorPointId, VectorSearchResult}

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference


/**
 * Retrieval and review cluster — the read side of the durable
 * knowledge stores. Owns summary persistence / indexing / lookup, the
 * per-turn memory-retrieval cache and its invalidation, the memory
 * review surface (history, pending queue, approve / reject / forget),
 * the batched access-count recorder, and the semantic + Lucene search
 * paths over memories and the event log.
 *
 * Mixed into [[Sigil]]; the self-type reaches `withDB`,
 * `embeddingProvider` / `vectorIndex`, and the memory write paths in
 * [[MemoryOps]].
 */
trait RetrievalOps { this: Sigil =>

  /** Persist a new [[ContextSummary]] and return the stored record. The
    * caller (curator or app-specific summarizer) owns the generation
    * policy; this helper just writes.
    *
    * When vector search is wired ([[vectorWired]]), the summary's text
    * is embedded and upserted into [[vectorIndex]] with payload
    * `kind=summary` so `searchConversationEvents` can surface it. */
  def persistSummary(summary: ContextSummary): Task[ContextSummary] =
    withDB(_.summaries.transaction(_.upsert(summary))).flatMap { stored =>
      indexSummary(stored).map(_ => stored)
    }

  /** Per-conversation cache for non-critical memory retrieval results.
    * Inter-message-stable — populated lazily on first curate-time
    * read for a conversation, invalidated by
    * [[sigil.pipeline.MemoryCacheInvalidationEffect]] on (a) a
    * non-agent message settling and (b) a topic-change `Switch`
    * settling. See [[sigil.conversation.compression.MemoryRetrievalCache]]
    * for the caching contract.
    *
    * Apps don't typically interact with this directly — the
    * [[sigil.conversation.compression.StandardMemoryRetriever]] consults
    * it transparently via [[cachedMemoryRetrieve]]. Public for
    * observability (specs / app debug tools peek without mutating). */
  final val memoryRetrievalCache: sigil.conversation.compression.MemoryRetrievalCache =
    new sigil.conversation.compression.MemoryRetrievalCache

  /** Read or compute a [[sigil.conversation.compression.MemoryRetrievalResult]]
    * for `conversationId`. The compute thunk runs at most once per
    * (conversation, cache lifetime). */
  def cachedMemoryRetrieve(conversationId: Id[Conversation],
                           compute: => Task[sigil.conversation.compression.MemoryRetrievalResult]
                          ): Task[sigil.conversation.compression.MemoryRetrievalResult] =
    memoryRetrievalCache.getOrCompute(conversationId, compute)

  /** Invalidate the cached retrieval for a conversation — called by
    * [[sigil.pipeline.MemoryCacheInvalidationEffect]] on appropriate
    * settled events. Idempotent. */
  def invalidateMemoryRetrievalCache(conversationId: Id[Conversation]): Unit =
    memoryRetrievalCache.invalidate(conversationId)

  /** Invalidate every conversation's cached retrieval. Fired by the
    * memory write paths that can change what is recallable — reject /
    * approve, forget, a keyed upsert that archives a prior version,
    * an in-place mutation (pin / unpin / move), and consolidation
    * merges. A `ContextMemory` carries no conversation mapping the
    * cache could invert, so the write bumps a global epoch instead;
    * every conversation recomputes its retrieval on the next turn.
    * O(1) — a single atomic increment. */
  def invalidateAllMemoryRetrievals(): Unit =
    memoryRetrievalCache.invalidateAll()

  /** All versions of a keyed memory in `spaceId`, chronologically
    * (oldest first by `created`). */
  def memoryHistory(key: String, spaceId: SpaceId): Task[List[ContextMemory]] =
    if (key.isEmpty) Task.pure(Nil)
    else withDB(_.memories.transaction { tx =>
      import lightdb.filter.*
      tx.query
        .filter(m => (m.spaceIdValue === spaceId.value) && (m.key === Some(key)))
        .toList
        .map(_.sortBy(_.created.value))
    })

  /** Pending (awaiting approval) memories in the given spaces. */
  def listPendingMemories(spaces: Set[SpaceId]): Task[List[ContextMemory]] =
    if (spaces.isEmpty) Task.pure(Nil)
    else withDB(_.memories.transaction { tx =>
      import lightdb.filter.*
      tx.query
        .filter(m => (m.statusName === MemoryStatus.Pending.toString) && spaces.map(s => m.spaceIdValue === s.value).reduce(_ || _))
        .toList
    })

  /** Transition a memory from `Pending` → `Approved`. Returns the
    * updated record, or `None` if the id isn't found. No-op if the
    * memory is already approved. An approval re-indexes the record so
    * semantic search sees it again, and drops every cached retrieval
    * so the memory can surface on the next turn. */
  def approveMemory(id: Id[ContextMemory]): Task[Option[ContextMemory]] =
    withDB(_.memories.transaction { tx =>
      tx.get(id).flatMap {
        case None => Task.pure(None)
        case Some(m) if m.status == MemoryStatus.Approved => Task.pure(Some(m))
        case Some(m) =>
          val updated = m.copy(status = MemoryStatus.Approved, modified = Timestamp())
          tx.upsert(updated).map(_ => Some(updated))
      }
    }).flatMap {
      case Some(updated) => reindexMemory(updated).map { indexed =>
        invalidateAllMemoryRetrievals()
        Some(indexed)
      }
      case None => Task.pure(None)
    }

  /** Transition a memory to `Rejected` (kept on disk for lineage, but
    * hidden from retrievers). Removes the record's vector point so a
    * semantic search can't resurrect it, and drops every cached
    * retrieval so a mid-burst rejection takes effect on the next turn.
    * Use [[forgetMemory]] for hard delete. */
  def rejectMemory(id: Id[ContextMemory]): Task[Option[ContextMemory]] =
    withDB(_.memories.transaction { tx =>
      tx.get(id).flatMap {
        case None => Task.pure(None)
        case Some(m) =>
          val updated = m.copy(status = MemoryStatus.Rejected, modified = Timestamp())
          tx.upsert(updated).map(_ => Some(updated))
      }
    }).flatMap {
      case Some(updated) => evictMemoryPoint(updated._id).map { _ =>
        invalidateAllMemoryRetrievals()
        Some(updated)
      }
      case None => Task.pure(None)
    }

  /** Hard-delete every version of a keyed memory in `spaceId`. Returns
    * the number of records removed. Also removes corresponding points
    * from the vector index so semantic search doesn't return stale
    * hits, and drops every cached retrieval so the forgotten memory
    * stops rendering on the next turn. */
  def forgetMemory(key: String, spaceId: SpaceId): Task[Int] =
    if (key.isEmpty) Task.pure(0)
    else memoryHistory(key, spaceId).flatMap { versions =>
      withDB(_.memories.transaction { tx =>
        Task.sequence(versions.map(v => tx.delete(v._id))).map(_ => versions.size)
      }).flatMap { removed =>
        if (!vectorWired) Task.pure(removed)
        else Task
          .sequence(versions.map(v => vectorIndex.delete(VectorPointId(v._id.value))))
          .map(_ => removed)
          .handleError { e =>
            Task(scribe.warn(s"Vector delete failed during forgetMemory(key=$key): ${e.getMessage}"))
              .map(_ => removed)
          }
      }.map { removed =>
        if (removed > 0) invalidateAllMemoryRetrievals()
        removed
      }
    }

  /** Pending `accessCount` / `lastAccessedAt` bumps, accumulated in
    * memory and drained by [[flushMemoryAccesses]]. Retrieval marks
    * access on every fresh compute; writing that straight through
    * would cost a store commit (and, on the default Lucene backend, an
    * fsync) per turn purely for a ranking signal. */
  private val pendingMemoryAccesses: java.util.concurrent.ConcurrentHashMap[Id[ContextMemory], (Int, Long)] =
    new java.util.concurrent.ConcurrentHashMap[Id[ContextMemory], (Int, Long)]()

  /** Bump `accessCount` and `lastAccessedAt` on a memory. Called by
    * retrieval paths (`semantic_search`, MemoryRetriever) so apps can
    * implement LRU-based retention without Sigil needing its own
    * pruner. The bump accumulates in memory and lands on the next
    * [[flushMemoryAccesses]] — see [[recordMemoryAccesses]] for the
    * durability contract. */
  def recordMemoryAccess(id: Id[ContextMemory]): Task[Unit] =
    recordMemoryAccesses(List(id))

  /**
   * Batched [[recordMemoryAccess]] — accumulates the bumps in memory
   * rather than opening a write transaction per retrieval.
   *
   * `accessCount` / `lastAccessedAt` are a retrieval-ranking feedback
   * signal, not conversation state: a store commit per fresh
   * retrieval bought durability nobody needs at the cost of an fsync
   * on the hot path, and the read-modify-write of whole documents
   * raced every concurrent memory mutation (a consolidation archive
   * or a reject landing between the read and the write was silently
   * reverted by the stale snapshot).
   *
   * Accumulated bumps are drained by [[flushMemoryAccesses]] — on the
   * [[sigil.maintenance.MemoryAccessFlushTask]] cadence
   * ([[memoryAccessFlushInterval]], default 60s) and once more during
   * [[shutdown]]. The flush applies each delta with `tx.modify`, so
   * the mutation lands on the FRESH row and touches only the two
   * access fields. A process killed between flushes loses at most one
   * interval's worth of access counts; the memories themselves are
   * unaffected.
   */
  def recordMemoryAccesses(ids: Seq[Id[ContextMemory]]): Task[Unit] =
    if (ids.isEmpty) Task.unit
    else Task {
      val now = Timestamp().value
      ids.distinct.foreach { id =>
        pendingMemoryAccesses.merge(id, (1, now), (a, b) => (a._1 + b._1, math.max(a._2, b._2)))
      }
    }

  /** How often [[sigil.maintenance.MemoryAccessFlushTask]] drains the
    * accumulated `accessCount` / `lastAccessedAt` bumps to the store.
    * Shorter means fresher reinforcement signal at the cost of more
    * commits; longer means a crash loses more counts. Default 60s. */
  def memoryAccessFlushInterval: scala.concurrent.duration.FiniteDuration =
    scala.concurrent.duration.DurationInt(60).seconds

  /** Drain the accumulated access bumps into the store. Returns the
    * number of memories updated. Each delta is applied with
    * `tx.modify` against the current row and touches only
    * `accessCount` / `lastAccessedAt`, so a concurrent archive,
    * reject, or re-scope is never clobbered. Missing ids are dropped.
    * Failures are logged and swallowed — access marking is a feedback
    * signal, never worth failing anything over. */
  def flushMemoryAccesses: Task[Int] = Task.defer {
    val drained = {
      val builder = List.newBuilder[(Id[ContextMemory], (Int, Long))]
      pendingMemoryAccesses.keySet().forEach { id =>
        Option(pendingMemoryAccesses.remove(id)).foreach(delta => builder += (id -> delta))
      }
      builder.result()
    }
    if (drained.isEmpty) Task.pure(0)
    else withDB(_.memories.transaction { tx =>
      Task.sequence(drained.map { case (id, (count, at)) =>
        tx.modify(id) {
          case Some(m) => Task.pure(Some(m.copy(
            accessCount = m.accessCount + count,
            lastAccessedAt = Timestamp(math.max(m.lastAccessedAt.value, at))
          )))
          case None => Task.pure(None)
        }.map(_.fold(0)(_ => 1))
      }).map(_.sum)
    }).handleError { e =>
      Task {
        scribe.warn(s"flushMemoryAccesses failed for ${drained.size} memories: ${e.getMessage}")
        0
      }
    }
  }

  /** Load all summaries for a conversation, oldest-first. */
  def summariesFor(conversationId: Id[Conversation]): Task[List[ContextSummary]] =
    withDB(_.summaries.transaction { tx =>
      import lightdb.filter.*
      tx.query
        .filter(_.conversationId === conversationId)
        .toList
        .map(_.sortBy(_.created.value))
    })

  // -- vector-indexing internals --

  private final def indexSummary(s: ContextSummary): Task[Unit] =
    if (!vectorWired || s.text.isEmpty) Task.unit
    else embeddingProvider.embed(s.text).flatMap { vec =>
      vectorIndex.upsert(VectorPoint(
        id = VectorPointId(s._id.value),
        vector = vec,
        payload = Map(
          "kind" -> "summary",
          "conversationId" -> s.conversationId.value,
          "summaryId" -> s._id.value
        )
      ))
    }.handleError { e =>
      Task(scribe.warn(s"Vector index failed for summary ${s._id.value}: ${e.getMessage}"))
    }

  // -- search APIs --

  /**
   * Semantic search across persisted [[ContextMemory]] records,
   * restricted to the given spaces. When vector search is wired, embed
   * the query and hit the vector index with a `kind=memory` filter
   * plus a `spaceId` any-of clause — the space scope applies inside
   * the index's top-K cut, so a large multi-tenant store can't crowd
   * in-space matches out of the candidate pool — then hydrate ids via
   * [[SigilDB.memories]]. Only recallable records are returned (see
   * [[ContextMemory.isRecallable]]). When not wired, fall back to the
   * space-scoped listing (relevance-unordered — callers that care
   * should override this method).
   */
  def searchMemories(query: String,
                     spaces: Set[SpaceId],
                     limit: Int = 10): Task[List[ContextMemory]] =
    if (!vectorWired) findMemories(spaces).map(_.take(limit))
    else embeddingProvider.embed(query).flatMap { vec =>
      val filter = sigil.vector.VectorQueryFilter(
        exact = Map("kind" -> "memory"),
        anyOf = if (spaces.isEmpty) Map.empty else Map("spaceId" -> spaces.map(_.value))
      )
      vectorIndex.search(vec, limit = limit, filter = filter).flatMap { hits =>
        val ids = hits.flatMap(_.payload.get("memoryId")).map(Id[ContextMemory](_))
        withDB { db =>
          db.memories.transaction { tx =>
            Task.sequence(ids.map(id => tx.get(id))).map { loaded =>
              val now = Timestamp()
              loaded.flatten
                .filter(m => spaces.isEmpty || spaces.contains(m.spaceId))
                .filter(_.isRecallable(now))
            }
          }
        }
      }
    }

  /**
   * Semantic (or Lucene-fallback) search across persisted events in a
   * conversation. Used by the `search_conversation` tool and by app
   * UIs. `topicId` restricts to a single topic when supplied.
   */
  def searchConversationEvents(conversationId: Id[Conversation],
                               query: String,
                               topicId: Option[Id[Topic]] = None,
                               limit: Int = 10): Task[List[Event]] =
    if (!vectorWired) searchEventsLucene(conversationId, query, topicId, limit)
    else embeddingProvider.embed(query).flatMap { vec =>
      val baseFilter = Map("kind" -> "message", "conversationId" -> conversationId.value)
      val filter = topicId.map(t => baseFilter + ("topicId" -> t.value)).getOrElse(baseFilter)
      vectorIndex.search(vec, limit = limit, filter = filter).flatMap { hits =>
        val ids = hits.flatMap(_.payload.get("eventId")).map(Id[Event](_))
        withDB { db =>
          db.eventsTransaction(conversationId) { tx =>
            Task.sequence(ids.map(id => tx.get(id))).map(_.flatten)
          }
        }
      }
    }

  /** Fallback substring search over conversation events when vector
    * search isn't wired. In-memory scan — fine for the default fallback
    * path; apps that need relevance ranking or large corpora should
    * wire a vector index. */
  private final def searchEventsLucene(conversationId: Id[Conversation],
                                       query: String,
                                       topicId: Option[Id[Topic]],
                                       limit: Int): Task[List[Event]] =
    withDB(_.eventsTransaction(conversationId)(_.list)).map { all =>
      val needle = query.toLowerCase
      all.filter { e =>
        e.conversationId == conversationId &&
          topicId.forall(e.topicId == _) &&
          eventSearchText(e).toLowerCase.contains(needle)
      }.take(limit)
    }

  /** Best-effort text representation of an event for Lucene-fallback
    * substring search. Apps that add custom event subtypes override
    * this hook to contribute their own searchable text. */
  protected def eventSearchText(event: Event): String = event match {
    case m: Message => m.content.collect { case ResponseContent.Text(t) => t }.mkString("\n")
    case tc: TopicChange => s"${tc.newLabel}"
    case other => other.toString
  }

  /** Adapt a single [[Event]] into the [[sigil.tool.model.SearchConversationHit]]
    * shape both the agent tool and the [[sigil.signal.ConversationSearchSnapshot]]
    * notice emit. Apps with custom event subtypes can override to contribute
    * richer snippets — but matching the existing format keeps UI and tool
    * results renderable from the same view code. */
  protected def searchHit(e: Event): sigil.tool.model.SearchConversationHit = {
    val snippet = e match {
      case m: Message =>
        m.content.collect { case ResponseContent.Text(t) => t }.mkString(" ").take(280)
      case tc: TopicChange => s"[topic change] ${tc.newLabel}"
      case other           => other.getClass.getSimpleName
    }
    sigil.tool.model.SearchConversationHit(
      eventId       = e._id.value,
      timestamp     = e.timestamp.value,
      participantId = e.participantId.value,
      topicId       = e.topicId.value,
      eventType     = e.getClass.getSimpleName,
      snippet       = snippet
    )
  }
}
