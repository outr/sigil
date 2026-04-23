package sigil

import fabric.rw.RW
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
import sigil.conversation.{ActiveSkillSlot, ContextKey, ContextMemory, ContextSummary, Conversation, ConversationView, FrameBuilder, MemorySpaceId, ParticipantProjection, SkillSource, Topic, TopicEntry, TopicShiftResult, TurnInput}
import sigil.embedding.{EmbeddingProvider, NoOpEmbeddingProvider}
import sigil.tool.consult.{ConsultTool, TopicClassifierTool}
import sigil.provider.GenerationSettings
import sigil.db.{Model, SigilDB}
import sigil.dispatcher.{StopFlag, TriggerFilter}
import sigil.event.{AgentState, Event, Message, ModeChange, Stop, TopicChange, TopicChangeKind}
import sigil.provider.Mode
import sigil.information.Information
import sigil.participant.{AgentParticipant, AgentParticipantId, DefaultAgentParticipant, Participant, ParticipantId}
import sigil.provider.Provider
import sigil.signal.{AgentActivity, AgentStateDelta, CoreSignals, EventState, Signal}
import sigil.tool.core.CoreTools
import sigil.tool.model.ResponseContent
import sigil.tool.{ToolFinder, ToolInput}
import sigil.vector.{NoOpVectorIndex, VectorIndex, VectorPoint, VectorSearchResult}

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

trait Sigil {

  /**
   * App-specific Signal subtypes (custom Events / Deltas the app introduces
   * beyond what sigil ships). The framework's [[CoreSignals]] are registered
   * automatically; this list extends the polymorphic discriminator with
   * additional types.
   */
  protected def signals: List[RW[? <: Signal]]

  /**
   * App-specific ParticipantId subtypes. Apps register their own
   * `ParticipantId` implementations here for polymorphic serialization.
   */
  protected def participantIds: List[RW[? <: ParticipantId]]

  /**
   * When `true`, tools that would normally cause external side effects (send
   * a message, write to a shared resource, charge a card) should return a
   * representative test response instead. The `TurnContext` passed to
   * `Tool.execute` forwards this flag through `context.sigil.testMode` so
   * tools can check it directly.
   *
   * Default `false` — production. Tests override to `true`.
   */
  def testMode: Boolean = false

  // -- tool discovery --

  /**
   * Resolves tools matching capability-discovery queries. Called by
   * `find_capability` and slash-command dispatch. Implementations back onto
   * whatever catalog the app maintains (in-memory, DB, remote registry).
   *
   * The finder also supplies the `ToolInput` RWs for its tools; Sigil
   * registers them into the polymorphic discriminator at init.
   */
  def findTools: ToolFinder

  // -- context curation --

  /**
   * Per-turn curator: given the current [[ConversationView]] plus the
   * target model and participant chain, produce the [[TurnInput]] the
   * provider will render. Policy lives here — pick which
   * memories/summaries/information to surface, apply app-specific
   * overlays, add extra context, run budget-based compression, etc.
   *
   * `modelId` and `chain` are forwarded so implementations that use
   * [[sigil.conversation.compression.StandardContextCurator]] (or
   * anything else LLM-driven) can invoke
   * [[sigil.tool.consult.ConsultTool.invoke]] with the same provider
   * credentials and chain the turn itself runs under.
   *
   * Apps that want no curation return `Task.pure(TurnInput(view))`
   * explicitly.
   */
  def curate(view: ConversationView,
             modelId: Id[Model],
             chain: List[ParticipantId]): Task[TurnInput]

  // -- information lookup --

  /**
   * Resolve the full content of an [[Information]] catalog entry. Apps
   * that don't use the Information catalog return `Task.pure(None)`
   * explicitly.
   */
  def getInformation(id: Id[Information]): Task[Option[Information]]

  /**
   * Persist an [[Information]] record so it can be resolved later via
   * [[getInformation]]. Apps that enable block-extraction on compression
   * (see [[sigil.conversation.compression.StandardBlockExtractor]]) MUST
   * implement this — references emitted by the extractor resolve through
   * [[getInformation]] reading whatever this writes.
   *
   * Apps that don't use the Information catalog return `Task.unit`
   * explicitly.
   */
  def putInformation(information: Information): Task[Unit]

  // -- memory --

  /**
   * App-specific [[MemorySpaceId]] subtypes registered into the polymorphic
   * discriminator so [[ContextMemory.spaceId]] values round-trip through
   * fabric RW. Apps define concrete spaces (GlobalSpace, ProjectSpace,
   * UserSpace, etc.) and list their RWs here.
   */
  protected def memorySpaceIds: List[RW[? <: MemorySpaceId]]

  /**
   * Search memories across the given spaces. Default queries
   * [[SigilDB.memories]] by indexed `spaceId`. Apps override for relevance
   * ranking, recency weighting, embedding search, caching, etc.
   *
   * Typically called from `curate` when assembling a turn's
   * `TurnInput.memories`: the curator picks which returned
   * records to include (by id) based on its policy.
   */
  // -- skills --

  /**
   * Resolve the Mode-source [[ActiveSkillSlot]] for a given [[Mode]]. Called
   * by the framework when a [[ModeChange]] event reaches `Complete` — the
   * returned slot (if any) is written into the changing participant's
   * [[ParticipantProjection.activeSkills]] keyed by `SkillSource.Mode`;
   * `None` clears any stale Mode-source slot.
   *
   * Apps that don't use mode-scoped skills return
   * `Task.pure(None)` explicitly.
   */
  def modeSkill(mode: Mode): Task[Option[ActiveSkillSlot]]

  /**
   * The [[MemorySpaceId]] into which a
   * [[sigil.conversation.compression.MemoryContextCompressor]] should
   * write facts extracted during compression of this conversation.
   *
   * Apps that don't want memory extraction return
   * `Task.pure(None)` — the compressor collapses to summary-only.
   * Apps that do want it return a concrete space (per-conversation,
   * per-user, or a global compression-facts space).
   */
  def compressionMemorySpace(conversationId: Id[Conversation]): Task[Option[MemorySpaceId]]

  def findMemories(spaces: Set[MemorySpaceId]): Task[List[ContextMemory]] =
    if (spaces.isEmpty) Task.pure(Nil)
    else withDB(_.memories.transaction { tx =>
      import lightdb.filter.*
      tx.query
        .filter(m => spaces.map(s => m.spaceId === s).reduce(_ || _))
        .toList
    })

  // -- embeddings & vector search --

  /**
   * The [[EmbeddingProvider]] used to vectorize persisted text for
   * semantic retrieval. Apps that don't use embeddings return
   * [[NoOpEmbeddingProvider]] — Sigil skips auto-indexing and semantic
   * search falls back to the Lucene path. Apps that do use
   * embeddings wire a concrete provider (e.g.
   * [[sigil.embedding.OpenAICompatibleEmbeddingProvider]]) and must
   * pair it with a non-NoOp [[vectorIndex]].
   */
  def embeddingProvider: EmbeddingProvider

  /**
   * Backing vector store for semantic search. Apps that don't use
   * vector search return [[NoOpVectorIndex]] (upserts dropped, searches
   * empty). Apps that do typically wire
   * [[sigil.vector.QdrantVectorIndex]] in production or
   * [[sigil.vector.InMemoryVectorIndex]] in tests.
   */
  def vectorIndex: VectorIndex

  /** `true` when both [[embeddingProvider]] and [[vectorIndex]] are
    * non-NoOp — the flag the framework checks before auto-embedding on
    * persist or attempting vector-backed search. */
  protected final def vectorWired: Boolean =
    embeddingProvider.dimensions > 0 && (vectorIndex ne NoOpVectorIndex)

  // -- broadcasting --

  /**
   * The wire transport for [[Signal]]s. The framework calls
   * `broadcaster.handle(signal)` after persisting and before fanning out.
   * Apps push to WebSocket / SSE / DurableSocket, or return
   * [[SignalBroadcaster.NoOp]] explicitly when no wire transport is
   * wanted.
   */
  def broadcaster: SignalBroadcaster

  /**
   * An [[spice.http.client.intercept.Interceptor]] chained into every
   * provider's HTTP client — captures request / response pairs for
   * diagnostics. The built-in
   * [[sigil.provider.debug.JsonLinesInterceptor]] writes JSON lines
   * to a file so the full back-and-forth can be walked post-hoc.
   * Apps that don't want wire logging return
   * [[spice.http.client.intercept.Interceptor.empty]] explicitly.
   */
  def wireInterceptor: spice.http.client.intercept.Interceptor

  // -- participants (registration for polymorphic RW) --

  /**
   * App-specific [[Participant]] subtypes registered into the polymorphic
   * discriminator so [[sigil.conversation.Conversation.participants]] can
   * round-trip them through fabric RW. Framework subtypes
   * ([[DefaultAgentParticipant]]) are registered automatically; this list
   * extends the poly with app-specific agent types (Planner, Critic, etc.).
   */
  protected def participants: List[RW[? <: Participant]]

  // -- provider resolution --

  /**
   * Resolve a live [[Provider]] for the given model, scoped to the
   * participant chain. The chain lets the app pick credentials
   * (API keys, OAuth tokens, billing accounts) tied to the originating
   * participant — typically `chain.head` is the user whose key pays for
   * the call.
   *
   * Called by [[AgentParticipant.defaultProcess]] at every turn; no
   * caching in the framework. Apps cache if they care.
   */
  def providerFor(modelId: Id[Model], chain: List[ParticipantId]): Task[Provider]

  // -- framework dispatch (entry point) --

  /**
   * Inject a [[Signal]] into the framework. The single pipeline every
   * signal passes through, on the way in from outside or back out from
   * an agent's own turn. In order:
   *
   *   1. Persist via `SigilDB.apply` (insert Event / apply Delta).
   *   2. Update materialized projections on [[Conversation]]
   *      (`currentMode`, `currentTopicId`) for Mode/Topic changes.
   *   3. Append a frame to the conversation's [[ConversationView]] when
   *      an event settles Complete (via `FrameBuilder`).
   *   4. Resolve and apply the Mode-source skill slot on `ModeChange`.
   *   5. Dispatch control signals — a [[Stop]] event updates the
   *      matching agent's [[sigil.dispatcher.StopFlag]] so the agent's
   *      next iteration check (or in-flight `takeWhile`) exits.
   *   6. Broadcast to the wire via [[SignalBroadcaster]].
   *   7. Fan out to participants whose [[TriggerFilter]] matches.
   *
   * Apps don't override this — it's the framework's pipeline.
   */
  final def publish(signal: Signal): Task[Unit] =
    for {
      _ <- withDB(_.apply(signal))
      _ <- updateConversationProjection(signal)
      _ <- updateView(signal)
      _ <- maybeApplyModeSkill(signal)
      _ <- maybeIndexSettledMessage(signal)
      _ <- applyStop(signal)
      _ <- broadcaster.handle(signal).handleError(logBroadcastError(signal, _))
      _ <- signal match {
             case e: Event => fanOut(e)
             case _: sigil.signal.Delta => Task.unit
           }
    } yield ()

  /** When a [[Message]] settles `Complete`, vectorize and upsert its
    * text into [[vectorIndex]] so the search tool can retrieve it.
    * No-op when vector search isn't wired. */
  private final def maybeIndexSettledMessage(signal: Signal): Task[Unit] = {
    if (!vectorWired) Task.unit
    else signal match {
      case m: Message if m.state == EventState.Complete => indexMessageEvent(m)
      case d: sigil.signal.Delta =>
        withDB(_.events.transaction(_.get(d.target.asInstanceOf[Id[Event]]))).flatMap {
          case Some(m: Message) if m.state == EventState.Complete => indexMessageEvent(m)
          case _ => Task.unit
        }
      case _ => Task.unit
    }
  }

  // -- stop-flag registry --

  /** Active per-claim [[StopFlag]]s, keyed by the `AgentState._id` that
    * owns the claim. Populated when `tryFire` wins a claim and removed
    * when `releaseClaim` completes (successfully or via error). */
  private final val stopFlags: ConcurrentHashMap[Id[Event], StopFlag] = new ConcurrentHashMap()

  /** On a [[Stop]] event, set the matching flag(s): one specific agent if
    * `targetParticipantId` is set, else every agent in the conversation.
    * Also logs the stop (with `reason`, if supplied) so operators can see
    * where stops originate — otherwise `Stop.reason` would be metadata
    * that only shows up if someone trawls the event log. */
  private final def applyStop(signal: Signal): Task[Unit] = signal match {
    case s: Stop => Task {
      val target = s.targetParticipantId.map(_.value).getOrElse("*")
      val why = s.reason.map(r => s" reason=\"$r\"").getOrElse("")
      scribe.info(
        s"Stop received: conversation=${s.conversationId.value} target=$target " +
          s"force=${s.force} by=${s.participantId.value}$why"
      )
      import scala.jdk.CollectionConverters.*
      stopFlags.entrySet().iterator().asScala.foreach { entry =>
        val lockId = entry.getKey
        val flag = entry.getValue
        // Lock id encodes `agentlock:<agentId>:<convId>`; cheapest match is
        // on the id suffix for conversation + participant.
        val matchesConv = lockId.value.endsWith(s":${s.conversationId.value}")
        val matchesTarget = s.targetParticipantId match {
          case None     => true
          case Some(id) => lockId.value == s"agentlock:${id.value}:${s.conversationId.value}"
        }
        if (matchesConv && matchesTarget) {
          if (s.force) flag.force.set(true) else flag.graceful.set(true)
        }
      }
    }
    case _ => Task.unit
  }

  /** If this signal settles a [[ModeChange]] to `Complete`, resolve the
    * Mode-source [[ActiveSkillSlot]] (via [[modeSkill]]) and write it into
    * the acting participant's projection on the view. */
  private final def maybeApplyModeSkill(signal: Signal): Task[Unit] = signal match {
    case mc: ModeChange if mc.state == EventState.Complete => applyModeSkill(mc)
    case d: sigil.signal.Delta =>
      withDB(_.events.transaction(_.get(d.target.asInstanceOf[Id[Event]]))).flatMap {
        case Some(mc: ModeChange) if mc.state == EventState.Complete => applyModeSkill(mc)
        case _ => Task.unit
      }
    case _ => Task.unit
  }

  private final def applyModeSkill(mc: ModeChange): Task[Unit] =
    modeSkill(mc.mode).flatMap {
      case Some(slot) =>
        updateProjection(mc.conversationId, mc.participantId)(
          proj => proj.copy(activeSkills = proj.activeSkills + (SkillSource.Mode -> slot))
        )
      case None =>
        // No app-provided skill for this mode — clear any stale Mode-source slot.
        updateProjection(mc.conversationId, mc.participantId)(
          proj => proj.copy(activeSkills = proj.activeSkills - SkillSource.Mode)
        )
    }

  /**
   * Maintain the per-conversation [[ConversationView]] as events/deltas
   * flow through `publish`. A frame lands in the view exactly once per
   * source event, the moment the event reaches `EventState.Complete`:
   *
   *   - Atomic Complete events (e.g. a user-typed Message, a
   *     `find_capability` ToolResults) — the Event branch appends
   *     directly.
   *   - Events that start Active and settle later via a Delta (streaming
   *     Message, in-flight ToolInvoke) — the Delta branch re-reads the
   *     target post-apply. If it's now Complete, append.
   *
   * Idempotency: `appendToViewIfNew` guards against double-appends by
   * checking whether a frame with the same `sourceEventId` already exists.
   * Deltas that don't complete their target, or events that are still
   * Active, fall through as no-ops.
   */
  private final def updateView(signal: Signal): Task[Unit] = signal match {
    case e: Event if e.state == EventState.Complete =>
      appendToViewIfNew(e)
    case d: sigil.signal.Delta =>
      withDB(_.events.transaction(_.get(d.target.asInstanceOf[Id[Event]]))).flatMap {
        case Some(target) if target.state == EventState.Complete => appendToViewIfNew(target)
        case _ => Task.unit
      }
    case _ => Task.unit
  }

  /** Append `event`'s frame(s) and participant-projection updates to the
    * conversation's view, creating the view if it doesn't yet exist.
    * Idempotent — if a frame for `event._id` already exists in the view
    * the modify returns unchanged. */
  private final def appendToViewIfNew(event: Event): Task[Unit] =
    withDB(_.views.transaction(_.modify(ConversationView.idFor(event.conversationId)) {
      case Some(view) if view.frames.exists(_.sourceEventId == event._id) =>
        Task.pure(Some(view))
      case Some(view) =>
        val nextFrames = FrameBuilder.appendFor(view.frames, event)
        val nextProjections = FrameBuilder.updateProjections(view.participantProjections, event)
        Task.pure(Some(view.copy(
          frames = nextFrames,
          participantProjections = nextProjections,
          modified = Timestamp(Nowish())
        )))
      case None =>
        val seeded = ConversationView(
          conversationId = event.conversationId,
          frames = FrameBuilder.appendFor(Vector.empty, event),
          participantProjections = FrameBuilder.updateProjections(Map.empty, event),
          _id = ConversationView.idFor(event.conversationId)
        )
        Task.pure(Some(seeded))
    })).unit

  // -- view / summary helpers --

  /** Fetch the [[ConversationView]] for a conversation, returning an empty
    * seed (no frames, no projections) if one hasn't been materialized yet.
    * Empty views are NOT persisted — the view only lands on disk once a
    * Complete event exists to anchor it. */
  def viewFor(conversationId: Id[Conversation]): Task[ConversationView] =
    withDB(_.views.transaction(_.get(ConversationView.idFor(conversationId)))).map {
      case Some(view) => view
      case None => ConversationView(
        conversationId = conversationId,
        _id = ConversationView.idFor(conversationId)
      )
    }

  /** Drop the existing view (if any) and rebuild it by folding every
    * Complete event for the conversation through [[FrameBuilder]]. Useful
    * for recovery, schema migrations, and tests.
    *
    * Returns the newly-materialized view. */
  def rebuildView(conversationId: Id[Conversation]): Task[ConversationView] =
    withDB(_.events.transaction(_.list)).flatMap { all =>
      val events = all
        .filter(_.conversationId == conversationId)
        .sortBy(_.timestamp.value)
        .toVector
      val complete = events.filter(_.state == EventState.Complete)
      val frames = FrameBuilder.build(complete)
      val projections = complete
        .foldLeft(Map.empty[ParticipantId, ParticipantProjection])(FrameBuilder.updateProjections)
      val rebuilt = ConversationView(
        conversationId = conversationId,
        frames = frames,
        participantProjections = projections,
        _id = ConversationView.idFor(conversationId)
      )
      for {
        _ <- withDB(_.views.transaction(_.upsert(rebuilt)))
        // Re-apply Mode-source skill for the most recent Complete ModeChange.
        withSkill <- {
          val latestMode = complete.reverseIterator.collectFirst { case mc: ModeChange => mc }
          latestMode.fold(Task.pure(rebuilt)) { mc =>
            applyModeSkill(mc).flatMap(_ => viewFor(conversationId))
          }
        }
      } yield withSkill
    }

  /** Update a participant's [[ParticipantProjection]] on the conversation's
    * view. If the view doesn't exist yet, an empty one is seeded so the
    * projection has a durable home. Use this from curators, tools, or any
    * app code that needs to mutate per-participant projection state. */
  def updateProjection(conversationId: Id[Conversation], participantId: ParticipantId)
                      (f: ParticipantProjection => ParticipantProjection): Task[Unit] =
    withDB(_.views.transaction(_.modify(ConversationView.idFor(conversationId)) {
      case Some(view) =>
        Task.pure(Some(view
          .updateParticipant(participantId)(f)
          .copy(modified = Timestamp(Nowish()))))
      case None =>
        val seeded = ConversationView(
          conversationId = conversationId,
          participantProjections = Map(participantId -> f(ParticipantProjection())),
          _id = ConversationView.idFor(conversationId)
        )
        Task.pure(Some(seeded))
    })).unit

  /** Convenience: set (or replace) a skill slot for a participant. Discovery
    * and User sources are driven through here by tools that want to activate
    * a skill; Mode-source slots are maintained by the framework via
    * [[modeSkill]] on `ModeChange`. */
  def activateSkill(conversationId: Id[Conversation],
                    participantId: ParticipantId,
                    source: SkillSource,
                    slot: ActiveSkillSlot): Task[Unit] =
    updateProjection(conversationId, participantId)(
      proj => proj.copy(activeSkills = proj.activeSkills + (source -> slot))
    )

  /** Convenience: clear a skill slot for a participant (if present). */
  def clearSkill(conversationId: Id[Conversation],
                 participantId: ParticipantId,
                 source: SkillSource): Task[Unit] =
    updateProjection(conversationId, participantId)(
      proj => proj.copy(activeSkills = proj.activeSkills - source)
    )

  /** Convenience: set a single key/value on a participant's
    * `extraContext`. Same key replaces. */
  def setParticipantContext(conversationId: Id[Conversation],
                            participantId: ParticipantId,
                            key: ContextKey,
                            value: String): Task[Unit] =
    updateProjection(conversationId, participantId)(
      proj => proj.copy(extraContext = proj.extraContext + (key -> value))
    )

  /** Convenience: remove a key from a participant's `extraContext`. */
  def clearParticipantContext(conversationId: Id[Conversation],
                              participantId: ParticipantId,
                              key: ContextKey): Task[Unit] =
    updateProjection(conversationId, participantId)(
      proj => proj.copy(extraContext = proj.extraContext - key)
    )

  /** Convenience: publish a [[Stop]] event for the conversation. Lets
    * UI layers (stop button) and programmatic callers issue stops
    * without reconstructing the event by hand. For LLM-initiated stops
    * use [[sigil.tool.core.StopTool]] instead. */
  def stop(conversationId: Id[Conversation],
           requestedBy: ParticipantId,
           targetParticipantId: Option[ParticipantId] = None,
           force: Boolean = false,
           reason: Option[String] = None): Task[Unit] =
    withDB(_.conversations.transaction(_.get(conversationId))).flatMap {
      case None => Task.unit
      case Some(conv) =>
        publish(Stop(
          participantId = requestedBy,
          conversationId = conversationId,
          topicId = conv.currentTopicId,
          targetParticipantId = targetParticipantId,
          force = force,
          reason = reason
        ))
    }

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

  /** Persist a new [[ContextMemory]] and return the stored record.
    * When vector search is wired, auto-embeds `memory.fact` and
    * upserts into [[vectorIndex]] with payload
    * `kind=memory, spaceId=…`. */
  def persistMemory(memory: ContextMemory): Task[ContextMemory] =
    withDB(_.memories.transaction(_.upsert(memory))).flatMap { stored =>
      indexMemory(stored).map(_ => stored)
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

  /** Derive a deterministic UUID for a lightdb id. Qdrant requires
    * point ids to be UUIDs or unsigned ints; lightdb ids are
    * arbitrary strings. Using a name-based UUID (v3/v5-style via
    * `UUID.nameUUIDFromBytes`) gives us a stable point id that
    * upsert can replace deterministically. */
  private def vectorPointId(lightdbId: String): String =
    java.util.UUID.nameUUIDFromBytes(lightdbId.getBytes("UTF-8")).toString

  private final def indexSummary(s: ContextSummary): Task[Unit] =
    if (!vectorWired || s.text.isEmpty) Task.unit
    else embeddingProvider.embed(s.text).flatMap { vec =>
      vectorIndex.upsert(VectorPoint(
        id = vectorPointId(s._id.value),
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

  private final def indexMemory(m: ContextMemory): Task[Unit] =
    if (!vectorWired || m.fact.isEmpty) Task.unit
    else embeddingProvider.embed(m.fact).flatMap { vec =>
      vectorIndex.upsert(VectorPoint(
        id = vectorPointId(m._id.value),
        vector = vec,
        payload = Map(
          "kind" -> "memory",
          "memoryId" -> m._id.value,
          "spaceId" -> m.spaceId.value
        )
      ))
    }.handleError { e =>
      Task(scribe.warn(s"Vector index failed for memory ${m._id.value}: ${e.getMessage}"))
    }

  /** Index a settled [[Message]]'s text content so `searchConversationEvents`
    * can surface it by semantic similarity. Skipped when vector search
    * isn't wired or the message carries no text. */
  private final def indexMessageEvent(m: Message): Task[Unit] = {
    val text = m.content.collect { case ResponseContent.Text(t) => t }.mkString("\n").trim
    if (!vectorWired || text.isEmpty) Task.unit
    else embeddingProvider.embed(text).flatMap { vec =>
      vectorIndex.upsert(VectorPoint(
        id = vectorPointId(m._id.value),
        vector = vec,
        payload = Map(
          "kind" -> "message",
          "conversationId" -> m.conversationId.value,
          "topicId" -> m.topicId.value,
          "eventId" -> m._id.value,
          "participantId" -> m.participantId.value
        )
      ))
    }.handleError { e =>
      Task(scribe.warn(s"Vector index failed for message ${m._id.value}: ${e.getMessage}"))
    }
  }

  // -- search APIs --

  /**
   * Semantic search across persisted [[ContextMemory]] records,
   * restricted to the given spaces. When vector search is wired, embed
   * the query, hit the vector index with a `kind=memory` filter, then
   * hydrate ids via [[SigilDB.memories]]. When not wired, fall back to
   * the existing space-scoped listing (relevance-unordered — callers
   * that care should override this method).
   */
  def searchMemories(query: String,
                     spaces: Set[MemorySpaceId],
                     limit: Int = 10): Task[List[ContextMemory]] =
    if (!vectorWired) findMemories(spaces).map(_.take(limit))
    else embeddingProvider.embed(query).flatMap { vec =>
      vectorIndex.search(vec, limit = limit, filter = Map("kind" -> "memory")).flatMap { hits =>
        val ids = hits.flatMap(_.payload.get("memoryId")).map(Id[ContextMemory](_))
        withDB { db =>
          db.memories.transaction { tx =>
            Task.sequence(ids.map(id => tx.get(id))).map { loaded =>
              val filtered = loaded.flatten.filter(m => spaces.isEmpty || spaces.contains(m.spaceId))
              filtered
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
          db.events.transaction { tx =>
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
    withDB(_.events.transaction(_.list)).map { all =>
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

  /** Maintain materialized projections on the [[Conversation]] record:
    *   - `currentMode` tracks the latest [[ModeChange]]
    *   - `topics` (the navigation stack) tracks the latest [[TopicChange]]:
    *     - `Switch` either pushes a new entry (if the topic isn't on the
    *       stack) or truncates the stack back to that entry (if it is —
    *       the natural "return to prior topic" flow)
    *     - `Rename` mutates the active entry's label + summary in place
    *
    * Fires only on the SETTLE (an Event already at `Complete`, or a
    * `Delta` that transitions its target to `Complete`), never on the
    * initial Active pulse — so these projection fields are written
    * exactly once per transition even though each change flows through
    * `publish` twice (event + state delta). */
  private final def updateConversationProjection(signal: Signal): Task[Unit] = {
    val settled: Task[Option[Event]] = signal match {
      case e: Event if e.state == EventState.Complete => Task.pure(Some(e))
      case d: sigil.signal.Delta =>
        withDB(_.events.transaction(_.get(d.target.asInstanceOf[Id[Event]])))
          .map(_.filter(_.state == EventState.Complete))
      case _ => Task.pure(None)
    }
    settled.flatMap {
      case Some(mc: ModeChange) =>
        withDB(_.conversations.transaction(_.modify(mc.conversationId) {
          case Some(conv) if conv.currentMode != mc.mode =>
            Task.pure(Some(conv.copy(currentMode = mc.mode, modified = Timestamp(Nowish()))))
          case Some(conv) => Task.pure(Some(conv))
          case None       => Task.pure(None)
        })).unit
      case Some(tc: TopicChange) =>
        applyTopicChangeToStack(tc)
      case _ => Task.unit
    }
  }

  /** Update Conversation.topics in response to a settled TopicChange.
    *
    * For Switch: the change carries the post-transition topicId; we
    * either push a fresh entry or truncate the stack back to a matching
    * entry already present.
    *
    * For Rename: walk the stack and update label + summary on every
    * entry whose id matches the renamed topic (typically just the active
    * one). The Topic record itself is updated separately by the
    * orchestrator before publishing.
    */
  private final def applyTopicChangeToStack(tc: TopicChange): Task[Unit] =
    withDB(_.conversations.transaction(_.modify(tc.conversationId) {
      case None => Task.pure(None)
      case Some(conv) =>
        tc.kind match {
          case TopicChangeKind.Switch(_) =>
            val existingIdx = conv.topics.indexWhere(_.id == tc.topicId)
            val nextStack: List[TopicEntry] =
              if (existingIdx >= 0) {
                // Truncate back to that entry — return to prior topic.
                conv.topics.take(existingIdx + 1)
              } else {
                // New topic — load the Topic record and push as a new entry.
                // Fall back to a stub if the record can't be resolved.
                conv.topics :+ TopicEntry(
                  id = tc.topicId,
                  label = tc.newLabel,
                  summary = ""  // populated below from the Topic record
                )
              }
            // If we appended a stub, fetch the Topic record to fill summary.
            val withSummary: Task[List[TopicEntry]] =
              if (existingIdx >= 0) Task.pure(nextStack)
              else withDB(_.topics.transaction(_.get(tc.topicId))).map {
                case Some(t) => nextStack.init :+ TopicEntry(t._id, t.label, t.summary)
                case None    => nextStack
              }
            withSummary.map { stack =>
              if (stack == conv.topics) Some(conv)
              else Some(conv.copy(topics = stack, modified = Timestamp(Nowish())))
            }
          case TopicChangeKind.Rename(_) =>
            // Refresh the entry whose id matches by reading the (already-renamed)
            // Topic record. Walk the stack and replace any matches.
            withDB(_.topics.transaction(_.get(tc.topicId))).map {
              case None => Some(conv)
              case Some(t) =>
                val updatedStack = conv.topics.map { e =>
                  if (e.id == tc.topicId) TopicEntry(t._id, t.label, t.summary) else e
                }
                if (updatedStack == conv.topics) Some(conv)
                else Some(conv.copy(topics = updatedStack, modified = Timestamp(Nowish())))
            }
        }
    })).unit

  // -- topic classification --

  /**
   * The framework's two-step topic resolver. Given the conversation's
   * current topic, its prior topics, and a proposed (label, summary)
   * from a respond call, ask the model to classify the relationship via
   * a focused [[TopicClassifierTool]] call (no conversation history,
   * just the inputs).
   *
   * Returns:
   *   - `NoChange` — same subject as Current; nothing to relabel.
   *   - `Refine`   — same subject as Current; adopt the sharper label.
   *   - `Return(prior)` — same subject as one of the priors; truncate
   *     the stack back to that entry.
   *   - `New`      — a brand new subject; push a fresh entry.
   *
   * If the classifier call fails (provider error, no tool call), falls
   * back to `NoChange` — the safe default that preserves state.
   */
  def classifyTopicShift(modelId: Id[Model],
                         chain: List[ParticipantId],
                         current: TopicEntry,
                         priors: List[TopicEntry],
                         proposedLabel: String,
                         proposedSummary: String,
                         userMessage: String): Task[TopicShiftResult] = {
    val priorsBlock =
      if (priors.isEmpty) "  (none)"
      else priors.map(p => s"  - \"${p.label}\" — ${p.summary}").mkString("\n")
    val systemPrompt =
      """You categorize how a proposed topic relates to a conversation's existing topics.
        |Pick exactly one value from the enum:
        |  - "NoChange" — proposed is the same subject as Current; nothing new to label.
        |  - "Refine"   — same subject as Current, but proposed is a sharper / more specific label.
        |  - <prior label> — same subject as one of the prior topics. The user is returning.
        |  - "New"      — genuinely different from Current and all priors.""".stripMargin
    val userPrompt =
      s"""User just said: ${quote(userMessage)}
         |
         |Current topic:
         |  - "${current.label}" — ${current.summary}
         |
         |Previous topics:
         |$priorsBlock
         |
         |Proposed topic for this turn:
         |  - "$proposedLabel" — $proposedSummary
         |
         |Pick exactly one value from the enum.""".stripMargin
    val tool = new TopicClassifierTool(priors.map(_.label))
    ConsultTool.invoke(
      sigil = this,
      modelId = modelId,
      chain = chain,
      systemPrompt = systemPrompt,
      userPrompt = userPrompt,
      tool = tool,
      generationSettings = GenerationSettings(maxOutputTokens = Some(50), temperature = Some(0.0))
    ).map {
      case None => TopicShiftResult.NoChange
      case Some(input) => input.kind match {
        case "NoChange" => TopicShiftResult.NoChange
        case "Refine"   => TopicShiftResult.Refine
        case "New"      => TopicShiftResult.New
        case other      =>
          priors.find(_.label == other)
            .map(TopicShiftResult.Return(_))
            .getOrElse(TopicShiftResult.NoChange)
      }
    }
  }

  private def quote(s: String): String = "\"" + s.replace("\"", "\\\"") + "\""

  // -- conversation helpers --

  /**
   * Create a new [[Conversation]] seeded with an initial [[Topic]]. Both
   * records are persisted so the conversation's `currentTopicId` resolves
   * from the moment it's written. Returns the stored conversation.
   *
   * Apps should route new-conversation creation through here (rather than
   * constructing `Conversation` directly) so the Topic invariant is never
   * violated. `label` defaults to [[Topic.DefaultLabel]] — the LLM is
   * expected to rename it on its first `respond` call once the subject
   * becomes clear.
   */
  def newConversation(createdBy: ParticipantId,
                      label: String = Topic.DefaultLabel,
                      summary: String = Topic.DefaultSummary,
                      participants: List[Participant] = Nil,
                      currentMode: Mode = Mode.Conversation,
                      conversationId: Id[Conversation] = Conversation.id()): Task[Conversation] = {
    val topic = Topic(
      conversationId = conversationId,
      label = label,
      summary = summary,
      createdBy = createdBy
    )
    val conversation = Conversation(
      topics = List(TopicEntry(topic._id, topic.label, topic.summary)),
      participants = participants,
      currentMode = currentMode,
      _id = conversationId
    )
    for {
      _ <- withDB(_.topics.transaction(_.upsert(topic)))
      stored <- withDB(_.conversations.transaction(_.upsert(conversation)))
    } yield stored
  }

  /**
   * Resolve the current [[Topic]] record for a conversation. Returns
   * `None` only if the conversation's `currentTopicId` refers to a
   * missing Topic record (a data-integrity failure — the invariant is
   * that `newConversation` always persists one).
   */
  def currentTopic(conversation: Conversation): Task[Option[Topic]] =
    withDB(_.topics.transaction(_.get(conversation.currentTopicId)))

  private final def logBroadcastError(signal: Signal, t: Throwable): Task[Unit] =
    Task(scribe.warn(s"Broadcaster failed for signal: ${signal.getClass.getSimpleName}", t))

  private final def fanOut(event: Event): Task[Unit] =
    withDB(_.conversations.transaction(_.get(event.conversationId))).flatMap {
      case None       => Task.unit
      case Some(conv) =>
        val tasks: List[Task[Unit]] = conv.participants.collect {
          case agent: AgentParticipant if TriggerFilter.isTriggerFor(agent, event) =>
            tryFire(agent, conv)
        }
        Task.sequence(tasks).unit
    }

  /**
   * Atomically claim `AgentState(Active)` for `(agent, conv)`. If we win the
   * claim, broadcast the new AgentState and start the agent's self-loop on
   * a background fiber. If someone else already owns the lock, no-op.
   *
   * The lock IS the AgentState record, identified by a stable id derived
   * from `(agentId, conversationId)`. Each turn upserts the same id; the
   * `AtomicReference` captures whether OUR `f` was the one that returned a
   * fresh `Active` (the only way to tell with `tx.modify` semantics).
   */
  private final def tryFire(agent: AgentParticipant, conv: Conversation): Task[Unit] = {
    val lockId = agentStateLockId(agent.id, conv._id)
    val claimedRef = new AtomicReference[Option[AgentState]](None)
    withDB(_.events.transaction(_.modify(lockId) {
      case Some(s: AgentState) if s.state == EventState.Active =>
        Task.pure(Some(s))  // someone else owns it; observe and bail
      case _ =>
        val claim = AgentState(
          agentId = agent.id,
          participantId = agent.id,
          conversationId = conv._id,
          topicId = conv.currentTopicId,
          activity = AgentActivity.Thinking,
          state = EventState.Active,
          timestamp = Timestamp(Nowish()),
          _id = lockId
        )
        claimedRef.set(Some(claim))
        Task.pure(Some(claim))
    })).flatMap { _ =>
      claimedRef.get() match {
        case Some(claim) =>
          // We won the claim. Register a StopFlag for this claim so any
          // Stop events published against this agent can interrupt.
          stopFlags.put(claim._id, new StopFlag)
          // Broadcast manually (modify already persisted), then fire the
          // agent on its own fiber.
          broadcaster.handle(claim).handleError(logBroadcastError(claim, _)).flatMap { _ =>
            Task {
              runAgent(agent, conv, claim).startUnit()
              ()
            }
          }
        case None => Task.unit
      }
    }
  }

  /**
   * Self-loop while holding the AgentState(Active) claim:
   *
   *   - process triggers for the current iteration
   *   - check DB for any new triggers that arrived during processing
   *   - if any, loop without releasing the claim
   *   - if none, transition to Idle/Complete and release
   */
  /** Hard cap on dispatcher self-loop iterations within a single AgentState
    * claim. Prevents an LLM that keeps calling non-terminal tools (e.g. only
    * `change_mode`, never `respond`) from looping forever. Reaching the cap
    * raises [[AgentRunawayException]] in the runAgent fiber after releasing
    * the AgentState claim — it's a real failure, not a normal exit. Apps
    * can override. */
  protected def maxAgentIterations: Int = 10

  private final def runAgent(agent: AgentParticipant,
                             conv: Conversation,
                             claimed: AgentState): Task[Unit] =
    runAgentLoop(agent, conv._id, claimed, iteration = 1, sinceTimestamp = claimed.timestamp)

  /**
   * `sinceTimestamp` advances per iteration — each loop hands the next one
   * its own start-time, so events consumed by the previous iteration
   * (ModeChange from iter 1, ToolResults from iter 2, etc.) don't re-appear
   * as "new triggers" on every subsequent check and cause spurious loops.
   *
   * The very first iteration uses `claim.timestamp` as its starting point
   * so external triggers that landed between claim-time and iteration-1
   * start are still visible.
   */
  private final def runAgentLoop(agent: AgentParticipant,
                                 convId: Id[Conversation],
                                 claimed: AgentState,
                                 iteration: Int,
                                 sinceTimestamp: Timestamp): Task[Unit] = Task.defer {
    // Snapshot the start of THIS iteration. The next iteration uses this as
    // its own `sinceTimestamp`, so events emitted during this iteration
    // (including self-emitted non-terminal tool results the agent acted on)
    // don't re-appear as triggers next time.
    val thisIterationStart = Timestamp(Nowish())
    val stopFlag = Option(stopFlags.get(claimed._id))
    // A Stop may have landed before this iteration even starts; short-
    // circuit if so (graceful = "don't start another iteration"; force
    // = "same, plus the in-flight stream below won't run"). Either way,
    // release and exit.
    if (stopFlag.exists(_.requested)) releaseClaim(claimed)
    else
    // Reload the conversation each iteration — materialized projections
    // (currentMode, modified, etc.) update as Events flow through `publish`,
    // so the conversation we hand to the agent must reflect the latest state.
    withDB(_.conversations.transaction(_.get(convId))).flatMap {
      case None =>
        // Conversation deleted mid-turn — release the lock and exit cleanly.
        releaseClaim(claimed)
      case Some(conv) =>
        // Snapshot suggestedTools BEFORE the iteration so we can detect
        // whether a fresh `find_capability` during the turn replaced them.
        // If it did, the new list survives to the next turn. If it didn't,
        // the snapshot (which the agent saw in its roster this turn)
        // decays — suggestions live for exactly ONE turn; an agent that
        // doesn't call what it discovered loses it.
        viewFor(convId).map(_.projectionFor(agent.id).suggestedTools).flatMap { suggestedSnapshot =>
          buildContext(agent, conv, sinceTimestamp = sinceTimestamp, claimedId = claimed._id).flatMap {
            case (ctx, triggers) =>
              // Wrap the agent's signal stream with a force-stop check so a
              // Stop(force=true) mid-iteration terminates the stream promptly.
              val rawStream = agent.process(ctx, triggers)
              val interruptible = stopFlag match {
                case Some(flag) => rawStream.takeWhile(_ => !flag.force.get())
                case None       => rawStream
              }
              interruptible.evalTap(publish).drain
          }.flatMap(_ => decaySuggestedTools(convId, agent.id, suggestedSnapshot))
        }.flatMap { _ =>
          // After the iteration drains, check stop flags before anything
          // else — a Stop that fired mid-stream means exit now, don't
          // continue looping even if there are new triggers.
          if (stopFlag.exists(_.requested)) releaseClaim(claimed)
          else newTriggersExist(agent, conv, sinceTimestamp = thisIterationStart).flatMap {
            case true if iteration < maxAgentIterations =>
              runAgentLoop(agent, convId, claimed, iteration + 1, thisIterationStart)
            case true =>
              // Cap hit — release the lock, then propagate as an error so the
              // calling fiber's failure handler sees it. A runaway loop is a
              // real failure (broken LLM behavior, bad instructions, etc.) and
              // shouldn't masquerade as a successful exit.
              releaseClaim(claimed).flatMap(_ =>
                Task.error(new AgentRunawayException(
                  s"Agent ${agent.id.value} hit maxAgentIterations ($maxAgentIterations) " +
                    s"in conversation ${conv._id.value}; check LLM behavior or raise the cap.")))
            case false =>
              releaseClaim(claimed)
          }
        }
    }.handleError { t =>
      // Any unhandled failure mid-turn — release the lock so the agent
      // isn't stuck Active forever, then re-raise so the fiber's error
      // boundary logs it. Failure during release itself is swallowed (we
      // already have the original error to report).
      scribe.error(s"runAgent failed for ${agent.id.value} in ${convId.value}", t)
      releaseClaim(claimed).handleError(_ => Task.unit).flatMap(_ => Task.error(t))
    }
  }

  /** One-turn decay for `suggestedTools` on the acting participant's
    * projection. If the current list equals the snapshot we took before
    * the iteration started, no fresh `find_capability` ran this turn —
    * the agent either called a previously-discovered tool (and the list
    * has served its purpose) or ignored it. Either way, clear so the
    * suggestion doesn't leak into another turn. If the list differs, a
    * new discovery landed during the iteration; keep the new list so
    * the NEXT turn can call it. */
  private final def decaySuggestedTools(conversationId: Id[Conversation],
                                         agentId: ParticipantId,
                                         snapshot: List[sigil.tool.ToolName]): Task[Unit] =
    viewFor(conversationId).flatMap { view =>
      val current = view.projectionFor(agentId).suggestedTools
      if (current == snapshot && current.nonEmpty)
        updateProjection(conversationId, agentId)(_.copy(suggestedTools = Nil))
      else Task.unit
    }

  private final def releaseClaim(claimed: AgentState): Task[Unit] = {
    // Always-run cleanup — the flag must never leak even if the
    // Idle/Complete publish fails (broadcaster error, DB error, etc.).
    // A leaked flag is a minor map entry, but across many failures it'd
    // accumulate and match future Stop events against a non-existent
    // claim.
    val cleanup = Task {
      stopFlags.remove(claimed._id)
      ()
    }
    publish(AgentStateDelta(
      target = claimed._id,
      conversationId = claimed.conversationId,
      activity = Some(AgentActivity.Idle),
      state = Some(EventState.Complete)))
      .flatMap(_ => cleanup)
      .handleError(t => cleanup.flatMap(_ => Task.error(t)))
  }

  private final def buildContext(agent: AgentParticipant,
                                 conv: Conversation,
                                 sinceTimestamp: Timestamp,
                                 claimedId: Id[Event]): Task[(TurnContext, Stream[Event])] =
    for {
      view <- viewFor(conv._id)
      triggerEvents <- withDB(_.events.transaction(_.list)).map { all =>
        all.view
          .filter(e => e.conversationId == conv._id
                    && e.timestamp.value > sinceTimestamp.value
                    && TriggerFilter.isTriggerFor(agent, e))
          .toList
      }
      chain = buildChain(triggerEvents, agent)
      input <- curate(view, agent.modelId, chain)
    } yield {
      val triggers: Stream[Event] = Stream.emits(triggerEvents)
      val ctx = TurnContext(
        sigil = this,
        chain = chain,
        conversation = conv,
        conversationView = view,
        turnInput = input,
        currentAgentStateId = Some(claimedId)
      )
      (ctx, triggers)
    }

  private final def newTriggersExist(agent: AgentParticipant,
                                     conv: Conversation,
                                     sinceTimestamp: Timestamp): Task[Boolean] =
    withDB(_.events.transaction(_.list)).map { all =>
      all.exists(e => e.conversationId == conv._id
                   && e.timestamp.value > sinceTimestamp.value
                   && TriggerFilter.isTriggerFor(agent, e))
    }

  private final def buildChain(triggers: List[Event], agent: AgentParticipant): List[ParticipantId] = {
    val source = triggers.find(_.participantId != agent.id).map(_.participantId)
    source.toList :+ agent.id
  }

  /** Stable per-(agent, conversation) id used as both the AgentState key and
    * the lock-acquisition target inside `tx.modify`. */
  private final def agentStateLockId(agentId: AgentParticipantId, convId: Id[Conversation]): Id[Event] =
    Id(s"agentlock:${agentId.value}:${convId.value}")

  // -- lifecycle --

  val instance: Task[SigilInstance] = Task.defer {
    for {
      _ <- logger.info("Sigil initializing...")
      _ <- Task(Profig.initConfiguration())
      _ = Signal.register((CoreSignals.all ++ signals)*)
      _ = ToolInput.register((CoreTools.inputRWs ++ findTools.toolInputRWs)*)
      _ = ParticipantId.register(participantIds*)
      _ = Participant.register((summon[RW[DefaultAgentParticipant]] :: participants)*)
      _ = MemorySpaceId.register(memorySpaceIds*)
      config = Profig("sigil").as[Config]
      (directory, collectionStore) = config.postgres match {
        case Some(pg) =>
          val cm = HikariConnectionManager(SQLConfig(
            jdbcUrl = pg.jdbcUrl,
            driverClassName = Some("org.postgresql.Driver"),
            username = pg.username,
            password = pg.password,
            maximumPoolSize = pg.maximumPoolSize
          ))
          (None, PostgreSQLStoreManager(cm): CollectionManager)
        case None =>
          (Some(config.dbPath), SplitStoreManager(RocksDBSharedStore(config.dbPath), LuceneStore): CollectionManager)
      }
      db = SigilDB(directory, collectionStore)
      _ <- db.init
      _ <- if (vectorWired) vectorIndex.ensureCollection(embeddingProvider.dimensions)
           else Task.unit
    } yield SigilInstance(
      config = config,
      db = db
    )
  }.singleton

  def withDB[Return](f: SigilDB => Task[Return]): Task[Return] = instance.flatMap(sigil => f(sigil.db))

  object cache {

    def findModel(provider: Option[String] = None, model: Option[String] = None): rapid.Stream[Model] =
      rapid.Stream.force(withDB { db =>
        db.model.transaction { modelCache =>
          modelCache.query
            .filterOption(mc => provider.map(p => mc.provider === p.toLowerCase))
            .filterOption(mc => model.map(m => mc.model === m.toLowerCase))
            .toList
        }
      }.map(rapid.Stream.emits))

    def apply(provider: String, model: String): Task[Option[Model]] =
      withDB { db =>
        db.model.transaction { modelCache =>
          modelCache.get(Model.id(provider, model))
        }
      }
  }

  case class SigilInstance(config: Config, db: SigilDB)
}
