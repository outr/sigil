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
import sigil.conversation.{
  ActiveSkillSlot, ContextFrame, ContextKey, ContextMemory, ContextSummary, Conversation, EncodedContext, FrameBuilder, MemorySource,
  MemoryStatus, ParticipantProjection, ProgressContext, SkillSource, ToolCallState, Topic, TopicEntry, TopicShiftResult, TurnInput,
  TurnPlan, UpsertMemoryResult
}
import sigil.SpaceId
import sigil.cache.ModelRegistry
import sigil.controller.OpenRouter
import sigil.embedding.{EmbeddingProvider, NoOpEmbeddingProvider}
import sigil.governor.{BudgetDirective, BudgetGovernor, CheckpointIntervention, GovernorContext}
import sigil.governor.{
  DegenerateGenerationGovernor, GovernorVote, OutcomeGovernor, PlainTextReplyGovernor,
  ProgressGovernor, TurnDecisionGovernor, TurnGovernor
}
import sigil.transport.SignalTransport

import java.nio.file.Path
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import sigil.tool.consult.{ConsultTool, TopicClassifierTool}
import sigil.provider.{GenerationSettings, TokenUsage}
import sigil.db.{DefaultSigilDB, Model, SigilDB}
import sigil.dispatcher.{StopFlag, TriggerFilter}
import sigil.event.{
  AgentState, CapabilityResults, Event, EventsPage, Message, MessageRole, MessageVisibility, ModeChange, Stop, ToolInvoke, TopicChange,
  TopicChangeKind
}
import sigil.role.Role
import sigil.orchestrator.{BudgetScope, Directive, Orchestrator}
import sigil.provider.{Complexity, ConversationMode, ConversationRequest, Mode, ProviderStrategy, ReasoningMode, ToolPolicy, WorkType}
import sigil.information.Information
import sigil.participant.{AgentParticipant, AgentParticipantId, DefaultAgentParticipant, Participant, ParticipantId}
import sigil.pipeline.{
  ContentExternalizationTransform, GeocodingEnrichmentEffect, InboundTransform, LocationCaptureTransform, MemoryCacheInvalidationEffect,
  MessageIndexingEffect, RedactLocationTransform, RespondOptionsSelectionFramingTransform, SettledEffect, SignalHub,
  TopicIndexCanonicalizingTransform, ViewerTransform, WorkerConversationAddressingTransform
}
import sigil.render.{ContentRenderer, HtmlRenderer, MarkdownRenderer, PlainTextRenderer, SlackMrkdwnRenderer}
import sigil.provider.Provider
import sigil.provider.{ContextSection, ContextSections, InstructionTier, ModelProfile, PromptShape, Reliability, ResolvedReferences}
import sigil.service.Service
import sigil.signal.{
  AgentActivity, AgentStateDelta, CoreSignals, Delta, EventState, LocationDelta, Notice, ServiceLogSignal, ServiceStatusSignal, Signal,
  ToolDelta, TopicDelta
}
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
 * Projection cluster — everything `publish` derives from a settled
 * signal and everything consumers read back off it. Owns the
 * [[sigil.conversation.ConversationView]] frame append and the
 * per-participant [[sigil.conversation.ParticipantProjection]] writes
 * (skills, extra context, provider response state, read state,
 * reactions), the frame / event read surface with its paging and
 * cleared-at windowing, the encoded-context cache, and the
 * materialized [[Conversation]] projections (mode, topic stack, cost).
 *
 * Mixed into [[Sigil]]; the self-type reaches `withDB`, `publish`,
 * the topic stack projection, and the cost-notice channel.
 */
trait ProjectionOps { this: Sigil =>

  /**
   * If this signal settles a [[ModeChange]] to `Complete`, resolve the
   * Mode-source [[ActiveSkillSlot]] (via [[sigil.provider.Mode.skill]]) and write it into
   * the acting participant's projection on the view.
   */
  final private[sigil] def maybeApplyModeSkill(signal: Signal): Task[Unit] = signal match {
    case mc: ModeChange if mc.state == EventState.Complete => applyModeSkill(mc)
    case d: sigil.signal.Delta =>
      withDB(_.eventsTransaction(d.conversationId)(_.get(d.target.asInstanceOf[Id[Event]]))).flatMap {
        case Some(mc: ModeChange) if mc.state == EventState.Complete => applyModeSkill(mc)
        case _ => Task.unit
      }
    case _ => Task.unit
  }

  final private def applyModeSkill(mc: ModeChange): Task[Unit] =
    updateProjection(mc.conversationId, mc.participantId) { proj =>
      val newModeId = mc.mode.id

      // Step 1 — archive any Discovery slot bound to a different mode,
      // clearing the live Discovery slot. The slot's bound mode is
      // tracked on `discoverySkillMode`, set when `activate_skill`
      // ran. If `discoverySkillMode` is None or already equals the
      // new mode, no archive needed.
      val (archiveMap, clearedDiscovery) = proj.discoverySkillMode match {
        case Some(boundMode) if boundMode != newModeId =>
          val archived = proj.activeSkills.get(SkillSource.Discovery) match {
            case Some(slot) => proj.lastDiscoverySkillByMode + (boundMode -> slot)
            case None => proj.lastDiscoverySkillByMode
          }
          (archived, proj.activeSkills - SkillSource.Discovery)
        case _ => (proj.lastDiscoverySkillByMode, proj.activeSkills)
      }

      // Step 2 — restore an archived slot for the incoming mode (if
      // one was saved on a prior departure).
      val (restoredSkills, restoredDiscoveryMode, prunedArchive) =
        archiveMap.get(newModeId) match {
          case Some(slot) =>
            (clearedDiscovery + (SkillSource.Discovery -> slot), Some(newModeId), archiveMap - newModeId)
          case None =>
            (clearedDiscovery, proj.discoverySkillMode.filter(_ == newModeId), archiveMap)
        }

      // Step 3 — apply the new mode's bundled skill to the Mode slot.
      val withModeSkill = mc.mode.skill match {
        case Some(slot) => restoredSkills + (SkillSource.Mode -> slot)
        case None => restoredSkills - SkillSource.Mode
      }

      proj.copy(
        activeSkills = withModeSkill,
        lastDiscoverySkillByMode = prunedArchive,
        discoverySkillMode = restoredDiscoveryMode
      )
    }

  /**
   * Maintain per-participant [[ParticipantProjection]] state as
   * events/deltas flow through `publish`. Bug #26 — frames live on
   * the events themselves (`Event.contextFrame`), so the publish
   * pipeline only needs to project the participant-side state
   * (recentTools, suggestedTools) here.
   *
   * Updates fire exactly once per source event, the moment the
   * event reaches `EventState.Complete`:
   *
   *   - Atomic Complete events — apply directly.
   *   - Events that start Active and settle later via a Delta —
   *     the Delta branch re-reads the target post-apply.
   */
  final private[sigil] def updateView(signal: Signal): Task[Unit] = signal match {
    case e: Event if e.state == EventState.Complete =>
      applyParticipantProjectionFor(e)
    case d: sigil.signal.Delta =>
      withDB(_.eventsTransaction(d.conversationId)(_.get(d.target.asInstanceOf[Id[Event]]))).flatMap {
        case Some(target) if target.state == EventState.Complete => applyParticipantProjectionFor(target)
        case _ => Task.unit
      }
    case _ => Task.unit
  }

  /**
   * Apply the participant-side projection updates implied by `event`
   * to the relevant [[ParticipantProjection]] record. Runs only on
   * Complete events.
   */
  final private def applyParticipantProjectionFor(event: Event): Task[Unit] =
    event match {
      case ti: ToolInvoke =>
        // Bug #230 — when the tool author declared
        // [[sigil.tool.Tool.suggestedNextTools]], append those
        // tool-names to the per-participant `suggestedTools` overlay
        // so the next turn's "Suggested tools" prompt section advertises
        // them. Additive merge — never clobbers prior find_capability
        // discoveries or pagination navigators; the overlay still
        // single-turn-decays at agent-loop release.
        val nextTools: List[sigil.tool.ToolName] =
          resolvedStaticTools.find(_.name == ti.toolName).map(_.suggestedNextTools).getOrElse(Nil)
        // Record the invocation in the rolling-window cache with a
        // canonical args hash + short preview so the prompt renderer
        // can dedupe by (toolName, argsHash) and warn when the same
        // logical call repeats. Keep the most-recent
        // `recentToolInvocationsLimit` entries; older fall off the
        // tail.
        //
        // A dispatch reaches this path twice — the invoke reaches
        // `Complete` while its outcome is still Pending, then again when
        // the executor's settling delta folds the real outcome on. Both
        // carry the same invoke id, so the second pass updates that entry
        // in place (holding its position) rather than adding a second one.
        // Keyed by invoke, not by (toolName, argsHash): two genuinely
        // distinct calls with identical args stay distinct entries.
        // #354 — a slow tool whose large result raced past the frame
        // settles Complete with a `Pending` outcome (the agent saw a
        // placeholder, not the result). Record `resulted = false` so the
        // duplicate-call cap doesn't count the agent's rational retry of a
        // never-resulted call as a spinning duplicate (which escalated the
        // tier to the ceiling and restricted the tool out of the roster).
        val resulted = ti.outcome != sigil.event.ToolOutcome.Pending
        // #371 — a recoverable Failure marks a tooling-seam result. The
        // duplicate-call cap reads this to avoid escalating a tier on a loop of
        // identical FAILING calls (a stronger model hits the same failure).
        val failed = ti.outcome match {
          case _: sigil.event.ToolOutcome.Failure => true
          case _ => false
        }
        // A dispatch the framework REFUSED settles Pending like a raced one
        // but means the opposite — nothing ran and no result is coming. The
        // marker rides through so the cap counts its own refusals and the
        // raced-reissue redirect skips them.
        val invocation = ti.input match {
          case Some(in) => sigil.conversation.RecentToolInvocation(
              toolName = ti.toolName,
              argsHash = sigil.tool.ToolInputCanonicalizer.argsHash(in),
              argsPreview = sigil.tool.ToolInputCanonicalizer.argsPreview(in),
              invokedAt = ti.timestamp,
              resulted = resulted,
              failed = failed,
              refusal = ti.refusal,
              invokeId = Some(ti._id)
            )
          case None => sigil.conversation.RecentToolInvocation(
              toolName = ti.toolName,
              argsHash = "",
              argsPreview = "",
              invokedAt = ti.timestamp,
              resulted = resulted,
              failed = failed,
              refusal = ti.refusal,
              invokeId = Some(ti._id)
            )
        }
        updateProjection(ti.conversationId, ti.participantId) { proj =>
          val existing = proj.recentToolInvocations.indexWhere(_.invokeId.contains(ti._id))
          val recent =
            if (existing >= 0) proj.recentToolInvocations.updated(existing, invocation)
            else (invocation :: proj.recentToolInvocations).take(recentToolInvocationsLimit)
          val suggested =
            if (nextTools.isEmpty) proj.suggestedTools
            else (proj.suggestedTools ++ nextTools).distinct
          proj.copy(recentToolInvocations = recent, suggestedTools = suggested)
        }
      case cr: CapabilityResults =>
        // the per-loop `find_capability` cache is no longer persisted;
        // `FindCapabilityTool.executeResult` records matches directly
        // onto `TurnContext.discoveredCapabilities` so the cache dies
        // with the agent loop instead of polluting every subsequent turn.
        // The projection update here keeps the `suggestedTools` overlay
        // only — that's a single-turn decay surface that drives the
        // "Suggested tools" prompt section.
        updateProjection(cr.conversationId, cr.participantId) { proj =>
          val toolNames = cr.matches.collect {
            case m if m.capabilityType == sigil.tool.discovery.CapabilityType.Tool => sigil.tool.ToolName.internal(m.name)
          }
          // Sigil #377 / #383 — ACCUMULATE, never replace. The #301 "bounded
          // replace" caused a discovered tool the agent was using (#377) or
          // about to use (#383, create_workflow) to drop out of the roster once
          // a later `find_capability` returned a different match set — and once
          // the per-loop `discoveredCapabilities` cache clears (terminal
          // respond, #6306) this overlay is the only carrier. A discovered
          // capability must stay dispatchable for the rest of the conversation,
          // not just the one search. Bounded by `distinct` + the finite catalog
          // + the curator's own context-limit shedding of the tool roster.
          proj.copy(suggestedTools = (proj.suggestedTools ++ toolNames).distinct)
        }
      case _ => Task.unit
    }

  // -- projection helpers --

  /**
   * Fetch the [[ParticipantProjection]] for `(participantId, conversationId)`,
   * returning an empty seed if one hasn't been materialized yet. Empty
   * projections are NOT persisted — the projection only lands on disk
   * once an event drives an update.
   */
  def projectionFor(participantId: ParticipantId,
                    conversationId: Id[Conversation]): Task[ParticipantProjection] =
    withDB(_.participantProjections.transaction(_.get(ParticipantProjection.idFor(participantId, conversationId)))).map {
      case Some(p) => p
      case None => ParticipantProjection.empty(participantId, conversationId)
    }

  /**
   * Sigil #289 — predicate for cross-conversation reads. The
   * conversation-query tools (`search_conversation`, `reload_content`)
   * call this before dispatching a read against
   * a `conversationId` that differs from the caller's current
   * conversation. Allowed when:
   *   - target == current (same conversation; trivially allowed)
   *   - target == current.parentConversationId (worker reading its
   *     parent)
   *   - target.parentConversationId == current.id (parent reading
   *     one of its workers)
   *
   * Anything else returns `Left(reason)` and the tool surfaces a
   * Failure with the reason text. The predicate is intentionally
   * narrow — it doesn't traverse the whole tree (no grandparents /
   * sibling-conversations). Apps that need wider cross-conversation
   * access override this hook.
   */
  def canReadConversation(currentConversationId: Id[Conversation],
                          targetConversationId: Id[Conversation]): Task[Either[String, Unit]] =
    if (currentConversationId == targetConversationId) Task.pure(Right(()))
    else withDB(_.conversations.transaction { tx =>
      for {
        current <- tx.get(currentConversationId)
        target <- tx.get(targetConversationId)
      } yield (current, target) match {
        case (None, _) =>
          Left(s"current conversation `${currentConversationId.value}` not found")
        case (_, None) =>
          Left(s"target conversation `${targetConversationId.value}` not found")
        case (Some(c), Some(t)) =>
          val isParentOfTarget = t.parentConversationId.contains(c._id)
          val isChildOfTarget = c.parentConversationId.contains(t._id)
          if (isParentOfTarget || isChildOfTarget) Right(())
          else Left(
            s"target conversation `${targetConversationId.value}` is not the current " +
              s"conversation, its parent, or one of its workers — cross-conversation " +
              "reads are limited to parent/worker relationships."
          )
      }
    })

  /**
   * Most-recent [[sigil.event.ToolApproval]] for `(toolName,
   * conversationId)`, or `None` when the agent hasn't recorded a
   * decision yet. The orchestrator reads this before dispatching a
   * `requiresUserConsent` tool; apps can also call directly to
   * surface "is this tool approved in this conversation?" UX.
   */
  def latestToolApproval(toolName: sigil.tool.ToolName,
                         conversationId: Id[Conversation]): Task[Option[sigil.event.ToolApproval]] =
    withDB(_.conversationEventsConsistent(conversationId)).map { events =>
      events.iterator
        .collect { case ta: sigil.event.ToolApproval => ta }
        .filter(_.toolName == toolName)
        .toList
        .sortBy(_.timestamp.value)
        .lastOption
    }

  /**
   * Materialize the rolling-window frames for a conversation by querying
   * `db.events` for Complete events with a non-empty
   * [[Event.contextFrame]], honoring the conversation's `clearedAt`
   * watermark. Returns frames in chronological (timestamp-ascending)
   * order.
   *
   * Bug #26 — replaces the legacy `viewFor` / `view.frames` lookup.
   */
  def framesFor(conversationId: Id[Conversation]): Task[Vector[ContextFrame]] =
    withDB(_.conversations.transaction(_.get(conversationId))).flatMap { convOpt =>
      val watermark = convOpt.flatMap(_.clearedAt).map(_.value).getOrElse(0L)
      withDB(_.conversationEventsConsistent(conversationId)).flatMap { all =>
        val events = all.iterator
          .filter(_.timestamp.value > watermark)
          .filter(_.state == EventState.Complete)
          .toVector
          .sortBy(_.timestamp.value)
        // Self-heal fossilized placeholders: a ToolCall frame rendered
        // while the invoke's outcome was still Pending (`resultPending`)
        // whose row has SINCE settled means the settle-time rewrite was
        // missed — recompute from the row (pure) and persist, so the
        // "result raced past the prompt" marker can never outlive the
        // real result it stood in for.
        val heals = Vector.newBuilder[Event]
        val frames = events.flatMap { ev =>
          ev.contextFrame match {
            case Some(tc: ContextFrame.ToolCall) if tc.resultPending =>
              ev match {
                case ti: ToolInvoke if ti.outcome != sigil.event.ToolOutcome.Pending =>
                  val fresh = FrameBuilder.computeFrame(ti)
                  heals += ti.withContextFrame(fresh)
                  fresh
                case _ => ev.contextFrame
              }
            case other => other
          }
        }
        val healed = heals.result()
        val persistHeals =
          if (healed.isEmpty) Task.unit
          else withDB { db =>
            db.eventsTransaction(conversationId) { tx =>
              healed.foldLeft(Task.unit)((acc, ev) => acc.flatMap(_ => tx.upsert(ev).unit))
                .map(_ => healed.foreach(db.recordRecentEvent))
            }
          }.handleError(_ => Task.unit)
        persistHeals.map(_ => frames)
      }
    }

  /**
   * Canonical paged read of a conversation's event log.
   *
   * The single "load a window of conversation history" API every
   * cold-path reader funnels through — wire replay, conversation
   * switch / scroll-back, the durable event-log adapter. Returns an
   * [[EventsPage]] of [[Event]]s in chronological (oldest-first
   * within the page) order.
   *
   * Paging is message-counted, not event-counted. `maxMessages`
   * caps the number of [[Message]] events on a page; every
   * non-Message event (ToolInvoke, ToolResults, ModeChange,
   * TopicChange, ...) interleaved between those messages is
   * included for free and does NOT consume budget — a chatty turn
   * full of tool calls can't crowd Messages out of the page. With
   * `maxMessages = None` the whole window comes back on `page` 0 in
   * a single uncapped page.
   *
   * Pages run newest-first: `page` 0 is the most recent
   * `maxMessages` messages, `page` 1 the `maxMessages` before that,
   * and so on. Within a page the events are still ordered
   * oldest-first.
   *
   *   - `page` 0 carries the live edge — trailing non-Message
   *     events newer than its newest Message (an in-flight
   *     ToolInvoke / ToolResults pair) — and merges in the
   *     conversation's still-uncommitted batched-iteration events
   *     so a session joining mid-iteration sees the full picture.
   *   - Interior pages (`page` > 0) are strictly the events between
   *     that page's first and last Message — no live-edge merge.
   *
   * `topicId` restricts the result to a single topic. `minTimestamp`
   * and `maxTimestamp` bound the window — both EXCLUSIVE (an event
   * at exactly the bound is not returned), matching the
   * resume-cursor semantics callers rely on. All three filters
   * compose with `maxMessages` and `page`.
   *
   * No viewer / visibility parameter — this is the raw store read.
   * [[MessageVisibility]] filtering belongs to the delivery layer
   * ([[canSee]] / [[viewerTransforms]]); callers that need
   * viewer-scoping apply it to the returned events.
   *
   * `EventsPage.hasMore` is `true` when older messages exist beyond
   * the returned page.
   */
  def eventsFor(conversationId: Id[Conversation],
                page: Int = 0,
                maxMessages: Option[Int] = None,
                topicId: Option[Id[Topic]] = None,
                minTimestamp: Option[Timestamp] = None,
                maxTimestamp: Option[Timestamp] = None,
                /**
                 * When set, the page is scoped to what this viewer may
                 * see: events failing [[canSee]] are dropped BEFORE
                 * pagination (so `maxMessages` counts visible messages)
                 * and [[viewerTransforms]] redact the survivors —
                 * identical to the live wire's `signalsFor` semantics.
                 * `None` returns the raw log: framework internals
                 * (compaction, reconciliation, agent prompt builds)
                 * must see everything. ANY path that delivers history
                 * to a client passes the viewer — an Agents-visibility
                 * checkpoint directive reaching a user UI on a hard
                 * refresh is an information-scope leak, not a
                 * cosmetic one.
                 */
                viewer: Option[ParticipantId] = None): Task[EventsPage] = {
    import lightdb.filter.*
    val safePage = math.max(0, page)

    // In-memory filters applied identically to the committed DB rows
    // and to the batched-scope accumulator so both halves of a page-0
    // merge are narrowed the same way.
    def passesFilters(e: Event): Boolean =
      (topicId.forall(t => e.topicId == t) &&
        minTimestamp.forall(min => e.timestamp.value > min.value) &&
        maxTimestamp.forall(max => e.timestamp.value < max.value)) &&
        viewer.forall(v => canSee(e, v))

    def redactFor(e: Event): Event = viewer match {
      case Some(v) => applyViewerTransforms(e, v) match {
          case ev: Event => ev
          case _ => e
        }
      case None => e
    }

    // Indexed conversation-scoped query, newest-first. The timestamp
    // bounds fold into the same indexed query; `topicId` is not an
    // indexed field, so it is applied in memory over the
    // conversation-narrowed set.
    val committed: Task[List[Event]] = withDB(_.events.transaction { tx =>
      val base = tx.query.filter(_ => Event.conversationId === conversationId.value)
      val lowered = minTimestamp.fold(base)(min => base.filter(_ => Event.timestamp > min.value))
      val bounded = maxTimestamp.fold(lowered)(max => lowered.filter(_ => Event.timestamp < max.value))
      bounded
        .sort(lightdb.Sort.ByField(Event.timestamp, lightdb.SortDirection.Descending))
        .toList
    }).map(_.filter(passesFilters))

    committed.map { committedDesc =>
      // Page 0 merges the conversation's still-uncommitted
      // batched-iteration events — broadcast to the hub already but
      // not yet in the DB. Keyed by id with the accumulator winning,
      // so an event the current iteration both committed-earlier and
      // re-deltaed resolves to its latest version.
      val mergedDesc: List[Event] =
        if (safePage == 0) {
          val inFlight = withDBScopeSnapshot(conversationId).filter(passesFilters)
          if (inFlight.isEmpty) committedDesc
          else {
            val byId = scala.collection.mutable.LinkedHashMap.empty[Id[Event], Event]
            committedDesc.foreach(e => byId.put(e._id, e))
            inFlight.foreach(e => byId.put(e._id, e))
            byId.values.toList.sortBy(-_.timestamp.value)
          }
        } else committedDesc

      eventsForPage(mergedDesc.map(redactFor), safePage, maxMessages)
    }
  }

  /**
   * Snapshot the active batched-event scope's in-flight accumulator
   * for `conversationId`, or an empty list when no [[sigil.db.SigilDB.withBatchedEvents]]
   * scope is open. Read by [[eventsFor]] page 0. Synchronous —
   * `instance` is already resolved by the time any cold-path reader
   * runs, so this never blocks on DB init.
   */
  private def withDBScopeSnapshot(conversationId: Id[Conversation]): List[Event] =
    startedInstance.get() match {
      case Some(inst) => inst.db.batchedEventScope(conversationId).map(_.snapshot).getOrElse(Nil)
      case None => Nil
    }

  /**
   * Carve `page` out of a newest-first event list using
   * message-counted paging.
   *
   *   - `maxMessages = None`: page 0 is the whole list; higher
   *     pages are empty. `hasMore` always false.
   *   - page 0: from the newest event through the `maxMessages`-th
   *     newest Message inclusive (so trailing non-Message events
   *     newer than the newest Message — the live edge — ride
   *     along). `hasMore` when more messages exist past it.
   *   - interior page p: strictly from that page's first (newest)
   *     Message through its last (oldest) Message.
   *
   * Returns the page re-sorted oldest-first.
   */
  private def eventsForPage(desc: List[Event], page: Int, maxMessages: Option[Int]): EventsPage =
    maxMessages match {
      case None =>
        if (page == 0) EventsPage(desc.reverse, hasMore = false)
        else EventsPage(Nil, hasMore = false)

      case Some(cap) if cap <= 0 =>
        EventsPage(Nil, hasMore = false)

      case Some(cap) =>
        // Indices of Message events in the newest-first list.
        val messageIdx: Vector[Int] =
          desc.iterator.zipWithIndex.collect { case (e, i) if e.isInstanceOf[Message] => i }.toVector
        val totalMessages = messageIdx.length
        val firstMsgOrdinal = page * cap
        if (firstMsgOrdinal >= totalMessages) EventsPage(Nil, hasMore = false)
        else {
          val lastMsgOrdinal = math.min(firstMsgOrdinal + cap - 1, totalMessages - 1)
          val sliceEnd = messageIdx(lastMsgOrdinal)
          val sliceStart = if (page == 0) 0 else messageIdx(firstMsgOrdinal)
          val pageDesc = desc.slice(sliceStart, sliceEnd + 1)
          val hasMore = lastMsgOrdinal + 1 < totalMessages
          EventsPage(pageDesc.reverse, hasMore = hasMore)
        }
    }

  /**
   * Monotonically advance a conversation's `clearedAt` watermark.
   * The curator's stage-3 shed calls this after it summarises the
   * oldest frames into a `ContextSummary`: the watermark moves past
   * the shed slice, the next turn's `framesFor` filters those frames
   * out, and the summary takes their place via `summariesFor` —
   * "compress once, recall many" actually amortises.
   *
   * Monotonicity is enforced inside the transactional modify: the
   * watermark only advances. A concurrent caller racing with a
   * smaller `at` is a no-op on the persisted row. Events stay in
   * `db.events` for `SearchConversationTool` recall; this only
   * filters the curator's rolling-window input. Bug #147.
   */
  def advanceClearedAt(conversationId: Id[Conversation], at: Timestamp): Task[Unit] =
    withDB(_.conversationEventsConsistent(conversationId)).flatMap { evs =>
      // #316 — a budget shed may never advance the watermark to or past
      // the current user task. `framesFor` keeps frames with
      // `timestamp > clearedAt`, so capping strictly below the most-
      // recent user-authored Standard Message makes it structurally
      // impossible for a shed to permanently filter the active task out
      // of context — regardless of any shed-logic bug. Old history
      // before the task still sheds (recoverable via search_conversation,
      // or by reading the overflow file a bounded result named).
      // Explicit conversation-clear sets `clearedAt`
      // directly and is intentionally unaffected by this cap.
      val taskTs: Option[Long] = evs.iterator.collect {
        case m: sigil.event.Message
            if m.role == sigil.event.MessageRole.Standard
              && !m.participantId.isInstanceOf[sigil.participant.AgentParticipantId] => m.timestamp.value
      }.maxOption
      val capped = taskTs match {
        case Some(t) if at.value >= t => Timestamp(t - 1)
        case _ => at
      }
      withDB(_.conversations.transaction(_.modify(conversationId) {
        case Some(conv) =>
          val current = conv.clearedAt.map(_.value).getOrElse(0L)
          if (capped.value <= current) Task.pure(Some(conv))
          else Task.pure(Some(conv.copy(clearedAt = Some(capped), modified = Timestamp(Nowish()))))
        case None => Task.pure(None)
      })).unit
    }

  /**
   * Fetch the [[EncodedContext]] cache row for this `(agentId,
   * conversationId, modelId)` triple, returning a fresh empty row if
   * none exists. Bug #26 — the curator looks this up per turn,
   * appends since-cursor frames via the active provider's
   * [[sigil.provider.Provider.appendFrame]], and persists the result.
   * Cache misses (no row, or `builtThrough` behind newest event id)
   * trigger an incremental rebuild.
   *
   * The cache shape is opaque to the framework — only the provider
   * that wrote the bytes understands them. Cross-model mixing is
   * structurally impossible because `modelId` is part of the cache
   * key.
   */
  def encodedContextFor(agentId: ParticipantId,
                        conversationId: Id[Conversation],
                        modelId: Id[Model]): Task[EncodedContext] =
    withDB(_.encodedContexts.transaction(_.get(EncodedContext.idFor(agentId, conversationId, modelId)))).map {
      case Some(c) => c
      case None => EncodedContext.empty(agentId, conversationId, modelId)
    }

  /**
   * Persist (or upsert) an [[EncodedContext]] cache row. Returns the
   * stored record (with `modified` and `lastAccessedAt` bumped to
   * `now()`). Apps that drive their own cache flows call this after
   * incrementally appending; the framework's curator does so
   * automatically.
   */
  def saveEncodedContext(cache: EncodedContext): Task[EncodedContext] = {
    val now = Timestamp(Nowish())
    val updated = cache.copy(modified = now, lastAccessedAt = now)
    withDB(_.encodedContexts.transaction(_.upsert(updated)))
  }

  /**
   * Update a participant's [[ParticipantProjection]] in the projections
   * collection. Creates a fresh empty projection (with the deterministic
   * derived id) if none exists. Use from curators, tools, or any app
   * code that needs to mutate per-participant projection state.
   *
   * Sigil #291 — when `broadcast = true` (default), publishes a
   * [[sigil.signal.ParticipantProjectionUpdated]] Notice after the
   * write commits so multi-client UIs subscribed to this conversation
   * see the change without polling. Framework-internal cache writes
   * that no UI cares about (e.g. cached `previous_response_id`) pass
   * `broadcast = false`. App writes through the convenience methods
   * ([[setParticipantContext]], [[activateSkill]], etc.) broadcast
   * by default — the polling workaround that motivated this signal.
   */
  def updateProjection(conversationId: Id[Conversation],
                       participantId: ParticipantId,
                       broadcast: Boolean = true)(f: ParticipantProjection => ParticipantProjection): Task[Unit] =
    withDB(_.participantProjections.transaction(_.modify(ParticipantProjection.idFor(participantId, conversationId)) {
      case Some(proj) =>
        Task.pure(Some(f(proj).copy(modified = Timestamp(Nowish()))))
      case None =>
        Task.pure(Some(f(ParticipantProjection.empty(participantId, conversationId))
          .copy(modified = Timestamp(Nowish()))))
    })).flatMap {
      case Some(updated) if broadcast =>
        publish(sigil.signal.ParticipantProjectionUpdated(conversationId, participantId, updated))
      case _ => Task.unit
    }

  /**
   * Convenience: set (or replace) a skill slot for a participant. Discovery
   * and User sources are driven through here by tools that want to activate
   * a skill; Mode-source slots are maintained by the framework via
   * [[sigil.provider.Mode.skill]] on `ModeChange`.
   */
  def activateSkill(conversationId: Id[Conversation],
                    participantId: ParticipantId,
                    source: SkillSource,
                    slot: ActiveSkillSlot): Task[Unit] =
    updateProjection(conversationId, participantId)(proj => proj.copy(activeSkills = proj.activeSkills + (source -> slot)))

  /**
   * Convenience: clear a skill slot for a participant (if present).
   */
  def clearSkill(conversationId: Id[Conversation],
                 participantId: ParticipantId,
                 source: SkillSource): Task[Unit] =
    updateProjection(conversationId, participantId)(proj => proj.copy(activeSkills = proj.activeSkills - source))

  /**
   * Convenience: set a single key/value on a participant's
   * `extraContext`. Same key replaces.
   */
  def setParticipantContext(conversationId: Id[Conversation],
                            participantId: ParticipantId,
                            key: ContextKey,
                            value: String): Task[Unit] =
    updateProjection(conversationId, participantId)(proj => proj.copy(extraContext = proj.extraContext + (key -> value)))

  /**
   * Convenience: remove a key from a participant's `extraContext`.
   */
  def clearParticipantContext(conversationId: Id[Conversation],
                              participantId: ParticipantId,
                              key: ContextKey): Task[Unit] =
    updateProjection(conversationId, participantId)(proj => proj.copy(extraContext = proj.extraContext - key))

  /**
   * Cache a provider's per-(agent, conversation) server-side state
   * handle. OpenAI's Responses API populates this on every settle so
   * the next turn can pass `previous_response_id` and ship only the
   * delta input. `messageCount` is the rendered-message count at the
   * time of capture — the next call trims that many from the head
   * before sending.
   */
  def setProviderResponseState(conversationId: Id[Conversation],
                               participantId: ParticipantId,
                               responseId: String,
                               messageCount: Int): Task[Unit] =
    // Sigil #291 — framework-internal provider-cache write; no UI
    // surface mirrors this state, so suppress the broadcast.
    updateProjection(conversationId, participantId, broadcast = false)(proj =>
      proj.copy(
        latestProviderResponseId = Some(responseId),
        latestProviderResponseMessageCount = Some(messageCount)
      ))

  /**
   * Forget the cached provider response state for an (agent, conversation)
   * pair. Fires when the upstream API rejects `previous_response_id`
   * (`previous_response_not_found` — the id expired). Next turn falls
   * back to the full-transcript request shape.
   */
  def clearProviderResponseState(conversationId: Id[Conversation],
                                 participantId: ParticipantId): Task[Unit] =
    // Sigil #291 — framework-internal cache invalidation; suppress broadcast.
    updateProjection(conversationId, participantId, broadcast = false)(proj =>
      proj.copy(
        latestProviderResponseId = None,
        latestProviderResponseMessageCount = None
      ))

  /**
   * Convenience: advance a participant's last-read cursor in
   * `conversationId` to a specific event's server-stamped
   * timestamp. The framework looks up the event's authoritative
   * timestamp — clients never specify a wall-clock time, so
   * client-clock drift is moot. Bug #62.
   *
   * No-op when `readThrough` doesn't resolve (stale id, deleted
   * event). Idempotent: calling twice with the same id is
   * cheap.
   */
  def markRead(conversationId: Id[Conversation],
               participantId: ParticipantId,
               readThrough: Id[sigil.event.Event]): Task[Unit] =
    withDB(_.eventsTransaction(conversationId)(_.get(readThrough))).flatMap {
      case None => Task.unit
      case Some(e) => markRead(conversationId, participantId, e.timestamp)
    }

  /**
   * Direct-timestamp overload for the rare case where a caller
   * already has a server-authoritative `Timestamp` in hand
   * (replay tooling, batch catch-up scripts, etc.). Most code
   * should use the event-id overload above — that's the path
   * that's safe against client clock drift. Bug #62.
   */
  def markRead(conversationId: Id[Conversation],
               participantId: ParticipantId,
               lastReadAt: lightdb.time.Timestamp): Task[Unit] = {
    val stateId = sigil.event.ReadState.idFor(conversationId, participantId)
    withDB(_.eventsTransaction(conversationId)(_.get(stateId))).flatMap {
      case Some(_) =>
        publish(sigil.signal.ReadStateDelta(
          target = stateId,
          conversationId = conversationId,
          participantId = participantId,
          lastReadAt = lastReadAt
        ))
      case None =>
        // First read for this `(conv, participant)` — insert the
        // ReadState row. Subsequent advances mutate via
        // ReadStateDelta (no new event row).
        withDB(_.conversations.transaction(_.get(conversationId))).flatMap {
          case None => Task.unit
          case Some(conv) =>
            publish(sigil.event.ReadState(
              participantId = participantId,
              conversationId = conversationId,
              topicId = conv.currentTopicId,
              lastReadAt = lastReadAt,
              _id = stateId
            ))
        }
    }
  }

  /**
   * Read the current `lastReadAt` cursor for `(conversationId,
   * participantId)`. `None` if the participant has never marked
   * read in this conversation. Bug #62.
   */
  def readStateFor(conversationId: Id[Conversation],
                   participantId: ParticipantId): Task[Option[sigil.event.ReadState]] = {
    val stateId = sigil.event.ReadState.idFor(conversationId, participantId)
    withDB(_.eventsTransaction(conversationId)(_.get(stateId))).map {
      case Some(r: sigil.event.ReadState) => Some(r)
      case _ => None
    }
  }

  /**
   * Convenience: publish a [[sigil.event.Reaction]] event for the
   * given message. `removed = false` means "I'm reacting now",
   * `removed = true` means "I'm taking my reaction back." Last-
   * write-wins per `(messageId, participantId, emoji)` — consumers
   * reduce the event tail to find the current state.
   *
   * No-ops if the conversation isn't found (caller's `conversationId`
   * was stale). Bug #61.
   */
  def react(conversationId: Id[Conversation],
            participantId: ParticipantId,
            messageId: Id[sigil.event.Event],
            emoji: String,
            removed: Boolean = false): Task[Unit] =
    withDB(_.conversations.transaction(_.get(conversationId))).flatMap {
      case None => Task.unit
      case Some(conv) =>
        publish(sigil.event.Reaction(
          participantId = participantId,
          conversationId = conversationId,
          topicId = conv.currentTopicId,
          messageId = messageId,
          emoji = emoji,
          removed = removed
        ))
    }

  /**
   * Convenience: publish a [[Stop]] event for the conversation. Lets
   * UI layers (stop button) and programmatic callers issue stops
   * without reconstructing the event by hand. For LLM-initiated stops
   * use [[sigil.tool.core.CancelTool]] instead.
   */
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

  /**
   * Maintain materialized projections on the [[Conversation]] record:
   *   - `currentMode` tracks the latest [[ModeChange]]
   *   - `topics` (the navigation stack) tracks the latest [[TopicChange]]:
   *     - `Switch` either pushes a new entry (if the topic isn't on the
   *       stack) or truncates the stack back to that entry (if it is —
   *       the natural "return to prior topic" flow)
   *     - `Rename` mutates the active entry's label + summary in place
   *   - `cost` is incremented when a [[Message]] settles whose
   *     `modelId` resolves to a known [[Model]] in
   *     [[sigil.cache.ModelRegistry]] (USD; per-token pricing
   *     multiplied by [[sigil.provider.TokenUsage]]). Each non-zero
   *     increment publishes a [[sigil.signal.ConversationCostUpdated]]
   *     Notice with the new total + per-Message delta.
   *
   * Fires only on the SETTLE (an Event already at `Complete`, or a
   * `Delta` that transitions its target to `Complete`), never on the
   * initial Active pulse — so these projection fields are written
   * exactly once per transition even though each change flows through
   * `publish` twice (event + state delta).
   */
  final private[sigil] def updateConversationProjection(signal: Signal): Task[Unit] = {
    val settled: Task[Option[Event]] = signal match {
      case e: Event if e.state == EventState.Complete => Task.pure(Some(e))
      case d: sigil.signal.Delta =>
        withDB(_.eventsTransaction(d.conversationId)(_.get(d.target.asInstanceOf[Id[Event]])))
          .map(_.filter(_.state == EventState.Complete))
      case _ => Task.pure(None)
    }
    settled.flatMap {
      case Some(mc: ModeChange) =>
        withDB(_.conversations.transaction(_.modify(mc.conversationId) {
          case Some(conv) if conv.currentMode != mc.mode =>
            Task.pure(Some(conv.copy(currentMode = mc.mode, modified = Timestamp(Nowish()))))
          case Some(conv) => Task.pure(Some(conv))
          case None => Task.pure(None)
        })).unit
      case Some(cc: sigil.event.ComplexityChange) =>
        // The pin/unpin tools mutate `pinnedComplexity` themselves;
        // this projection arm keeps the event the source of truth so
        // future emitters (e.g. classifier-driven auto-escalation)
        // flow through the same path without duplicating the
        // conversation modify.
        withDB(_.conversations.transaction(_.modify(cc.conversationId) {
          case Some(conv) if conv.pinnedComplexity != cc.newTier =>
            Task.pure(Some(conv.copy(pinnedComplexity = cc.newTier, modified = Timestamp(Nowish()))))
          case Some(conv) => Task.pure(Some(conv))
          case None => Task.pure(None)
        })).unit
      case Some(tc: TopicChange) =>
        applyTopicChangeToStack(tc)
      case Some(m: Message) =>
        applyEventCostToConversation(m.conversationId, m._id, m.participantId, m.modelId, m.usage)
      case Some(t: ToolInvoke) =>
        // For tool-call-only turns (change_mode, cancel, find_capability,
        // …) the per-turn usage attaches to the ToolInvoke via
        // [[sigil.signal.ToolDelta]]. Cost projection picks it up off
        // the same `modelId × usage` pair that drives the Message path.
        applyEventCostToConversation(t.conversationId, t._id, t.participantId, t.modelId, t.usage)
      case _ => Task.unit
    }
  }

  /**
   * Increment [[Conversation.cost]] for a settled cost-bearing event
   * (either a [[Message]] or a [[ToolInvoke]]) whose `modelId` is
   * known to the [[sigil.cache.ModelRegistry]].
   *
   * Math: per-token pricing × token counts (USD). Cache miss,
   * `modelId = None`, or zero usage → no-op. On a non-zero delta,
   * publishes a [[sigil.signal.ConversationCostUpdated]] Notice
   * carrying the new running total + the per-event delta.
   */
  /**
   * Sigil #406 — per-turn cost telemetry seam. Called once per charged turn at
   * the point the framework folds the turn's USD `cost` into
   * `Conversation.cost`, for BOTH `Message` and tool-call-only `ToolInvoke`
   * turns. The framework has already resolved the model, computed the delta
   * cost from `Model.pricing` × `usage`, and knows the acting participant and
   * active mode — [[TurnCost]] bundles them so a consumer can persist its own
   * cost-ledger row (itemized by model · mode · participant, project-to-date)
   * without reconstructing any of it or patching the cost pipeline.
   *
   * Default no-op. Runs best-effort — a consumer's failure here is logged and
   * does not break the cost projection. The transient
   * [[sigil.signal.ConversationCostUpdated]] Notice still fires as the live
   * "cost changed" trigger; this hook is the DURABLE seam (the Notice isn't
   * persisted / replayed).
   */
  def onTurnCost(entry: TurnCost): Task[Unit] = Task.unit

  final private def applyEventCostToConversation(
    conversationId: Id[Conversation],
    eventId: Id[Event],
    participantId: ParticipantId,
    modelId: Option[Id[Model]],
    usage: TokenUsage
  ): Task[Unit] = {
    // Bug #91 — `findTolerant` lets an event stamped with a bare id
    // (`gpt-5.5`) match a registry entry indexed by its prefixed id
    // (`openai/gpt-5.5`). Without it, every cost projection on a
    // bare-id event silently misses and the conversation's running
    // total stays at zero.
    // Cache-thrash canary. A healthy multi-turn conversation reads its cached
    // prefix far more than it writes it; sustained cache_creation ≫ cache_read
    // means the cached prefix is unstable across turns (e.g. a breakpoint that
    // moves every turn, or churning content early in the request) and the whole
    // history is being re-created — the dominant cost/latency failure mode.
    // Warn loudly so a regression surfaces in the logs instead of only in a
    // monthly bill. Thresholded so normal first-turn cache fills stay quiet.
    if (usage.cacheCreationTokens > 50000 && usage.cacheCreationTokens > usage.cacheReadTokens * 4)
      scribe.warn(s"[cache-thrash] cache_creation=${usage.cacheCreationTokens} ≫ " +
        s"cache_read=${usage.cacheReadTokens} (model=${modelId.map(_.value).getOrElse("?")}); " +
        "the cached prefix looks unstable across turns — prompt caching is not amortizing.")
    // Capture the resolved model id alongside the delta so the #406 telemetry
    // reports what the turn actually ran on (registry-canonical when known).
    val chargeOpt: Option[(Id[Model], BigDecimal)] = modelId.flatMap { mid =>
      cache.findTolerant(mid).map(model => cache.canonicalIdFor(mid) -> Sigil.costFor(model.pricing, usage))
    }.filter(_._2 > 0)
    chargeOpt match {
      case None => Task.unit
      case Some((resolvedModelId, delta)) =>
        // Mirror the charge onto the live turn's spend accumulator so
        // the budget gate reads per-turn cost without a store query.
        // Cost-bearing events carry the acting agent's participantId,
        // which keys the claim registry.
        participantId match {
          case agentId: sigil.participant.AgentParticipantId =>
            Option(activeClaims.get(agentStateLockId(agentId, conversationId))).foreach(_.addTurnCost(delta))
          case _ => ()
        }
        withDB(_.conversations.transaction(_.modify(conversationId) {
          case None => Task.pure(None)
          case Some(conv) =>
            Task.pure(Some(conv.copy(cost = conv.cost + delta, modified = Timestamp(Nowish()))))
        })).flatMap {
          case Some(updated) =>
            publish(sigil.signal.ConversationCostUpdated(
              conversationId = updated._id,
              cost = updated.cost,
              delta = delta
            )).flatMap { _ =>
              // Sigil #406 — hand the fully-attributed turn cost to the app's
              // ledger seam. Best-effort: a consumer hiccup mustn't break the
              // cost projection.
              onTurnCost(TurnCost(
                conversationId = updated._id,
                eventId = eventId,
                participantId = participantId,
                modelId = resolvedModelId,
                mode = updated.currentMode.name,
                cost = delta,
                usage = usage,
                timestamp = Timestamp(Nowish())
              )).handleError(t => Task(scribe.warn(s"onTurnCost hook failed for ${updated._id.value}: ${t.getMessage}")))
            }
          case None => Task.unit
        }
    }
  }
}
