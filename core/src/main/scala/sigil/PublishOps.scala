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
 * Signal-pipeline cluster — [[Sigil.publish]] and everything it fans
 * into. Owns the viewer-scope predicates and per-viewer transform
 * fold, the [[SignalHub]] broadcast surface (`signals`, `signalsFor`,
 * eager broadcast), the inbound-transform / settled-effect legs, the
 * historical-import publish paths, the targeted `publishTo` channel,
 * the framework-workflow lifecycle notices, and the inbound Notice
 * dispatcher.
 *
 * Mixed into [[Sigil]]; the self-type reaches `withDB`, the
 * projection updates, the agent fan-out, and the stop dispatch the
 * pipeline drives in order.
 */
trait PublishOps { this: Sigil =>

  /**
   * Fold a signal through [[viewerTransforms]] in declaration order,
   * returning the version a specific viewer should see. Apps that
   * consume [[signals]] directly and fan out to subscribers call this
   * per (signal, viewer) pair to apply redaction/filtering.
   */
  final def applyViewerTransforms(signal: Signal, viewer: ParticipantId): Signal =
    viewerTransforms.foldLeft(signal)((s, t) => t.apply(s, viewer, this))

  /**
   * Hard scope predicate — does `viewer` get to see `signal` at all?
   * Default consults [[Event.visibility]] when the signal is an
   * [[Event]] (Deltas always pass through; client logic must ignore
   * deltas whose target event was filtered). Apps override for
   * custom scope rules (per-tenant, per-permission-grant, etc.).
   *
   * Resolution policy for the default `MessageVisibility` cases:
   *   - `All` — pass.
   *   - `Agents` — pass iff `viewer.isInstanceOf[AgentParticipantId]`.
   *   - `Users` — pass iff viewer is NOT an `AgentParticipantId`.
   *   - `Participants(ids)` — pass iff `ids.contains(viewer)`.
   *
   * Called twice per signal: once in `signalsFor(viewer)` for wire
   * delivery, once on each [[sigil.conversation.ContextFrame]]'s
   * source visibility in `buildContext` for prompt-building.
   */
  def canSee(signal: Signal, viewer: ParticipantId): Boolean = signal match {
    case e: Event => visibilityAllows(e.visibility, viewer)
    case _        => true
  }

  /**
   * Same predicate, applied directly to a denormalized
   * [[MessageVisibility]] (e.g. on a [[sigil.conversation.ContextFrame]]).
   * Apps that override [[canSee]] should usually override this too if
   * their custom logic depends on the signal payload — the default
   * delegates straight to the visibility tag.
   */
  def visibilityAllows(visibility: MessageVisibility, viewer: ParticipantId): Boolean =
    visibility match {
      case MessageVisibility.All               => true
      case MessageVisibility.Agents            => viewer.isInstanceOf[AgentParticipantId]
      case MessageVisibility.Users             => !viewer.isInstanceOf[AgentParticipantId]
      case MessageVisibility.Participants(ids) => ids.contains(viewer)
    }

  // -- broadcast stream --

  /** Per-subscriber queue capacity for the [[SignalHub]]. Every
    * `sigil.signals` / `signalsFor(viewer)` subscriber gets its own
    * bounded queue of this size; when one fills, the hub sheds the
    * oldest transient signal (a `Delta` / `Notice`) rather than block
    * the publisher. The default ([[SignalHub.DefaultCapacity]]) is
    * sized for always-on reasoning-model streams; apps with even
    * heavier signal volume — or many slow wire subscribers — raise it. */
  def signalHubCapacity: Int = SignalHub.DefaultCapacity

  /** Multicast dispatcher populated by [[publish]]. One per Sigil
    * instance; initialized lazily so initialization order is safe. */
  private[sigil] final lazy val hub: SignalHub = new SignalHub(signalHubCapacity)

  /** #317 — event ids whose live broadcast already happened eagerly
    * (via [[broadcastEager]]) ahead of the per-iteration batch drain.
    * [[publish]] consults this set at its hub-emit step and SKIPS the
    * re-broadcast for a matching event id (removing it), so the event
    * is broadcast exactly once even though it still flows through the
    * full publish pipeline for durable persist + projection. */
  private final val eagerlyBroadcastEventIds: java.util.Set[Id[Event]] =
    java.util.concurrent.ConcurrentHashMap.newKeySet[Id[Event]]()

  /**
   * #317 — broadcast `event` to live [[signals]] / [[signalsFor]]
   * subscribers immediately, ahead of the per-iteration batch drain
   * (and ahead of any blocking tool execution that drain would
   * otherwise gate the broadcast behind).
   *
   * Records the event id so the subsequent [[publish]] of the same
   * event — which still runs the full pipeline (durable insert,
   * projection, fan-out, settled effects) — suppresses its own
   * hub re-emit. The event is therefore broadcast exactly once and
   * persisted exactly once; only the broadcast *timing* moves
   * forward.
   *
   * Inbound transforms run here so the eagerly-broadcast shape
   * matches what `publish` would have emitted; `_id` is stable
   * across transforms, so the suppression key holds.
   */
  final def broadcastEager(event: Event): Task[Unit] =
    applyInboundTransforms(event).map {
      case resolved: Event =>
        eagerlyBroadcastEventIds.add(resolved._id)
        hub.emit(resolved)
        ()
      case _ => ()
    }

  /**
   * Broadcast-level stream of every signal that has completed its
   * publish pipeline (transforms applied, persisted, projections
   * updated, settled effects fired). Each call returns a new
   * subscriber — slow subscribers drop oldest on overflow and don't
   * block peers.
   *
   * Signals are emitted unchanged — viewer-dependent transforms are
   * NOT applied. Subscribers that need per-viewer redaction should
   * consume [[signalsFor]] instead.
   *
   * The hub is not a replay log; late subscribers see only signals
   * emitted after they subscribe. Durable history lives in the events
   * store.
   */
  final def signals: Stream[Signal] = hub.subscribe

  /** Per-viewer broadcast subscription that ALSO receives signals
    * emitted via [[publishTo]] targeted at this viewer. Used internally
    * by [[signalsFor]] and by transports that want full per-viewer
    * delivery (broadcasts + targeted Notices). */
  private[sigil] final def signalsViewerScoped(viewer: ParticipantId): Stream[Signal] =
    hub.subscribeFor(viewer)

  /**
   * Per-viewer stream derived from [[signals]]. Each signal is first
   * tested against [[canSee]] (drops `MessageVisibility.Agents`
   * messages for non-agent viewers, etc.); survivors are folded
   * through [[viewerTransforms]] for redaction. Wire transports
   * subscribe to one of these per connected client — the returned
   * stream is already filtered and redacted, so the app does not
   * have to call `canSee` / `applyViewerTransforms` itself.
   *
   * Deltas always pass `canSee`; client UIs must ignore deltas
   * whose target event was filtered out by visibility.
   */
  final def signalsFor(viewer: ParticipantId): Stream[Signal] =
    signalsViewerScoped(viewer).flatMap { s =>
      if (canSee(s, viewer)) Stream.emit(applyViewerTransforms(s, viewer))
      else Stream.empty
    }

  // -- framework dispatch (entry point) --

  /**
   * Inject a [[Signal]] into the framework. The single pipeline every
   * signal passes through, on the way in from outside or back out from
   * an agent's own turn. In order:
   *
   *   1. Apply [[inboundTransforms]] (e.g. `LocationCaptureTransform`).
   *   2. Persist via `SigilDB.apply` (insert Event / apply Delta).
   *   3. Update materialized projections on [[Conversation]]
   *      (`currentMode`, `currentTopicId`) for Mode/Topic changes.
   *   4. Append a frame to the conversation's [[ConversationView]] when
   *      an event settles Complete (via `FrameBuilder`).
   *   5. Resolve and apply the Mode-source skill slot on `ModeChange`.
   *   6. Dispatch control signals — a [[Stop]] event updates the
   *      matching agent's [[sigil.dispatcher.StopFlag]] so the agent's
   *      next iteration check (or in-flight `takeWhile`) exits.
   *   7. Emit to the [[SignalHub]] for subscribers of [[signals]] /
   *      [[signalsFor]].
   *   8. Fan out to participants whose [[TriggerFilter]] matches.
   *   9. Run [[settledEffects]] (e.g. vector indexing, geocoding).
   *
   * Apps don't override this — it's the framework's pipeline.
   */
  final def publish(signal: Signal): Task[Unit] = signal match {
    case n: sigil.signal.Notice =>
      // Notices are transient pulses — no persist, no projection
      // updates, no fan-out. Inbound transforms still run (apps may
      // want to redact / annotate). Then broadcast through the hub
      // and dispatch to handleNotice.
      applyInboundTransforms(n).flatMap { resolved =>
        Task { updateServiceStatusCache(resolved); hub.emit(resolved); () }
      }
    case _ =>
      validateEventInvariants(signal).flatMap { _ =>
      applyInboundTransforms(signal).flatMap { resolved =>
        for {
          written <- withDB(_.write(resolved, inlineContextFrame))
          // Feed the founding-turn topic sweep's per-claim event log
          // (no-op when no claim is live for the conversation).
          _ <- Task {
                 resolved match {
                   case e: Event => recordTurnEvent(e)
                   case _        => ()
                 }
               }
          // A settling ToolDelta reaching the durable pipeline releases
          // the invoke's in-flight-dispatch registration — the stop
          // path's out-of-band settle no longer owns it.
          _ <- Task {
                 resolved match {
                   case td: ToolDelta if td.state.contains(EventState.Complete) =>
                     inflightToolDispatches.remove(td.target)
                     ()
                   case _ => ()
                 }
               }
          _ <- settlePairedToolFrame(written)
          _ <- updateConversationProjection(resolved)
          _ <- updateView(resolved)
          _ <- maybeApplyModeSkill(resolved)
          _ <- applyStop(resolved)
          // #317 — skip the re-broadcast when this event was already
          // broadcast eagerly at tool-start; persist + projection above
          // still ran, so the event stays durable + projected exactly once.
          _ <- Task {
                 val alreadyBroadcast = resolved match {
                   case e: Event => eagerlyBroadcastEventIds.remove(e._id)
                   case _        => false
                 }
                 if (!alreadyBroadcast) hub.emit(resolved)
                 ()
               }
          _ <- resolved match {
                 case e: Event => fanOut(e)
                 case _: sigil.signal.Delta => Task.unit
                 case _: sigil.signal.Notice => Task.unit  // unreachable here, exhaustive
               }
          _ <- applySettledEffects(resolved)
        } yield ()
      }
      }
  }

  /** Validate Event invariants before persistence. Bug #64 —
    * fail-loud at the write boundary so a malformed event never
    * reaches the DB (and never poisons subsequent reads via
    * `FrameBuilder`'s recovery path). The Throwable's stack
    * trace points directly at the caller that bypassed the
    * invariant — diagnostically useful in a way the read-side
    * throw never was.
    *
    * Currently checks: every `MessageRole.Tool` event must
    * carry `origin` pointing to its parent ToolInvoke. Apps
    * with custom Event subtypes can extend the validation by
    * overriding this hook. */
  protected def validateEventInvariants(signal: Signal): Task[Unit] = signal match {
    case e: sigil.event.Event
      if e.role == sigil.event.MessageRole.Tool && e.origin.isEmpty =>
      Task.error(new IllegalStateException(
        s"Refusing to publish ${e.getClass.getSimpleName} with role=Tool but no `origin`. " +
          s"Every Tool-role event MUST carry `origin` pointing to its parent ToolInvoke. " +
          s"Event id=${e._id.value}; participantId=${e.participantId.value}; " +
          s"conversationId=${e.conversationId.value}. " +
          s"Caller stack trace identifies the emission site that bypassed origin-stamping."
      ))
    case _ => Task.unit
  }

  /** Compute an Event's [[sigil.conversation.ContextFrame]] and inline it on the row about to be
    * persisted. Handed to `SigilDB.write` so the frame lands in the write that persists the
    * event; reading the row back and rewriting it instead costs a second full row write whose
    * search-index commit dominates everything else the publish path does.
    *
    * `computeFrame` is a pure function of the event's current state, so recomputing on every
    * write keeps the inlined frame honest as that state moves on — a ToolDelta folding `output` /
    * `outcome` onto a ToolInvoke flips its frame from Active to Complete, a settling MessageDelta
    * replaces a streaming Message's content. Non-Complete events and well-formed Tool-role events
    * project to no frame of their own; the latter settle their parent invoke's frame instead, via
    * [[settlePairedToolFrame]].
    *
    * Inlining is the source-of-truth path for prompt construction: the curator queries
    * `event.contextFrame.isDefined` against `db.events` rather than walking a separate frames
    * projection. */
  private final def inlineContextFrame(event: Event): Event =
    FrameBuilder.computeFrame(event) match {
      case Some(frame) if !event.contextFrame.contains(frame) => event.withContextFrame(Some(frame))
      case _                                                  => event
    }

  /** Fold a settled Tool-role event's payload into the inlined `ContextFrame.ToolCall` of the
    * ToolInvoke it names as `origin`, so the projection carries the whole tool transaction in one
    * frame and pair adjacency on the wire holds by construction regardless of what interleaved
    * between the invoke and its result.
    *
    * Fires on ANY Tool-role event carrying an `origin`, not just `ToolResults`: the orchestrator
    * surfaces tool-input parse failures as a Tool-role `Message` (`disposition = Failure`), the
    * same shape `settleOrphanToolInvoke` emits for stream-abort orphans. Narrowing to
    * `ToolResults` leaves those unpaired and strands the invoke Active.
    *
    * Takes the row `SigilDB.write` actually persisted — the inserted Event, or a Delta's
    * post-application Event — so nothing has to be read back to decide whether there is pairing
    * work to do. */
  private final def settlePairedToolFrame(written: Option[Event]): Task[Unit] = written match {
    case Some(event) if event.state == EventState.Complete && event.role == MessageRole.Tool =>
      event.origin match {
        case None => Task.unit
        case Some(invokeId) =>
          withDB(_.eventsTransaction(event.conversationId) { tx =>
            tx.get(invokeId).flatMap {
              case Some(ti: ToolInvoke) =>
                ti.contextFrame match {
                  // Settle the invoke's frame from its paired Tool-role result under the shared
                  // pairing rule ([[FrameBuilder.settlesPairedCall]]). The pending clause covers
                  // the refuse paths (duplicate-call cap, tool-fan-out cap, …) whose
                  // `toolDeltaPrefix` already flipped the frame to Complete with the "raced past"
                  // placeholder before the paired Failure Message arrives — without this that
                  // placeholder stuck and told the agent to retry a call the framework
                  // deliberately refused.
                  case Some(tc: ContextFrame.ToolCall)
                      if FrameBuilder.settlesPairedCall(tc, Some(ti.outcome)) =>
                    tx.upsert(ti.withContextFrame(Some(FrameBuilder.settledPairedFrame(tc, event)))).unit
                  case _ => Task.unit
                }
              case _ => Task.unit
            }
          })
      }
    case _ => Task.unit
  }

  /**
   * Bulk-import historical events into a conversation. Persists +
   * projects the batch, then emits a single
   * [[sigil.signal.ConversationHistoryImported]] Notice carrying the
   * conversation id and the count of events added.
   *
   * Skipped vs. [[publish]]: per-event `hub.emit`, [[fanOut]] (trigger
   * evaluation), inbound transforms, and [[settledEffects]] do NOT run
   * — these are *historical* events being seeded into context, not
   * "happened just now" wire events. Persistence and projection still
   * run so the conversation surfaces the events to subsequent reads.
   *
   * Caller is responsible for any follow-up triggering. Typical
   * pattern: `publish` a Tool-role success Message after this resolves
   * so the agent's normal trigger path fires once with the imported
   * history fully in place.
   *
   * @param events         events to import, in source order. Each
   *                       event's `conversationId` should match
   *                       `conversationId`; mismatches are persisted
   *                       under the event's own `conversationId`
   *                       without complaint (caller's responsibility
   *                       to validate if it cares).
   * @param conversationId target conversation. Surfaced on the emitted
   *                       notice so clients scope their refresh
   *                       decision.
   */
  final def publishHistorical(events: Seq[sigil.event.Event],
                              conversationId: Id[Conversation]): Task[Unit] =
    publishHistoricalSilent(events, conversationId).flatMap(_ =>
      notifyHistoryImported(conversationId, events.size)
    )

  /**
   * Persist + project a batch of historical events WITHOUT firing the
   * [[sigil.signal.ConversationHistoryImported]] refresh Notice.
   *
   * Used by long-running imports that progressively persist chunks
   * into a staging conversation: the workflow can call this many
   * times during processing (each chunk lands in DB silently — no
   * subscriber sees per-chunk refresh churn), then call
   * [[notifyHistoryImported]] exactly once at the end (typically
   * after [[mergeStagingIntoMain]] flips the events to the real
   * conversation).
   *
   * Apps doing one-shot imports (no staging step) should keep
   * calling [[publishHistorical]] — that's the convenience wrapper
   * that pairs Silent + Notify in a single call.
   */
  final def publishHistoricalSilent(events: Seq[sigil.event.Event],
                                    conversationId: Id[Conversation]): Task[Unit] =
    if (events.isEmpty) Task.unit
    else Task.sequence(events.toList.map(e => validateEventInvariants(e))).flatMap { _ =>
      // Inline contextFrame on every imported event before persisting
      // so the bulk-import path matches the publish-time pipeline's
      // settle-time inlining. Events that are still Active
      // (rare for imports, but supported) keep `contextFrame = None`.
      //
      // Sigil #261 — two-pass to mirror the unified ToolCall frame
      // model: first compute each event's own frame (ToolInvoke →
      // ToolCall, state derived from the invoke's own EventState +
      // settled output/outcome); then fold any Tool-role Message
      // diagnostics (orchestrator-synthesised refusal challenges /
      // repeated-query intercepts) into the matching ToolInvoke's
      // inlined ToolCall frame, transitioning it to Complete. This
      // is the bulk-import equivalent of the cross-event update
      // `attachContextFrameOnSettle` does at live-publish time.
      val framedMap = scala.collection.mutable.LinkedHashMap.empty[Id[Event], Event]
      events.foreach { e =>
        val withFrame =
          if (e.state != EventState.Complete || e.contextFrame.nonEmpty) e
          else e.withContextFrame(FrameBuilder.computeFrame(e))
        framedMap(e._id) = withFrame
      }
      framedMap.values.toList.foreach {
        case m: Message if m.role == MessageRole.Tool && m.state == EventState.Complete =>
          m.origin.foreach { invokeId =>
            framedMap.get(invokeId).foreach { ti =>
              ti.contextFrame match {
                // The shared pairing rule ([[FrameBuilder.settlesPairedCall]]):
                // fold when the parent frame is still Active or still carries
                // the "result hasn't landed" placeholder. A parent already
                // holding its own settled payload — every framework synthetic
                // diagnostic — is left as-is.
                case Some(tc: ContextFrame.ToolCall) if FrameBuilder.settlesPairedCall(tc) =>
                  framedMap(invokeId) = ti.withContextFrame(Some(FrameBuilder.settledPairedFrame(tc, m)))
                case _ =>
              }
            }
          }
        case _ =>
      }
      val framed = framedMap.values.toSeq
      val batches = framed.grouped(1000).toList
      val persistAll: Task[Unit] = Task.sequence(batches.map { batch =>
        withDB(_.events.transaction { tx =>
          Task.sequence(batch.toList.map(tx.upsert))
        })
      }).unit
      for {
        _ <- persistAll
        _ <- coalescedProjectionFor(conversationId, events)
        // Frames are sourced live from `db.events` — no view to rebuild post-publishHistoricalSilent.
      } yield ()
    }

  /**
   * Emit the [[sigil.signal.ConversationHistoryImported]] refresh
   * Notice for `conversationId`. Idempotent — safe to call from
   * cancel-handlers that aren't sure whether the import made
   * progress. Pair with [[publishHistoricalSilent]] for chunked
   * progressive imports; the convenience [[publishHistorical]]
   * already wraps both steps for one-shot imports.
   */
  final def notifyHistoryImported(conversationId: Id[Conversation], totalEventCount: Int): Task[Unit] =
    Task {
      hub.emit(sigil.signal.ConversationHistoryImported(conversationId, totalEventCount))
      ()
    }

  /** Coalesced [[Conversation]] row update for a bulk import: applies
    * the *last* [[ModeChange]] / [[TopicChange]] in the batch and adds
    * the summed cost from imported [[Message]]s. Skips per-event
    * [[sigil.signal.ConversationCostUpdated]] Notices — bulk imports
    * are silent on the wire by design. */
  private final def coalescedProjectionFor(conversationId: Id[Conversation],
                                           events: Seq[sigil.event.Event]): Task[Unit] = {
    val complete = events.filter(_.state == EventState.Complete)
    val latestMode = complete.reverseIterator.collectFirst { case mc: ModeChange => mc }
    val latestTopic = complete.reverseIterator.collectFirst { case tc: TopicChange => tc }
    val totalCost: BigDecimal = complete.iterator.collect {
      case m: Message =>
        m.modelId.flatMap(cache.find).map { model =>
          Sigil.costFor(model.pricing, m.usage)
        }.getOrElse(BigDecimal(0))
    }.foldLeft(BigDecimal(0))(_ + _)

    val applyMode: Task[Unit] = latestMode match {
      case None => Task.unit
      case Some(mc) =>
        withDB(_.conversations.transaction(_.modify(conversationId) {
          case Some(conv) if conv.currentMode != mc.mode =>
            Task.pure(Some(conv.copy(currentMode = mc.mode, modified = Timestamp(Nowish()))))
          case other => Task.pure(other)
        })).unit
    }
    val applyTopic: Task[Unit] = latestTopic match {
      case None     => Task.unit
      case Some(tc) => applyTopicChangeToStack(tc)
    }
    val applyCost: Task[Unit] =
      if (totalCost <= 0) Task.unit
      else withDB(_.conversations.transaction(_.modify(conversationId) {
        case None => Task.pure(None)
        case Some(conv) =>
          Task.pure(Some(conv.copy(cost = conv.cost + totalCost, modified = Timestamp(Nowish()))))
      })).unit

    applyMode.flatMap(_ => applyTopic).flatMap(_ => applyCost)
  }

  /**
   * Single-target broadcast — deliver `signal` only to subscribers
   * registered with [[signalsFor]] at the given viewer. Used primarily
   * for [[sigil.signal.Notice]] replies / snapshots that should reach
   * one specific connected client (e.g. a `ConversationListSnapshot`
   * answering that viewer's `RequestConversationList`).
   *
   * Like [[publish]], inbound transforms run first. The signal is NOT
   * persisted or projected — `publishTo` is for ephemeral targeted
   * delivery, not durable state changes. (For state changes, publish
   * an Event; the framework's pipeline handles persist + per-viewer
   * fan-out automatically.)
   */
  final def publishTo(viewer: ParticipantId, signal: Signal): Task[Unit] =
    applyInboundTransforms(signal).flatMap { resolved =>
      Task { hub.emitTo(viewer, resolved); () }
    }

  /**
   * Wrap a framework-internal Task with lifecycle Notices so client
   * UIs can render a progress indicator for it. Bug #50.
   *
   * Emits a [[sigil.signal.FrameworkWorkflowNotice]] in three (or
   * two) phases:
   *   1. `Started(label)` — immediately on enter.
   *   2. (optional) `Step(stepLabel, durationMs)` — emitted from
   *      within `task` via the `step` callback handed to it.
   *   3. `Completed(durationMs)` on success OR `Failed(reason,
   *      durationMs)` on error.
   *
   * Notices are transient — broadcast through the SignalHub, NOT
   * persisted to `db.events`. Pre-flight runs every turn; persisting
   * a started/completed pair per pre-flight pollutes the event log
   * with noise nothing reads later. Clients filter the Notice for
   * activity-bar / latency-trace rendering and apply their own
   * threshold ("don't paint sub-300ms workflows") client-side.
   *
   * `workflowType` is the broad category (`"preflight"`,
   * `"compress"`, `"frame-load"`, …). Apps wrap their own
   * framework-internal operations by calling this with their own
   * type strings — the framework treats the field as opaque.
   *
   * `conversationId` scopes the workflow to a conversation when
   * applicable (most common case); `None` for cross-conversation
   * operations.
   */
  final def runAsFrameworkWorkflow[A](workflowType: String,
                                      label: String,
                                      conversationId: Option[Id[Conversation]] = None)
                                     (task: FrameworkWorkflowControl => Task[A]): Task[A] = {
    // Backwards-compat shim. The public signature has not changed —
    // every prior callsite (in this repo and downstream) still calls
    // it the same way. Internally it delegates to [[RunUnit.execute]]
    // for the Started → Completed | Failed pulse, but ALSO carries the
    // extras this surface was always responsible for:
    //
    //   - the `Step` callback handed to the body via
    //     [[FrameworkWorkflowControl]] (with its implicit
    //     cancellation-token checkpoint),
    //   - registration / deregistration in
    //     [[Sigil.activeFrameworkWorkflows]] for the
    //     `cancel_framework_workflow` tool surface,
    //   - the special `CancellationException` failure-reason format
    //     ("cancelled: <reason>") that
    //     [[sigil.tool.core.CancelFrameworkWorkflowTool]] depends on.
    //
    // The persistent-Event vs operational-Notice distinction
    // pinned on [[RunUnit.execute]] still holds — apps and the
    // framework continue to publish persistent lifecycle Events
    // separately for their own callsites; the wrap here only emits
    // the operational `FrameworkWorkflowNotice`.
    val token = new CancellationToken(java.util.UUID.randomUUID().toString)
    val record = ActiveFrameworkWorkflow(token.workflowId, workflowType, label, conversationId,
      System.currentTimeMillis(), token)
    val started = record.startedAtMillis
    val stepCb: String => Task[Unit] = { stepLabel =>
      token.checkpoint.flatMap(_ =>
        publish(sigil.signal.FrameworkWorkflowNotice(
          token.workflowId,
          workflowType,
          sigil.signal.FrameworkWorkflowPhase.Step(stepLabel, System.currentTimeMillis() - started),
          conversationId
        ))
      )
    }
    val control = FrameworkWorkflowControl(stepCb, token)
    given sigilGiven: Sigil = this
    Task {
      Sigil.activeFrameworkWorkflows.put(token.workflowId, record)
      ()
    }.flatMap(_ => RunUnit.execute(new FunctionRunUnit[A](
      label = label,
      workflowType = workflowType,
      conversationId = conversationId,
      run = task(control),
      cleanup = Task {
        Sigil.activeFrameworkWorkflows.remove(token.workflowId)
        ()
      },
      cancellationToken = Some(token)
    )))
  }

  /** Convenience overload: wrap a Task that doesn't need to emit
    * intermediate `Step` Notices and doesn't poll cancellation
    * itself (the wrapper still aborts cleanly on cancellation
    * before the task runs, but won't interrupt mid-execution).
    * Bug #50 / #51. */
  final def runAsFrameworkWorkflow[A](workflowType: String,
                                      label: String,
                                      conversationId: Option[Id[Conversation]],
                                      task: Task[A]): Task[A] =
    runAsFrameworkWorkflow(workflowType, label, conversationId)(c => c.token.guard(task))

  /** Snapshot list of every framework workflow currently in flight.
    * Read-only; the records expose ids, type, label, conversation
    * scope, start timestamp, and cancellation token. Used by
    * `cancel_framework_workflow` and the activity-list surface.
    * Bug #51. */
  final def activeFrameworkWorkflows: List[ActiveFrameworkWorkflow] = {
    import scala.jdk.CollectionConverters.*
    Sigil.activeFrameworkWorkflows.values().asScala.toList.sortBy(_.startedAtMillis)
  }

  /** Cancel an in-flight framework workflow by id. Idempotent —
    * cancelling a finished or already-cancelled workflow is a no-
    * op. Returns whether this call flipped the flag (informational
    * for the caller — the workflow's body still has to reach a
    * checkpoint to honour it). Bug #51. */
  final def cancelFrameworkWorkflow(workflowId: String, reason: String): Boolean =
    Option(Sigil.activeFrameworkWorkflows.get(workflowId))
      .map(_.cancellationToken.cancel(reason))
      .getOrElse(false)

  /**
   * Inbound-Notice dispatch hook. Called by [[sigil.transport.SessionBridge]]
   * (and any other Notice-aware ingress) for each Notice that arrives
   * from a client over the wire. Apps and modules override to handle
   * their own Notice subtypes; the default chain handles the
   * framework-level Notices (`RequestConversationList`,
   * `SwitchConversation`, …) and the secrets module's request/reply
   * Notices when loaded.
   *
   * The chain pattern: subclass implementations match their own
   * Notice subtypes and call `super.handleNotice(notice, fromViewer)`
   * for the default arm so framework-level dispatch still runs.
   */
  def handleNotice(notice: sigil.signal.Notice, fromViewer: ParticipantId): Task[Unit] =
    notice match {
      case _: sigil.signal.RequestConversationList =>
        listConversations(fromViewer).flatMap { conversations =>
          publishTo(fromViewer, sigil.signal.ConversationListSnapshot(conversations))
        }
      case sigil.signal.SwitchConversation(convId, limit) =>
        // Uncapped canonical read returns the conversation's whole
        // event log oldest-first (the live-edge merge picks up any
        // in-flight iteration events); the trailing `limit` events
        // are the most-recent window the snapshot delivers.
        // Viewer-scoped read — an Agents-visibility event (checkpoint
        // directives, internal diagnostics) must never ship to a user
        // UI through the history path when the live wire filters it.
        eventsFor(convId, maxMessages = None, viewer = Some(fromViewer)).flatMap { page =>
          val cap = math.max(0, limit)
          val sorted = page.events
          val window = if (sorted.length <= cap) sorted else sorted.drop(sorted.length - cap)
          val hasMore = sorted.length > cap
          publishTo(fromViewer, sigil.signal.ConversationSnapshot(convId, window.toVector, hasMore))
        }

      case sigil.signal.RequestConversationHistory(convId, beforeMs, limit) =>
        // Uncapped canonical read with an exclusive upper timestamp
        // bound returns every event older than the `beforeMs` cursor,
        // oldest-first; the trailing `limit` of that set is the page
        // closest to the cursor.
        eventsFor(convId, maxMessages = None, maxTimestamp = Some(lightdb.time.Timestamp(beforeMs)),
                  viewer = Some(fromViewer)).flatMap { page =>
          val cap = math.max(0, limit)
          val sorted = page.events
          val window = if (sorted.length <= cap) sorted else sorted.drop(sorted.length - cap)
          val hasMore = sorted.length > cap
          publishTo(fromViewer, sigil.signal.ConversationHistorySnapshot(convId, window.toVector, hasMore))
        }

      // -- tool listing vocabulary (BUGS.md #38) --

      case sigil.signal.RequestToolList(spaces, kinds) =>
        listTools(fromViewer, spaces, kinds).flatMap { summaries =>
          publishTo(fromViewer, sigil.signal.ToolListSnapshot(summaries))
        }

      // -- client-registered interaction tools --

      case sigil.signal.RegisterClientTools(convId, sessionId, tools, replace) =>
        clientTools.register(convId, sessionId, tools, replace).flatMap { case (accepted, rejected) =>
          publishTo(fromViewer, sigil.signal.ClientToolsRegistered(convId, sessionId, accepted, rejected))
        }

      case sigil.signal.UnregisterClientTools(convId, sessionId, names) =>
        clientTools.deregister(convId, sessionId, names)

      case r: sigil.signal.ClientToolResult =>
        Task {
          val delivered = clientTools.completeResult(r.invokeId, r.content, r.isError)
          if (!delivered) scribe.debug(
            s"ClientToolResult for ${r.invokeId.value} had no parked call (already answered, timed out, or fire-and-forget) — ignored")
          ()
        }

      // -- conversation search vocabulary --
      // Symmetric to RequestConversationList for the search axis.
      // Reuses the same `searchConversationEvents` primitive that the
      // agent's `search_conversation` tool calls, so UI hits and agent
      // hits land identically. `conversationId` is required (no UI-side
      // "default conversation" concept in the framework — the panel
      // already knows which conversation it's filtering).

      case sigil.signal.RequestConversationSearch(query, convIdOpt, topicId, limit) =>
        convIdOpt match
          case None =>
            publishTo(fromViewer, sigil.signal.ConversationSearchSnapshot(query, Nil))
          case Some(convId) =>
            searchConversationEvents(convId, query, topicId, limit).map(_.map(searchHit)).flatMap { hits =>
              publishTo(fromViewer, sigil.signal.ConversationSearchSnapshot(query, hits))
            }

      // -- memory list vocabulary (sigil #292) --
      // Viewer-scoped (createdBy = fromViewer). Apps wanting wider
      // visibility override `handleNotice` to run their own query.
      // `pinned` pushes into Lucene via the indexed boolean; creator,
      // type, location, and the substring match are projections the
      // index can't express and run in memory. `limit` deliberately
      // stays post-filter — cutting before the viewer scope would
      // return someone else's rows' worth of empty slots. Superseded
      // versions are dropped so a versioned key shows once. `Pending`
      // records deliberately survive: this is the owner's own inbox,
      // and a memory awaiting approval is exactly what a review UI
      // needs to show, so the status half of the recall gate is
      // bypassed on purpose here.
      case r: sigil.signal.RequestMemoryList =>
        withDB(_.memories.transaction { tx =>
          import lightdb.filter.*
          val base = tx.query
          r.pinned.fold(base)(p => base.filter(_ => ContextMemory.pinned === p)).toList
        }).flatMap { all =>
          val q = r.query.map(_.toLowerCase).filter(_.nonEmpty)
          val filtered = all
            .filter(_.validUntil.isEmpty)
            .filter(_.createdBy.exists(_.value == fromViewer.value))
            .filter(m => r.memoryType.forall(_ == m.memoryType))
            .filter(m => !r.hasLocation || m.location.isDefined)
            .filter(m => q.forall(needle =>
              m.fact.toLowerCase.contains(needle) ||
                m.label.toLowerCase.contains(needle) ||
                m.summary.toLowerCase.contains(needle)))
            .take(math.max(0, r.limit))
            .map(sigil.tool.model.MemoryListEntry.from)
          publishTo(fromViewer, sigil.signal.MemoryListSnapshot(filtered))
        }

      // -- model catalog vocabulary (sigil #293) --
      // Global (no per-viewer scoping); apps wanting per-tenant
      // restrictions override.
      case r: sigil.signal.RequestModelCatalog =>
        val filtered = cache.all
          .filter(m => r.provider.forall(_.equalsIgnoreCase(m.provider)))
          .filter(m => r.modality.forall(md =>
            m.architecture.modality.equalsIgnoreCase(md) ||
              m.architecture.inputModalities.exists(_.equalsIgnoreCase(md))))
          .filter(m => r.query.map(_.toLowerCase).filter(_.nonEmpty).forall(needle =>
            m.name.toLowerCase.contains(needle) ||
              m._id.value.toLowerCase.contains(needle)))
        publishTo(fromViewer, sigil.signal.ModelCatalogSnapshot(filtered))

      // -- conversation lifecycle (sigil #300) --
      // Client-initiated clear/delete. The framework runs the action
      // (which broadcasts the corresponding outbound ConversationCleared
      // / ConversationDeleted Notice to every viewer) on behalf of the
      // requesting viewer. Symmetric to every other Request* Notice
      // above — the inbound shape is the verb, the broadcast shape is
      // the confirmation.
      case sigil.signal.RequestConversationClear(convId) =>
        clearConversation(convId, fromViewer)
      case sigil.signal.RequestConversationDelete(convId) =>
        deleteConversation(convId)

      // -- viewer-state + stored-file vocabularies --
      // Handled by the ViewerStateOps mixin. The Notice subtypes there
      // are disjoint from the framework-level ones above, so dispatch
      // order is irrelevant.
      case other if viewerStateNotices.isDefinedAt((other, fromViewer)) =>
        viewerStateNotices((other, fromViewer))

      case _ => Task.unit
    }

  /** Resolve the list of [[sigil.signal.ToolSummary]] visible to a
    * viewer, optionally narrowed to a subset of spaces and/or
    * [[sigil.tool.ToolKind]] values. Default walks `SigilDB.tools`,
    * filters by `accessibleSpaces(List(viewer))` (a tool's
    * `space` must be in the intersection of the viewer's authorized
    * spaces with the request's `spaces` filter), and applies the
    * `kinds` filter if supplied.
    *
    * Apps with massive tool catalogs override for indexed lookup or
    * partition-aware paging — the wire shape stays
    * [[sigil.signal.ToolListSnapshot]] either way. */
  def listTools(viewer: ParticipantId,
                spaces: Option[Set[SpaceId]] = None,
                kinds: Option[Set[sigil.tool.ToolKind]] = None): Task[List[sigil.signal.ToolSummary]] =
    accessibleSpaces(List(viewer)).flatMap { authorized =>
      val effective = spaces.fold(authorized)(_.intersect(authorized))
      val kindFilter: sigil.tool.Tool => Boolean =
        kinds.fold((_: sigil.tool.Tool) => true)(set => t => set.contains(t.kind))
      // Sigil #380 — read the tools store leniently. A row whose poly type
      // is no longer registered (a removed tool) must NOT fail the whole
      // catalog: the typed `_.list` would throw "Type not found" mid-stream
      // and abort every consumer (notice refreshes, ToolListSnapshot, …).
      // Walk `jsonStream` and drop rows that don't decode — same posture as
      // `StaticToolSyncUpgrade`'s orphan-tolerant prune. So removing a tool
      // is never DB-corrupting on read.
      withDB(_.tools.transaction(_.jsonStream.toList)).map { rows =>
        Sigil.decodeToolsLeniently(rows).collect {
          case tool if effective.contains(tool.space) && kindFilter(tool) =>
            sigil.signal.ToolSummary.fromTool(tool)
        }
      }
    }

  /** Fold the signal through [[inboundTransforms]] in declaration order.
    * Each transform sees the output of the previous one. */
  private final def applyInboundTransforms(signal: Signal): Task[Signal] =
    inboundTransforms.foldLeft(Task.pure(signal)) { (acc, transform) =>
      acc.flatMap(s => transform.apply(s, this))
    }

  /** Run each [[SettledEffect]] in declaration order, awaiting each
    * before the next. Effects that want fire-and-forget semantics
    * spawn their own fiber inside the returned Task. */
  private final def applySettledEffects(signal: Signal): Task[Unit] =
    settledEffects.foldLeft(Task.unit) { (acc, effect) =>
      acc.flatMap(_ => effect.apply(signal, this))
    }
}
