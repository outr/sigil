package sigil.db

import lightdb.LightDB
import lightdb.cache.CacheConfig
import lightdb.filter.*
import lightdb.id.Id
import lightdb.store.CollectionManager
import lightdb.upgrade.DatabaseUpgrade
import rapid.Task
import sigil.conversation.{ContextMemory, ContextSummary, Conversation, ConversationToolOverlay, EncodedContext, ParticipantProjection, Topic}
import sigil.event.Event
import sigil.information.StoredInformation
import sigil.signal.{Delta, Signal}
import sigil.provider.{ProviderConfig, ProviderStrategyRecord, SpaceProviderAssignment}
import sigil.skill.Skill
import sigil.spatial.GeocodingCache
import sigil.storage.StoredFile
import sigil.tool.Tool
import sigil.viewer.ViewerState

import java.nio.file.Path
import scala.concurrent.duration.*

/**
 * Core lightdb wrapper carrying the framework's standard collections.
 *
 * `SigilDB` is open for extension via Scala 3 self-typed mix-in traits.
 * Module sub-projects (e.g. `sigil-secrets`, future `sigil-mcp`) ship a
 * `<X>Collections { self: SigilDB => ... }` trait that adds their own
 * lightdb stores; apps assemble the concrete DB by mixing the traits
 * they want into a single class:
 *
 * {{{
 *   class MyAppDB(directory: Option[Path],
 *                 storeManager: CollectionManager,
 *                 upgrades: List[DatabaseUpgrade] = Nil)
 *     extends SigilDB(directory, storeManager, upgrades)
 *       with SecretsCollections
 *       with McpCollections
 * }}}
 *
 * The Sigil instance refines `type DB = MyAppDB`, satisfying every
 * module's `Sigil { type DB <: SigilDB & XCollections }` bound at once.
 */
abstract class SigilDB(override val directory: Option[Path],
                       override val storeManager: CollectionManager,
                       appUpgrades: List[DatabaseUpgrade] = Nil) extends LightDB {
  override type SM = CollectionManager

  val events: S[Event, Event.type] = store(Event)()
  val conversations: S[Conversation, Conversation.type] = store(Conversation).withCache(CacheConfig.lru(1000))()
  val memories: S[ContextMemory, ContextMemory.type] = store(ContextMemory).withCache(CacheConfig.lru(500, 5.minutes))()
  val summaries: S[ContextSummary, ContextSummary.type] = store(ContextSummary).withCache(CacheConfig.lru(500, 5.minutes))()
  val participantProjections: S[ParticipantProjection, ParticipantProjection.type] =
    store(ParticipantProjection).withCache(CacheConfig.lru(1000))()
  val encodedContexts: S[EncodedContext, EncodedContext.type] =
    store(EncodedContext).withCache(CacheConfig.lru(500))()
  val topics: S[Topic, Topic.type] = store(Topic).withCache(CacheConfig.lru(2000))()
  val geocodingCache: S[GeocodingCache, GeocodingCache.type] = store(GeocodingCache)()
  val tools: S[Tool, Tool.type] = store(Tool).withCache(CacheConfig.lru(500))()
  val skills: S[Skill, Skill.type] = store(Skill).withCache(CacheConfig.lru(500))()
  val storedFiles: S[StoredFile, StoredFile.type] = store(StoredFile)()
  val providerConfigs: S[ProviderConfig, ProviderConfig.type] = store(ProviderConfig)()
  val providerStrategies: S[ProviderStrategyRecord, ProviderStrategyRecord.type] = store(ProviderStrategyRecord)()
  val providerAssignments: S[SpaceProviderAssignment, SpaceProviderAssignment.type] = store(SpaceProviderAssignment)()
  val viewerStates: S[ViewerState, ViewerState.type] = store(ViewerState).withCache(CacheConfig.lru(500))()
  val conversationToolOverlays: S[ConversationToolOverlay, ConversationToolOverlay.type] =
    store(ConversationToolOverlay).withCache(CacheConfig.lru(500))()

  /** Large frame content externalized by block extraction during
    * curation, resolved back via [[sigil.Sigil.getInformation]]. */
  val storedInformations: S[StoredInformation, StoredInformation.type] =
    store(StoredInformation).withCache(CacheConfig.lru(200, 30.minutes))()

  /** Persisted snapshot of the framework's known [[Model]] catalog
    * (sigil #277). Seeded into the in-memory [[sigil.cache.ModelRegistry]]
    * at `Sigil.instance` boot; refreshed from OpenRouter when the slot
    * is empty or stale per `Sigil.modelRefreshInterval`. Default is a
    * never-refreshed empty marker. */
  val models: lightdb.StoredValue[Models] = stored[Models]("models", Models())

  override def upgrades: List[DatabaseUpgrade] = appUpgrades

  /**
   * Conversation-keyed registry of batched `events` write scopes.
   *
   * The split store fsyncs (`IndexWriter.commit`) once per
   * transaction. The agent loop publishes one signal per streamed
   * token, each previously opening its own transaction — hundreds
   * of commits per turn. [[withBatchedEvents]] opens a single
   * transaction for one agent-loop iteration and registers a
   * [[BatchedEventScope]] here keyed by `conversationId`;
   * [[eventsTransaction]] then routes every `events` read/write for
   * that conversation into the shared transaction so the whole
   * iteration costs one commit.
   *
   * Each scope also accumulates the events written this iteration
   * (see [[BatchedEventScope.inFlight]]) so [[batchedEventScope]]
   * callers — notably [[sigil.Sigil.eventsFor]] page 0 — can merge
   * the still-uncommitted live edge into their read.
   *
   * Registry membership is per-conversation: a signal whose
   * conversation has no batched scope (every non-loop publish — user
   * messages, external API calls, tool emissions outside a loop)
   * falls through to a fresh per-call transaction, byte-identical to
   * the unbatched path.
   */
  private val batchedEventTx: scala.collection.concurrent.TrieMap[Id[Conversation], BatchedEventScope[events.TX]] =
    scala.collection.concurrent.TrieMap.empty

  /**
   * Counter of write-bearing `events` transactions opened (each
   * maps 1:1 to a Lucene `IndexWriter.commit` fsync). Incremented
   * once per [[withBatchedEvents]] scope and once per [[apply]]
   * call that has no batched scope to join. Read-only routing
   * through [[eventsTransaction]] does not count — a read
   * transaction's commit is a near-noop. Diagnostic / spec hook
   * only.
   */
  private val eventsWriteCommitCount: java.util.concurrent.atomic.AtomicLong =
    new java.util.concurrent.atomic.AtomicLong(0L)

  /** Current value of the [[eventsWriteCommitCount]] commit counter. */
  def eventsWriteCommits: Long = eventsWriteCommitCount.get()

  /**
   * Hold a single `events` transaction open for the duration of
   * `task`, registered under `conversationId`. Every [[apply]] and
   * every read routed through [[eventsTransaction]] for that
   * conversation joins the shared transaction; the one commit
   * happens when the scope exits (success or failure).
   *
   * Durability note: events written inside the scope become durable
   * at scope exit, not per-write. A crash mid-scope loses that
   * scope's writes — the agent loop wraps one iteration per scope,
   * so a crash loses at most the in-flight iteration. This is the
   * intended trade for eliminating per-token commit amplification.
   */
  def withBatchedEvents[A](conversationId: Id[Conversation])(task: Task[A]): Task[A] =
    Task(eventsWriteCommitCount.incrementAndGet()).flatMap { _ =>
      events.transaction { tx =>
        batchedEventTx.put(conversationId, new BatchedEventScope[events.TX](tx))
        task.guarantee(Task {
          batchedEventTx.remove(conversationId)
          ()
        })
      }
    }

  /**
   * The active [[BatchedEventScope]] for `conversationId`, if a
   * [[withBatchedEvents]] scope is currently open for it.
   *
   * Read by [[sigil.Sigil.eventsFor]] page 0 to merge the
   * iteration's still-uncommitted events with the committed page —
   * an event published mid-iteration is hub-broadcast immediately
   * but not yet in the DB, so a session joining the conversation
   * after the broadcast needs the accumulator to see it. `None`
   * outside an iteration; the common cold-path read.
   */
  def batchedEventScope(conversationId: Id[Conversation]): Option[BatchedEventScope[events.TX]] =
    batchedEventTx.get(conversationId)

  /**
   * Run `f` against the `events` transaction for `conversationId`.
   * When a [[withBatchedEvents]] scope is active for that
   * conversation, `f` runs on the shared transaction (no new
   * transaction, no commit — the scope commits). Otherwise `f` runs
   * in a fresh per-call transaction exactly as a direct
   * `events.transaction(f)` would.
   *
   * Routing every per-iteration `events` access through this method
   * keeps reads consistent with batched-but-uncommitted writes: a
   * read inside the scope sees the scope's own pending inserts /
   * upserts.
   */
  def eventsTransaction[A](conversationId: Id[Conversation])(f: events.TX => Task[A]): Task[A] =
    batchedEventTx.get(conversationId) match {
      // Sigil #355 — serialize every routed access to the shared scope transaction. The scope
      // holds one backing connection; concurrent fibers (the turn plus foreign publishes to the
      // same conversation) must not drive it at once or a completion is lost and the loop hangs.
      case Some(scope) => scope.serialize(f(scope.transaction))
      case None        => events.transaction(f)
    }

  /**
   * All events for `conversationId`, scoped by the `conversationId` index — NOT a whole-store
   * `_.list`. Runs through [[eventsTransaction]], so when a [[withBatchedEvents]] scope is open it
   * executes on that same transaction and sees the turn's still-uncommitted writes (including
   * direct frame-attach upserts) exactly as `_.list` did — just without materializing every event
   * of every conversation into memory.
   *
   * Mid-turn decision logic (refusal-challenge, repeated-query intercept, self-heal, stall
   * detection) previously read `eventsTransaction(convId)(_.list)` then filtered by id — loading the
   * entire events store on each check. This bounds that footprint to one conversation while keeping
   * identical read-your-writes semantics.
   */
  def conversationEvents(conversationId: Id[Conversation]): Task[List[Event]] =
    eventsTransaction(conversationId)(_.query.filter(_ => Event.conversationId === conversationId.value).toList)

  /**
   * Apply a [[Signal]] to the events store. Events insert; Deltas
   * upsert a mutated target row; Notices are transient pulses with
   * no persistence — they fall through as a no-op so callers can
   * pipe a raw signal stream straight into `apply` without filtering.
   *
   * Both Event and Delta carry `conversationId`, so the signal
   * routes to the right batched transaction (when one is active)
   * by conversation id alone — see [[eventsTransaction]].
   *
   * When a [[withBatchedEvents]] scope is active for the signal's
   * conversation the written event is also recorded into that
   * scope's [[BatchedEventScope.inFlight]] accumulator — the
   * post-delta-applied event for a Delta — so the iteration's
   * still-uncommitted live edge is visible to [[eventsFor]] page 0.
   */
  def apply(signal: Signal): Task[Unit] = signal match {
    case e: Event =>
      countUnbatchedWrite(e.conversationId)
        .flatMap(_ => eventsTransaction(e.conversationId)(_.insert(e)).unit)
        .map(_ => recordInFlight(e.conversationId, e))
    case d: Delta =>
      countUnbatchedWrite(d.conversationId).flatMap(_ => eventsTransaction(d.conversationId) { tx =>
        tx.get(d.target.asInstanceOf[Id[Event]]).flatMap {
          case Some(target) =>
            val updated = d(target)
            tx.upsert(updated).map(_ => recordInFlight(d.conversationId, updated))
          case None => Task.unit
        }
      })
    case _: sigil.signal.Notice => Task.unit
  }

  /** Record `event` into the batched scope's accumulator when one
    * is active for `conversationId`; no-op otherwise. */
  private def recordInFlight(conversationId: Id[Conversation], event: Event): Unit =
    batchedEventTx.get(conversationId).foreach(_.record(event))

  /** Tick the write-commit counter for an `apply` that will open
    * its own fresh transaction (no [[withBatchedEvents]] scope to
    * join). Batched writes are counted once per scope, not here. */
  private def countUnbatchedWrite(conversationId: Id[Conversation]): Task[Unit] =
    Task {
      if (!batchedEventTx.contains(conversationId)) eventsWriteCommitCount.incrementAndGet()
      ()
    }
}

/**
 * Concrete `SigilDB` with no module mix-ins. Default for apps that
 * don't need extension collections; the framework's `Sigil` builds
 * an instance of this when `type DB = SigilDB` (the default).
 */
final class DefaultSigilDB(directory: Option[Path],
                           storeManager: CollectionManager,
                           appUpgrades: List[DatabaseUpgrade] = Nil)
  extends SigilDB(directory, storeManager, appUpgrades)
