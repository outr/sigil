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
 * Conversation-lifecycle cluster — create, join, leave, status, clear
 * and delete, plus the workspace / delegation-depth resolution that
 * walks the `parentConversationId` chain and the staging-import
 * surface (open, silent chunked persist, atomic merge, cancel).
 *
 * Mixed into [[Sigil]]; the self-type reaches `withDB`, `publish`,
 * `fireGreeting`, and the topic surface a fresh conversation seeds.
 */
trait ConversationOps { this: Sigil =>

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
                      currentMode: Mode = ConversationMode,
                      parentConversationId: Option[Id[Conversation]] = None,
                      pinnedComplexity: Option[sigil.provider.Complexity] = None,
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
      parentConversationId = parentConversationId,
      pinnedComplexity = pinnedComplexity,
      _id = conversationId
    )
    for {
      _      <- withDB(_.topics.transaction(_.upsert(topic)))
      stored <- withDB(_.conversations.transaction(_.upsert(conversation)))
      // Broadcast the lifecycle Notice so live viewers' UI panels can
      // pick up the new conversation without polling.
      _      <- publish(sigil.signal.ConversationCreated(stored._id, createdBy))
      // Fire greetings in-line per agent. fireGreeting is a no-op for agents
      // without greet-eligible behaviors, so the cost for non-greeting setups
      // is just the participants.collect walk.
      //
      // Sigil #350 — never greet in a worker/delegated sub-conversation. Its
      // opener is the brief (`delegate_task` posts it), and there is no user
      // to greet. A greeting there makes the supervisor run "how can I help?"
      // turns AND poisons the worker's context (the worker mirrors the
      // greeting). Suppress whenever the conversation has a parent.
      _      <- if (stored.parentConversationId.isDefined) Task.unit
                else Task.sequence(stored.participants.collect {
                  case agent: AgentParticipant => fireGreeting(agent, stored)
                })
    } yield stored
  }

  /**
   * Resolve the [[Conversation]] for `conversationId`, creating it (via
   * [[newConversation]]) with the supplied defaults if no row exists.
   * Returns the resulting Conversation either way.
   *
   * Idempotent — calling this on every wire-connect is the canonical
   * pattern for chat-shaped consumers that want lazy-create-on-first-
   * contact semantics. The participant list, label, summary, and mode
   * are only used on the create path; pre-existing conversations are
   * returned unchanged regardless of what is passed.
   *
   * Greet-on-join behavior matches [[newConversation]] — when the row
   * is being created, agent participants flagged with `greetsOnJoin`
   * fire their greeting; on the get path no greeting fires (the agent
   * already greeted on the original create).
   */
  def getOrCreateConversation(conversationId: Id[Conversation],
                              createdBy: ParticipantId,
                              label: String = Topic.DefaultLabel,
                              summary: String = Topic.DefaultSummary,
                              participants: List[Participant] = Nil,
                              currentMode: Mode = ConversationMode): Task[Conversation] =
    withDB(_.conversations.transaction(_.get(conversationId))).flatMap {
      case Some(c) => Task.pure(c)
      case None =>
        newConversation(
          createdBy = createdBy,
          label = label,
          summary = summary,
          participants = participants,
          currentMode = currentMode,
          conversationId = conversationId
        )
    }

  /**
   * Resolve the current [[Topic]] record for a conversation. Returns
   * `None` only if the conversation's `currentTopicId` refers to a
   * missing Topic record (a data-integrity failure — the invariant is
   * that `newConversation` always persists one).
   */
  def currentTopic(conversation: Conversation): Task[Option[Topic]] =
    withDB(_.topics.transaction(_.get(conversation.currentTopicId)))

  /**
   * Add a [[Participant]] to an existing conversation. Persists the
   * appended participant list, then — if the new participant is an
   * [[AgentParticipant]] — fires its greet-eligible behaviors via
   * [[fireGreeting]] so a late-joining agent has the same opportunity
   * to introduce itself as one that was present at conversation
   * creation.
   *
   * Idempotent: if the participant is already in the conversation,
   * returns the unmodified conversation and skips the greeting.
   *
   * Fails with [[ConversationNotFoundException]] when the conversation
   * id doesn't resolve.
   */
  def addParticipant(conversationId: Id[Conversation],
                     participant: Participant): Task[Conversation] =
    withDB(_.conversations.transaction(_.get(conversationId))).flatMap {
      case None =>
        Task.error(new ConversationNotFoundException(conversationId))
      case Some(conv) if conv.participants.exists(_.id == participant.id) =>
        Task.pure(conv)
      case Some(conv) =>
        val updated = conv.copy(participants = conv.participants :+ participant)
        for {
          stored <- withDB(_.conversations.transaction(_.upsert(updated)))
          _      <- publish(sigil.signal.ParticipantAdded(conversationId, participant))
          _      <- participant match {
                      case agent: AgentParticipant => fireGreeting(agent, stored)
                      case _                       => Task.unit
                    }
        } yield stored
    }

  /**
   * Remove a participant from a conversation. Persists the trimmed list
   * and broadcasts a [[sigil.signal.ParticipantRemoved]] Notice so
   * live viewers can drop the participant from member lists / sidebar
   * UI.
   *
   * Idempotent: if the participant isn't in the conversation, returns
   * the unchanged conversation and emits no Notice. Fails with
   * [[ConversationNotFoundException]] when the conversation id doesn't
   * resolve.
   */
  def removeParticipant(conversationId: Id[Conversation],
                        participantId: ParticipantId): Task[Conversation] =
    withDB(_.conversations.transaction(_.get(conversationId))).flatMap {
      case None =>
        Task.error(new ConversationNotFoundException(conversationId))
      case Some(conv) if !conv.participants.exists(_.id == participantId) =>
        Task.pure(conv)
      case Some(conv) =>
        val updated = conv.copy(participants = conv.participants.filterNot(_.id == participantId))
        for {
          stored <- withDB(_.conversations.transaction(_.upsert(updated)))
          _      <- publish(sigil.signal.ParticipantRemoved(conversationId, participantId))
        } yield stored
    }

  /**
   * Replace a participant's record in a conversation — used to push
   * display-info changes (`displayName`, `avatarUrl`, app-specific
   * fields on a [[Participant]] subtype) out to live viewers without
   * requiring a remove + re-add.
   *
   * The replacement is keyed on `participant.id`; if no current
   * participant matches the id, this is a no-op and emits no Notice.
   * Otherwise the new record replaces the old in the conversation's
   * `participants` list and a [[sigil.signal.ParticipantUpdated]]
   * Notice is broadcast.
   *
   * Fails with [[ConversationNotFoundException]] when the conversation
   * id doesn't resolve.
   */
  def updateParticipant(conversationId: Id[Conversation],
                        participant: Participant): Task[Conversation] =
    withDB(_.conversations.transaction(_.get(conversationId))).flatMap {
      case None =>
        Task.error(new ConversationNotFoundException(conversationId))
      case Some(conv) if !conv.participants.exists(_.id == participant.id) =>
        Task.pure(conv)
      case Some(conv) =>
        val updated = conv.copy(participants = conv.participants.map { p =>
          if (p.id == participant.id) participant else p
        })
        for {
          stored <- withDB(_.conversations.transaction(_.upsert(updated)))
          _      <- publish(sigil.signal.ParticipantUpdated(conversationId, participant))
        } yield stored
    }

  /**
   * Set a conversation's app-defined [[sigil.conversation.ConversationStatus]],
   * persist it, and broadcast a [[sigil.signal.ConversationStatusChanged]]
   * Notice so history-sidebar UIs re-bucket the thread live (sigil #386).
   *
   * Idempotent: an unchanged status returns the conversation untouched and
   * emits no Notice. The framework assigns the status NO meaning — apps own
   * the value and every transition (including intra-axis mutual exclusion;
   * "move InProgress → Completed" is the app replacing one status with
   * another). Query by category via the `Conversation.statusKey` index.
   *
   * Fails with [[ConversationNotFoundException]] when the id doesn't resolve.
   */
  def setConversationStatus(conversationId: Id[Conversation],
                            status: sigil.conversation.ConversationStatus): Task[Conversation] =
    withDB(_.conversations.transaction(_.get(conversationId))).flatMap {
      case None =>
        Task.error(new ConversationNotFoundException(conversationId))
      case Some(conv) if conv.status == status =>
        Task.pure(conv)
      case Some(conv) =>
        val updated = conv.copy(status = status)
        for {
          stored <- withDB(_.conversations.transaction(_.upsert(updated)))
          _      <- publish(sigil.signal.ConversationStatusChanged(conversationId, status))
        } yield stored
    }

  /**
   * Resolve the conversations a viewer can see. Currently the
   * underlying `SigilDB.conversations` is unscoped — every
   * conversation is visible to every viewer. Apps that need
   * per-viewer / per-space scoping override this hook.
   *
   * Used by the framework's default [[handleNotice]] arm for
   * [[sigil.signal.RequestConversationList]] — the snapshot that
   * goes back to the client is built from this list.
   */
  protected def listConversations(viewer: ParticipantId): Task[List[Conversation]] =
    withDB(_.conversations.transaction(_.list))

  /**
   * Filesystem path the conversation is working against — the
   * "project root" / "workspace." Returns `None` when no workspace
   * is configured for the conversation; tools that consult this
   * (filesystem tools rooting relative paths, `MetalsSigil`'s
   * subprocess routing, future BSP / build-server integrations)
   * fall back to their default behavior.
   *
   * Default: `Task.pure(None)`. Apps with a workspace concept
   * (Sage's per-conversation project, an app's project record)
   * override this once and every framework feature that wants to
   * know "where is this conversation working?" gets a consistent
   * answer.
   *
   * Module traits that need workspace info default their own hook
   * onto this one — e.g. `MetalsSigil.metalsWorkspace` returns
   * `workspaceFor(conversationId)` by default. Apps overriding
   * `workspaceFor` automatically light up Metals routing, FS
   * rooting, and any future workspace-aware feature.
   */
  def workspaceFor(conversationId: Id[Conversation]): Task[Option[java.nio.file.Path]] =
    Task.pure(None)

  /**
   * Workspace for a conversation, walking the `parentConversationId`
   * chain when the conversation itself has no binding. A worker or
   * staging conversation (spawned by `delegate_task` / import staging)
   * starts with no workspace of its own — the app only bound a
   * workspace to the user-facing parent. Without this fallthrough a
   * delegated worker can discover `grep` / `read_file` but has no
   * project root to run them against, so it spins to its iteration
   * cap reporting "no workspace path" (sigil #325).
   *
   * Resolution: `workspaceFor(conversationId)` first; on `None`, load
   * the conversation and recurse into its `parentConversationId`.
   * Bounded depth guards against a malformed parent cycle. Reuses the
   * app's own `workspaceFor` override at every level — apps wire the
   * workspace once on the parent and every descendant inherits it.
   */
  def resolvedWorkspaceFor(conversationId: Id[Conversation]): Task[Option[java.nio.file.Path]] =
    resolvedWorkspaceFor(conversationId, depth = 8)

  private def resolvedWorkspaceFor(conversationId: Id[Conversation], depth: Int): Task[Option[java.nio.file.Path]] =
    workspaceFor(conversationId).flatMap {
      case found @ Some(_)    => Task.pure(found)
      case None if depth <= 0 => Task.pure(None)
      case None =>
        withDB(_.conversations.transaction(_.get(conversationId))).flatMap {
          case Some(conv) =>
            conv.parentConversationId match {
              case Some(parentId) => resolvedWorkspaceFor(parentId, depth - 1)
              case None           => Task.pure(None)
            }
          case None => Task.pure(None)
        }
    }

  /**
   * Resolve the [[FileSystemContext]] a conversation's tools operate
   * against — the seam the framework writes large tool output through.
   * When a tool result overflows [[inlineContentThreshold]], the result
   * is written to a file under this context's root
   * (`.sigil/output/<convId>/<tool>-<callId>.txt`) and the agent recovers
   * it with the filesystem tools it already has (`grep` / `read_file`),
   * rather than navigating a bespoke reference handle. Because the write
   * goes through the SAME context the agent's `grep`/`read_file` use, the
   * file lands where those tools run — including the ProxyTool remote-fs
   * case (the file is on the user's machine, where its grep runs).
   *
   * Default: wrap [[resolvedWorkspaceFor]] in a [[LocalFileSystemContext]]
   * rooted at the workspace; `None` when no workspace is bound (the
   * overflow path then falls back to inline truncate-and-tell). Apps that
   * route filesystem work through a remote/proxied backend override this
   * to return the matching context.
   */
  def fileSystemContextFor(conversationId: Id[Conversation]): Task[Option[FileSystemContext]] =
    resolvedWorkspaceFor(conversationId).map(_.map(path => new LocalFileSystemContext(Some(path))))

  /**
   * Maximum delegation depth — how deep a worker→sub-worker chain may go
   * (sigil #348). A top-level agent → worker is depth 1; that worker →
   * sub-worker is depth 2; a `delegate_task` that would exceed this is
   * refused. This still lets a worker modularize (fan out genuinely
   * separable sub-tasks at the allowed depth) while making runaway
   * worker→worker→worker recursion structurally impossible even when the
   * doer-framing prompt doesn't fully hold on a weak model. Apps override
   * to widen or tighten.
   */
  def maxDelegationDepth: Int = 2

  /**
   * Delegation depth of a conversation — the number of
   * `parentConversationId` hops to the root. A top-level (user-facing)
   * conversation is depth 0; a worker sub-conversation is depth 1; a
   * sub-worker's is depth 2; etc. Bounded walk (mirrors
   * [[resolvedWorkspaceFor]]) so a malformed parent cycle can't loop.
   */
  def delegationDepth(conversationId: Id[Conversation]): Task[Int] =
    delegationDepth(conversationId, depth = 0, fuel = 16)

  private def delegationDepth(conversationId: Id[Conversation], depth: Int, fuel: Int): Task[Int] =
    if (fuel <= 0) Task.pure(depth)
    else withDB(_.conversations.transaction(_.get(conversationId))).flatMap {
      case Some(conv) =>
        conv.parentConversationId match {
          case Some(parentId) => delegationDepth(parentId, depth + 1, fuel - 1)
          case None           => Task.pure(depth)
        }
      case None => Task.pure(depth)
    }

  /**
   * Open a staging conversation that buffers events for a
   * long-running import workflow. The staging conv is a regular
   * [[Conversation]] row with `stagingFor = Some(target)` —
   * persisted records (events, memories, summaries) addressed to
   * its id are durable but logically isolated from the target
   * conversation until [[mergeStagingIntoMain]] flips the
   * conversationId references. On cancel / crash, the staging
   * conv plus its records are reaped by
   * [[OrphanStagingConversationSweep]] (or proactively cleaned via
   * [[deleteStagingConversation]]).
   *
   * Idempotent on `_id`. Apps typically construct the staging id
   * up front (e.g. `s"import-staging-${Unique()}"`) so the
   * workflow's run state can reference it.
   */
  def createStagingConversation(stagingId: Id[Conversation],
                                stagingFor: Id[Conversation]): Task[Conversation] = {
    val staging = Conversation(
      _id        = stagingId,
      topics     = Nil,
      stagingFor = Some(stagingFor)
    )
    withDB(_.conversations.transaction(_.upsert(staging)))
  }

  /**
   * Atomically commit a staging conversation's records into its
   * target. All [[sigil.event.Event]]s, [[sigil.conversation.ContextMemory]]s,
   * and [[sigil.conversation.ContextSummary]]s with
   * `conversationId = staging` are rewritten to reference `target`,
   * the staging conversation row is deleted, and one
   * [[sigil.signal.ConversationHistoryImported]] Notice fires
   * against `target` so consumers do their single refresh.
   *
   * Uses lightdb's `tx.upsert(stream)` for each record type — one
   * call per store handles batching, indices, and the WAL. Returns
   * the number of events flipped (informational; memories /
   * summaries also moved but aren't reflected in the count).
   */
  def mergeStagingIntoMain(staging: Id[Conversation],
                           target: Id[Conversation]): Task[Int] = {
    val rewriteEvents: Task[Int] = withDB(_.events.transaction { tx =>
      val rewritten = tx.query.filter(_.conversationId === staging.value).stream
        .map(e => e.withConversationId(target))
      tx.upsert(rewritten)
    })
    val rewriteMemories: Task[Int] = withDB(_.memories.transaction { tx =>
      val rewritten = tx.query.filter(_.conversationId === Some(staging)).stream
        .map(m => m.copy(conversationId = Some(target)))
      tx.upsert(rewritten)
    })
    val rewriteSummaries: Task[Int] = withDB(_.summaries.transaction { tx =>
      val rewritten = tx.query.filter(_.conversationId === staging).stream
        .map(s => s.copy(conversationId = target))
      tx.upsert(rewritten)
    })
    for {
      eventCount <- rewriteEvents
      _          <- rewriteMemories
      _          <- rewriteSummaries
      _          <- withDB(_.conversations.transaction(_.delete(staging)))
      _          <- notifyHistoryImported(target, eventCount)
    } yield eventCount
  }

  /**
   * Drop a staging conversation and every record that references
   * it, without merging into a target. Used by explicit cancel
   * paths and by [[OrphanStagingConversationSweep]] for crash
   * recovery. Deletes events, memories, and summaries addressed
   * to the staging id, then drops the conversation row. Vector-
   * index entries for deleted memories are NOT explicitly evicted
   * — the next embed/search cycle drops stale points by id-misses
   * (matches existing `deleteConversation` semantics).
   *
   * No Notice fires — the staging conv was never visible to
   * subscribers, so there's nothing to refresh.
   */
  def deleteStagingConversation(staging: Id[Conversation]): Task[Unit] =
    for {
      _ <- withDB { db =>
             db.events.transaction { tx =>
               val ids = tx.query.filter(_.conversationId === staging.value).stream.map(_._id)
               ids.evalMap(id => tx.delete(id)).drain
             }
           }
      _ <- withDB { db =>
             db.memories.transaction { tx =>
               val ids = tx.query.filter(_.conversationId === Some(staging)).stream.map(_._id)
               ids.evalMap(id => tx.delete(id)).drain
             }
           }
      _ <- withDB { db =>
             db.summaries.transaction { tx =>
               val ids = tx.query.filter(_.conversationId === staging).stream.map(_._id)
               ids.evalMap(id => tx.delete(id)).drain
             }
           }
      _ <- withDB(_.conversations.transaction(_.delete(staging)))
    } yield ()

  /**
   * Hard-delete a conversation and every record that references it —
   * the conversation row itself, every Event, the ConversationView
   * projection, and every Topic. The deletion is best-effort and
   * non-transactional across stores; failures partway through leave
   * the DB in a partially-cleaned state and re-raise.
   *
   * After deletion, any in-flight agent loop targeting this
   * conversation will release its claim on the next iteration's
   * `withDB(_.conversations.get(...))` lookup (returns `None`,
   * `runAgentLoop` releases cleanly).
   */
  def deleteConversation(conversationId: Id[Conversation]): Task[Unit] =
    for {
      // Broadcast the lifecycle Notice BEFORE the cascade so live
      // viewers see the pulse while the SignalHub is still wired
      // (and before the conversation's records are wiped).
      _ <- publish(sigil.signal.ConversationDeleted(conversationId))
      _ <- withDB(_.conversations.transaction(_.delete(conversationId)))
      _ <- withDB { db =>
             db.events.transaction { tx =>
               tx.query.filter(_.conversationId === conversationId.value).stream
                 .map(_._id)
                 .evalMap(id => tx.delete(id))
                 .drain
             }
           }
      _ <- withDB { db =>
             db.participantProjections.transaction { tx =>
               tx.query.filter(_.conversationId === conversationId).toList.flatMap { projections =>
                 Task.sequence(projections.map(p => tx.delete(p._id))).unit
               }
             }
           }
      _ <- withDB { db =>
             db.encodedContexts.transaction { tx =>
               tx.query.filter(_.conversationId === conversationId).toList.flatMap { caches =>
                 Task.sequence(caches.map(c => tx.delete(c._id))).unit
               }
             }
           }
      _ <- withDB { db =>
             db.topics.transaction { tx =>
               tx.query.filter(_.conversationId === conversationId).toList.flatMap { topics =>
                 Task.sequence(topics.map(t => tx.delete(t._id))).unit
               }
             }
           }
    } yield ()

  /**
   * Clear a conversation's visible history without deleting the
   * conversation. Sets a `clearedAt` watermark on the
   * [[Conversation]] record; the curator's `framesFor` query honors
   * the watermark by filtering out events at or before it. The
   * events themselves stay in [[sigil.db.SigilDB.events]] for audit
   * — this is a soft clear, not a hard delete.
   *
   * After clearing:
   *   - [[Sigil.framesFor]] returns no frames at-or-before the watermark.
   *   - Per-participant projections (suggested tools, recent tools)
   *     are deleted from `db.participantProjections`.
   *   - Encoded-context caches for the conversation are evicted so
   *     the next turn rebuilds against the post-clear event range.
   *   - New events added after the clear flow through normally.
   *
   * Broadcasts a [[sigil.signal.ConversationCleared]] Notice so
   * live viewers can reset their UI. Apps that need a hard purge
   * (events removed from `db.events`) implement that on top of this
   * — typically by tailing the Notice stream and running a delete
   * pass against the events store.
   */
  def clearConversation(conversationId: Id[Conversation],
                        clearedBy: ParticipantId): Task[Unit] = {
    val now = Timestamp(Nowish())
    withDB(_.conversations.transaction(_.modify(conversationId) {
      case Some(conv) => Task.pure(Some(conv.copy(clearedAt = Some(now), modified = now)))
      case None       => Task.pure(None)
    })).flatMap {
      case None => Task.unit  // no conversation to clear — silent no-op
      case Some(_) =>
        for {
          _ <- withDB { db =>
                 db.participantProjections.transaction { tx =>
                   tx.query.filter(_.conversationId === conversationId).toList.flatMap { projections =>
                     Task.sequence(projections.map(p => tx.delete(p._id))).unit
                   }
                 }
               }
          _ <- withDB { db =>
                 db.encodedContexts.transaction { tx =>
                   tx.query.filter(_.conversationId === conversationId).toList.flatMap { caches =>
                     Task.sequence(caches.map(c => tx.delete(c._id))).unit
                   }
                 }
               }
          _ <- publish(sigil.signal.ConversationCleared(
                 conversationId = conversationId,
                 clearedAt      = now,
                 clearedBy      = clearedBy
               ))
        } yield ()
    }
  }
}
