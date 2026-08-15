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
import sigil.conversation.{ReplySuggestionContext, ReplySuggestionsConfig}
import sigil.signal.SuggestedReplies
import sigil.tool.model.{Card, ResponseContent}
import sigil.conversation.compression.TranscriptRenderer
import sigil.tool.consult.{SuggestReplyInput, SuggestReplyTool}
import sigil.tool.{ToolFinder, ToolInput}
import sigil.vector.{NoOpVectorIndex, VectorIndex, VectorPoint, VectorPointId, VectorSearchResult}

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference


/**
 * Agent self-loop cluster — the claim, the loop, and the release.
 * Owns the in-process claim registry and its per-claim stop flags /
 * turn event log / stop latches, the `Stop` application path that
 * aborts in-flight provider streams and settles dangling invokes, the
 * cross-participant fan-out and `tryFire` claim acquisition, the
 * iteration loop itself (governor fold, forced synthesis, post-turn
 * extraction, intra-turn compaction, failure publication, heal
 * hand-off) and the trigger / context helpers each iteration needs.
 *
 * Mixed into [[Sigil]]; the self-type reaches the loop's config knobs
 * (`maxAgentIterations`, `turnGovernors`, …), `publish`, `withDB`,
 * the checkpoint surface, and the directive channel.
 */
trait AgentLoopOps { this: Sigil =>

  // -- stop-flag registry --

  /** Active per-claim [[StopFlag]]s, keyed by the `AgentState._id` that
    * owns the claim. Populated when `tryFire` wins a claim and removed
    * when `releaseClaim` completes (successfully or via error). */
  private final val stopFlags: ConcurrentHashMap[Id[Event], StopFlag] = new ConcurrentHashMap()

  /** In-process agent-claim registry — the AUTHORITATIVE mutual
    * exclusion for `tryFire`, keyed by the claim's deterministic lock
    * id. The persisted [[AgentState]] row remains the durable /
    * observable projection, but it cannot be the gate: its upsert is
    * buffered until the events transaction commits (a Lucene fsync,
    * milliseconds wide), and the store's per-id lock entry is evicted
    * when uncontended — so two publishes fanning out on independent
    * fibers could BOTH read no-claim inside that window and run two
    * concurrent generations (observed live: a voice caller's two
    * transcript fragments produced the same spoken reply twice). A
    * `putIfAbsent` on process-local state has no visibility window.
    * The claim was per-process by design already (multi-replica
    * deployments route conversations to one instance), so this
    * narrows no guarantee.
    *
    * `lastTriggerCheckAt` is maintained by the loop at each iteration
    * boundary; the release path rechecks for triggers newer than it
    * and re-fires, closing the check-then-release gap where a message
    * arriving between the loop's final trigger check and the claim
    * release found the claim still held, bailed, and was stranded. */
  private[sigil] final class ActiveClaimEntry(@volatile var lastTriggerCheckAt: Long,
                                       val conversationCostAtClaim: BigDecimal) {
    private val turnCostRef = new AtomicReference[BigDecimal](BigDecimal(0))
    def addTurnCost(delta: BigDecimal): Unit = { turnCostRef.updateAndGet(_ + delta); () }
    def turnCost: BigDecimal = turnCostRef.get()
    /** One-shot latches for the soft check-in, per scope. */
    val turnSoftFired = new java.util.concurrent.atomic.AtomicBoolean(false)
    val conversationSoftFired = new java.util.concurrent.atomic.AtomicBoolean(false)
    /** Escalation-headroom early fire: set by [[requestEscalation]]
      * when the tier bumps with the turn already ≥ half its soft
      * budget — the next boundary runs the check-in BEFORE the
      * expensive tier grinds, not after. */
    val budgetCheckinRequested = new java.util.concurrent.atomic.AtomicBoolean(false)
  }
  private[sigil] final val activeClaims: ConcurrentHashMap[Id[Event], ActiveClaimEntry] = new ConcurrentHashMap()

  /** External triggers that lost the claim race, keyed by the same
    * lock id as [[activeClaims]] and holding the instant the newest
    * such trigger was recorded. Written by `tryFire` when
    * `putIfAbsent` finds a live claim; consumed by the release path
    * immediately AFTER `activeClaims.remove` — the instant a new claim
    * can be won — so a publisher either wins the claim itself or
    * leaves a marker the releasing turn is guaranteed to see.
    *
    * The handoff exists because the release path's DB recheck can only
    * recover a trigger NEWER than the loop's last iteration boundary,
    * while fan-out wakes on triggers regardless of timestamp: a message
    * stamped before that boundary (built at transport ingress, then
    * published while the turn was ending) is woken on by fan-out,
    * declined by the live claim, and invisible to the recheck. The
    * marker carries no timestamp condition and no storage read, so the
    * recovery no longer depends on either.
    *
    * The stored value is the RECORDING instant, not the trigger's own
    * timestamp: a marker is spent once an iteration starts after it was
    * recorded (that iteration's context contains the trigger, which is
    * already persisted by the time the marker is written), and arrival
    * order is what that reflects — the trigger's own timestamp is
    * precisely the field the strand proves can't be trusted for it.
    *
    * Process-local, exactly like [[activeClaims]] — a multi-replica
    * deployment already routes a conversation to one instance. */
  private final val pendingTriggers: ConcurrentHashMap[Id[Event], java.lang.Long] = new ConcurrentHashMap()

  /** Drop any pending-trigger markers for `conversationId` — the
    * conversation is gone (or explicitly stopped), so nothing may
    * re-fire from it. */
  private[sigil] final def clearPendingTriggers(conversationId: Id[Conversation]): Unit = {
    import scala.jdk.CollectionConverters.*
    pendingTriggers.keySet().iterator().asScala
      .filter(_.value.endsWith(s":${conversationId.value}"))
      .toList
      .foreach(pendingTriggers.remove)
  }

  /** Spend the claim's marker if it was recorded before `cutoff` — the
    * start of an iteration that therefore builds its context with the
    * trigger already in it. Without this the release path would fire a
    * second turn for work the loop itself just handled. */
  private final def spendPendingTrigger(lockId: Id[Event], cutoff: Long): Unit =
    pendingTriggers.computeIfPresent(lockId, (_, recordedAt) =>
      if (recordedAt.longValue() < cutoff) null else recordedAt)

  /** Per-turn event log for the founding-turn topic sweep, keyed by
    * conversation (one live claim per conversation by design). Created
    * when `tryFire` wins a claim — seeded with the claim row itself —
    * appended by [[publish]] for every Event while the claim is live,
    * and consumed + removed by [[releaseClaim]]. Recording ids and
    * original topic stamps here — rather than querying the store at
    * turn end — keeps the sweep immune to the split store's
    * asynchronous index refresh: a turn-end query can miss the turn's
    * own tail, while deltas against recorded ids hit exact rows. The
    * founding user message publishes BEFORE the claim exists, so the
    * sweep resolves it separately from committed history. */
  private final class TurnEventLog {
    val stamps: ConcurrentHashMap[Id[Event], Id[Topic]] = new ConcurrentHashMap()
    val switches: java.util.concurrent.ConcurrentLinkedQueue[TopicChange] =
      new java.util.concurrent.ConcurrentLinkedQueue()
    def record(e: Event): Unit = {
      stamps.putIfAbsent(e._id, e.topicId)
      e match {
        case tc: TopicChange if tc.kind.isInstanceOf[TopicChangeKind.Switch] => switches.add(tc); ()
        case _ => ()
      }
    }
  }

  private final val activeTurnEvents: ConcurrentHashMap[Id[Conversation], TurnEventLog] = new ConcurrentHashMap()

  /** Record an Event into the owning conversation's live turn log, if
    * a claim is currently held. Cheap no-op otherwise. */
  private[sigil] final def recordTurnEvent(e: Event): Unit =
    Option(activeTurnEvents.get(e.conversationId)).foreach(_.record(e))

  /** Sigil #415 — conversation-level stop latch that OUTLIVES claims.
    * [[stopFlags]] only interrupts a claim that is live at the moment the
    * Stop arrives; in an erroring conversation churning through rapid
    * claim/release cycles (or with re-trigger machinery queued), a Stop
    * can land in a gap, set nothing, and evaporate — the next claim then
    * starts with a fresh unset flag and the agent visibly ignores the
    * user's Stop. The latch records the Stop's timestamp per conversation;
    * agent fan-out refuses to fire while it is set, and only a
    * user-authored Message NEWER than the latch clears it (a genuine
    * "keep going" re-arms naturally; auto-continue / challenge machinery
    * stays suppressed). In-memory: a restart clears latches, which is the
    * conservative direction (an agent firing after restart is recoverable;
    * a permanently-latched conversation is not). */
  private final val stopLatches: ConcurrentHashMap[Id[Conversation], java.lang.Long] = new ConcurrentHashMap()

  /** Sigil #415 — whether the user has requested a stop that should halt
    * further work for `conversationId` (optionally narrowed to one
    * agent). Consulted by the provider layer's transient-retry loop and
    * the candidate-fallback combinator BEFORE issuing another wire call:
    * a Stop that lands mid-retry-backoff must prevent the next attempt,
    * not race it. True when the conversation is stop-latched OR any
    * matching live claim's [[StopFlag]] is set. */
  private[sigil] final def stopRequested(conversationId: Id[Conversation],
                                         agentId: Option[ParticipantId] = None): Boolean =
    stopLatches.containsKey(conversationId) || {
      import scala.jdk.CollectionConverters.*
      stopFlags.entrySet().iterator().asScala.exists { entry =>
        entry.getKey.value.endsWith(s":${conversationId.value}") &&
          agentId.forall(id => entry.getKey.value == s"agentlock:${id.value}:${conversationId.value}") &&
          entry.getValue.requested
      }
    }

  /** In-flight provider HTTP-stream cancel handles, keyed per
    * (agent, conversation). A [[Provider]] registers its spice
    * `StreamHandle.cancel` here when an agent turn starts streaming and
    * deregisters when the stream terminates; [[applyStop]] looks up the
    * matching handle on a `Stop` and aborts the in-flight call so the
    * generated-then-discarded output tokens aren't billed. */
  final val providerStreams: sigil.provider.ProviderStreamRegistry =
    new sigil.provider.ProviderStreamRegistry

  /** On a [[Stop]] event, set the matching flag(s): one specific agent if
    * `targetParticipantId` is set, else every agent in the conversation.
    * Also aborts any in-flight provider HTTP stream for the matching
    * agent(s) via [[providerStreams]] so the call doesn't drain to
    * natural completion (which still bills the discarded output
    * tokens). Also logs the stop (with `reason`, if supplied) so
    * operators can see where stops originate — otherwise `Stop.reason`
    * would be metadata that only shows up if someone trawls the event
    * log. */
  private[sigil] final def applyStop(signal: Signal): Task[Unit] = signal match {
    case s: Stop =>
      val setFlags = Task {
        val target = s.targetParticipantId.map(_.value).getOrElse("*")
        val why = s.reason.map(r => s" reason=\"$r\"").getOrElse("")
        scribe.info(
          s"Stop received: conversation=${s.conversationId.value} target=$target " +
            s"force=${s.force} by=${s.participantId.value}$why"
        )
        // Sigil #415 — latch the conversation regardless of whether a
        // claim is live RIGHT NOW. Flags only reach a claim that exists
        // at this instant; the latch covers the gap (Stop landing between
        // claim/release cycles, queued re-triggers) so the conversation
        // stays stopped until a fresh user message re-arms it (fan-out
        // clears the latch on a user-authored Message newer than this).
        stopLatches.put(s.conversationId, java.lang.Long.valueOf(s.timestamp.value))
        import scala.jdk.CollectionConverters.*
        var matched = 0
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
            matched += 1
            if (s.force) flag.force.set(true) else flag.graceful.set(true)
            // Cancel the claim's tool-cancellation token on ANY stop so
            // an in-flight long-running tool can exit at its next
            // `ctx.checkpoint` instead of grinding on after the user
            // stopped the agent.
            flag.cancellation.cancel(s.reason.getOrElse("stopped by user"))
            ()
          }
        }
        // Cancel the conversation's detachable-tool executions —
        // attached or detached — through the same cooperative
        // `ctx.checkpoint` seam, unless the tool opted into surviving
        // Stop. A cancelled task settles its invoke with a Failure and
        // publishes NO continuation trigger.
        detachedToolTasks.values.iterator().asScala.foreach { t =>
          if (t.conversationId == s.conversationId && !t.keepRunningOnStop) {
            t.cancellation.cancel(s.reason.getOrElse("stopped by user"))
            ()
          }
        }
        // Diagnostic: a Stop that matched no live claim used to be a
        // silent total no-op — the exact failure observed in the field.
        // The latch above now covers it, but the log keeps the condition
        // visible for forensics.
        if (matched == 0) scribe.info(
          s"Stop for ${s.conversationId.value} matched no live agent claim — " +
            "conversation latched; agents will not re-fire until a new user message")
      }
      // Abort the in-flight provider HTTP stream(s) for the targeted
      // agent (or every agent in the conversation when no target is
      // set). Best-effort — cancel is idempotent and a missing handle
      // is a no-op, so a Stop arriving between iterations just finds
      // nothing to cancel.
      setFlags.flatMap(_ => providerStreams.cancelFor(s.conversationId, s.targetParticipantId))
    case _ => Task.unit
  }

  /** Settle any tool invoke left `Active` + `Pending` in `conversationId`
    * for `caller` — the dangling-on-Stop case.
    *
    * A `Stop(force = true)` truncates the agent's signal stream via
    * `takeWhile(_ => !force)` in `runAgentLoop`. When the cut lands
    * between a tool's `ToolInvoke` element and its paired settle element
    * (the window during which a slow atomic tool — a browser screenshot,
    * an HTTP fetch — is executing), the settle is dropped before it
    * persists. The orchestrator's in-memory reconcile can't recover it:
    * its `activeCalls` entry was already cleared when the now-dropped
    * settle signal was generated. The invoke is then stuck `Active` in
    * the event store — clients render it "running" forever and the next
    * turn's wire renderer treats it as a dangling `tool_call`.
    *
    * Run after the truncated drain (the loop's stop-exit): read the
    * durable store and fold a terminal Failure `ToolDelta` onto each
    * still-pending invoke so it reaches `Complete`. Scoped to `caller`
    * so a co-resident agent's in-flight calls in the same conversation
    * are untouched. Idempotent — a clean turn leaves nothing pending. */
  def settleDanglingToolInvokes(conversationId: Id[Conversation], caller: ParticipantId): Task[Unit] =
    withDB(_.conversationEventsConsistent(conversationId)).flatMap { events =>
      val dangling = events.collect {
        case ti: ToolInvoke
          if ti.participantId == caller
            && ti.state == EventState.Active
            && ti.outcome == sigil.event.ToolOutcome.Pending =>
          ti
      }
      // Registered in-flight dispatches whose invoke never became
      // durable — the force-stop cancelled the drain BEFORE the
      // dispatch's stream materialized, so there is no row to find
      // above. Re-publish the stored invoke first (its eager-broadcast
      // marker suppresses a duplicate hub emit), then settle it below
      // like any other dangler.
      val durableIds = events.collect { case ti: ToolInvoke => ti._id }.toSet
      import scala.jdk.CollectionConverters.*
      val unpersisted = inflightToolDispatches.values.asScala.toList.filter { inv =>
        inv.conversationId == conversationId && inv.participantId == caller && !durableIds.contains(inv._id)
      }
      unpersisted.foreach(inv => inflightToolDispatches.remove(inv._id))
      val persistMissing = unpersisted.foldLeft(Task.unit) { (acc, inv) =>
        acc.flatMap(_ => publish(inv).handleError(_ => Task.unit))
      }
      persistMissing.flatMap { _ =>
        (dangling ::: unpersisted).foldLeft(Task.unit) { (acc, ti) =>
          val reason = "Stopped before the tool produced a result."
          val settle = ToolDelta(
            target         = ti._id,
            conversationId = conversationId,
            input          = None,
            state          = Some(EventState.Complete),
            summary        = Some(reason),
            outcome        = Some(sigil.event.ToolOutcome.Failure(reason, recoverable = true))
          )
          acc.flatMap(_ => publish(settle).handleError(_ => Task.unit))
        }
      }
    }

  /**
   * Whether `event` should wake `agent` via the cross-participant fan-out
   * in `conv`. Normal conversations use [[TriggerFilter.isTriggerFor]].
   *
   * #352 — a DIRECTED worker conversation is stricter: an agent is woken
   * ONLY by an addressed `Standard` Message from another participant (the
   * brief, a relay, an answer). The worker's own Tool-role results and
   * broadcast chatter must NOT cross-wake the supervisor into running the
   * task itself — `TriggerFilter` fires Tool-role for every participant
   * ahead of the addressee check, which had the supervisor spinning up its
   * own coding loop on every worker tool call. Each agent's OWN
   * continuation is driven by its self-loop (which still uses the full
   * `isTriggerFor`), not fan-out, so the worker keeps iterating on its
   * tool results; the supervisor simply stops waking on them and acts only
   * when the worker actually addresses it (the [[WorkerConversationAddressingTransform]]
   * addresses an agent's reply to the other agent).
   */
  private def shouldWake(agent: AgentParticipant, event: Event, conv: Conversation): Boolean =
    if (!isDirectedWorkerConversation(conv)) TriggerFilter.isTriggerFor(agent, event)
    else event match {
      case m: Message
        if m.role == MessageRole.Standard && m.participantId != agent.id &&
          m.addressees.exists(_.contains(agent.id)) => true
      case _ => false
    }

  private[sigil] final def fanOut(event: Event): Task[Unit] = {
    // A fresh user-authored Message is the turn boundary — the prior
    // turn's delivered-result tracking has served its purpose (its
    // protection window ends with the turn) and must not leak across
    // turns as the conversation ages.
    event match {
      case m: Message
        if !m.participantId.isInstanceOf[AgentParticipantId] && m.role == MessageRole.Standard =>
        deliveredToolResults.remove(event.conversationId)
        ()
      case _ => ()
    }
    // Sigil #415 — honor the conversation's stop latch before waking any
    // agent. While latched, only a user-authored Message NEWER than the
    // Stop re-arms the conversation (and clears the latch); every other
    // trigger — an agent's own tool results, framework diagnostics,
    // challenge machinery, queued auto-continues — stays suppressed. The
    // user's Stop means stopped, even across claim/release churn.
    Option(stopLatches.get(event.conversationId)) match {
      case Some(stoppedAt) =>
        event match {
          case m: Message
            if !m.participantId.isInstanceOf[AgentParticipantId]
              && m.role == MessageRole.Standard
              && m.timestamp.value > stoppedAt =>
            stopLatches.remove(event.conversationId)
            fanOutUnlatched(event)
          case _ =>
            Task {
              scribe.debug(
                s"fanOut suppressed for ${event.conversationId.value}: conversation is stop-latched " +
                  s"(${event.getClass.getSimpleName} is not a fresh user message)")
            }
        }
      case None => fanOutUnlatched(event)
    }
  }

  private final def fanOutUnlatched(event: Event): Task[Unit] =
    withDB(_.conversations.transaction(_.get(event.conversationId))).flatMap {
      case None       => Task.unit
      case Some(conv) =>
        val fire: Task[Unit] = {
          val tasks: List[Task[Unit]] = conv.participants.collect {
            case agent: AgentParticipant if shouldWake(agent, event, conv) =>
              tryFire(agent, conv, trigger = Some(event))
          }
          Task.sequence(tasks).unit
        }
        // #327 — a directed worker conversation (linked to a parent,
        // ≥2 agents) terminates by the supervisor relaying up and not
        // re-addressing the worker. Backstop a non-terminating
        // supervisor↔worker exchange with a hard turn budget.
        val agentIds: Set[ParticipantId] = conv.participants.collect { case a: AgentParticipant => (a.id: ParticipantId) }.toSet
        if (!isDirectedWorkerConversation(conv)) fire
        else withDB(_.conversationEventsConsistent(conv._id)).flatMap { evs =>
          val agentMessages = evs.count {
            case m: Message => agentIds.contains(m.participantId)
            case _          => false
          }
          if (agentMessages < workerConversationTurnBudget) fire
          else Task {
            scribe.warn(
              s"Worker conversation ${conv._id.value} reached its turn budget " +
                s"($workerConversationTurnBudget agent messages); not firing further agent turns."
            )
          }
        }
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
  /**
   * Reconcile a persisted [[Participant]] against the app's current canonical
   * definition immediately before it runs a turn. The default is identity — a
   * conversation's participants are a snapshot taken at creation, so an agent's
   * roster / instructions / tool policy / generation settings are otherwise
   * frozen for that conversation's life.
   *
   * Apps that key canonical agent definitions by id override this to return the
   * current definition, so changes reach EXISTING conversations on their next
   * turn without an [[updateParticipant]] sweep — notably new tools added to a
   * [[sigil.provider.ToolPolicy.ActiveOnly]] / [[sigil.provider.ToolPolicy.Exclusive]]
   * roster, where there is no `find_capability` discovery path to surface a
   * catalog tool that isn't already in the frozen roster. A `Standard`-mode
   * agent already discovers new `staticTools` live via `find_capability`, so
   * this hook matters most for discovery-suppressed rosters.
   *
   * Contract: the returned participant MUST keep the same `id` (it's the same
   * seat in the conversation). The framework ignores a result whose id differs
   * and falls back to the persisted participant. The reconciliation does NOT
   * re-persist the conversation — it's applied per-turn — so a per-conversation
   * customization an app made via [[updateParticipant]] is only overridden if
   * the app's `currentParticipant` chooses to.
   */
  def currentParticipant(persisted: Participant): Task[Participant] = Task.pure(persisted)

  /** Claim-race loser bookkeeping: note an EXTERNAL trigger that found
    * the claim held, so the holder's release path re-fires for it.
    * Non-external triggers (the turn's own tool tail) leave nothing —
    * they are the holder's own work, not a reason to start a turn. */
  private final def recordPendingTrigger(agent: AgentParticipant,
                                         conv: Conversation,
                                         lockId: Id[Event],
                                         trigger: Option[Event]): Task[Unit] =
    trigger.filter(isExternalTrigger(agent, _)) match {
      case None => Task.unit
      case Some(_) =>
        pendingTriggers.merge(lockId, java.lang.Long.valueOf(System.currentTimeMillis()),
          (a, b) => if (a.longValue() >= b.longValue()) a else b)
        // The claim can be released between the failed `putIfAbsent`
        // and this write, leaving the marker behind the release's
        // read. Consuming it here — only once the claim is already
        // gone — closes that ordering hole.
        if (activeClaims.containsKey(lockId) || pendingTriggers.remove(lockId) == null) Task.unit
        else tryFire(agent, conv, trigger = trigger)
    }

  private final def tryFire(agent: AgentParticipant,
                            conv: Conversation,
                            greeting: Boolean = false,
                            trigger: Option[Event] = None): Task[Unit] = {
    val lockId = agentStateLockId(agent.id, conv._id)
    val claim = AgentState(
      agentId = agent.id,
      participantId = agent.id,
      conversationId = conv._id,
      topicId = conv.currentTopicId,
      // Written via tx.modify, so the canonicalizing inbound
      // transform never sees this row — stamp the current topic's
      // index directly.
      topicIndex = math.max(0, conv.topics.length - 1),
      activity = AgentActivity.Thinking,
      state = EventState.Active,
      timestamp = Timestamp(Nowish()),
      _id = lockId
    )
    // A conversation past its hard spend ceiling refuses to START
    // new turns — no LLM iteration runs on an exhausted budget. One
    // user-visible notice per exhaustion (cleared when `set_budget`
    // raises the ceiling) points the user at the fix.
    val convHardExhausted = effectiveBudgetsFor(conv).conversationHard.exists(conv.cost >= _)
    if (convHardExhausted) notifyBudgetExhausted(agent, conv)
    else {
    // The in-memory registry is the gate (see [[activeClaims]]); the
    // row write below is the durable projection. Losing here is fine:
    // the holder's loop picks the triggering event up at its next
    // iteration boundary, and an external trigger that lost the race
    // leaves a [[pendingTriggers]] marker the holder's release path
    // consumes.
    val won = activeClaims.putIfAbsent(lockId,
      new ActiveClaimEntry(claim.timestamp.value, conversationCostAtClaim = conv.cost)) == null
    if (!won) recordPendingTrigger(agent, conv, lockId, trigger)
    else withDB { db =>
      db.events.transaction(_.modify(lockId) {
        // Unconditional overwrite: the registry decided ownership. A
        // row still Active here is a fossil (a crashed process's claim
        // — the registry died with it); claiming over it IS the
        // recovery.
        case _ => Task.pure(Some(claim))
      }).map(_ => db.recordRecentEvent(claim))
    }.flatMap { _ =>
      // We won the claim. Register a StopFlag for this claim so any
      // Stop events published against this agent can interrupt.
      stopFlags.put(claim._id, new StopFlag)
      // Open the turn's latency accounting from the triggering event's
      // publish instant, so the first phase measures what the publish
      // pipeline spent before the agent could claim.
      openTurnPhases(conv._id, trigger.map(_.timestamp.value).getOrElse(claim.timestamp.value))
      // Open the turn's event log for the founding-turn topic
      // sweep, seeded with the claim row (written via tx.modify,
      // so the publish hook never sees it).
      val turnLog = new TurnEventLog
      turnLog.record(claim)
      activeTurnEvents.put(conv._id, turnLog)
      // Reconcile the persisted agent against the app's current definition
      // (default identity) so a new tool added to a discovery-suppressed
      // roster reaches this existing conversation. Keep the original on a
      // mismatched id or a non-agent result — the seat is by id.
      currentParticipant(agent).map {
        case fresh: AgentParticipant if fresh.id == agent.id => fresh
        case _                                               => agent
      }.flatMap { effectiveAgent =>
        // Broadcast manually (modify already persisted), then fire the
        // agent on its own fiber.
        Task {
          hub.emit(claim)
          runAgent(effectiveAgent, conv, claim, greeting = greeting).startUnit()
          ()
        }
      }
    }
    }
  }

  /**
   * Fire a one-shot greeting turn for `agent` in `conv`. Runs the agent's
   * standard merged dispatch through the lock-claim → loop machinery —
   * but with an empty trigger stream so the agent's roles' descriptions /
   * skills drive what the greeting says.
   *
   * No-op when `agent.greetsOnJoin == false`. Called automatically by
   * [[newConversation]] (fresh conversation case) and
   * [[addParticipant]] (late-join case); apps can also call it
   * directly to greet on demand.
   */
  def fireGreeting(agent: AgentParticipant, conv: Conversation): Task[Unit] =
    if (agent.greetsOnJoin) tryFire(agent, conv, greeting = true)
    else Task.unit

  /**
   * Self-loop while holding the AgentState(Active) claim:
   *
   *   - process triggers for the current iteration
   *   - check DB for any new triggers that arrived during processing
   *   - if any, loop without releasing the claim
   *   - if none, transition to Idle/Complete and release
   */

  /** Wipe the agent's [[sigil.conversation.ParticipantProjection.recentToolInvocations]]
    * rolling window for a conversation. Sigil #304 — the framework no
    * longer auto-clears this at the start of every `runAgent`; the
    * orchestrator's duplicate-call cap now scopes its count via
    * [[sigil.provider.ConversationRequest.turnStartedAt]], so the
    * window can persist across turns to feed
    * [[narrowRosterByRecentUse]] without inflating the dedupe count
    * for legitimate cross-turn retries. Apps that want an explicit
    * "reset agent state" UX action still call this directly; typical
    * conversation flow does not need to. */
  def clearDedupeForTurn(convId: Id[Conversation],
                         agentId: ParticipantId): Task[Unit] =
    updateProjection(convId, agentId)(_.copy(recentToolInvocations = Nil))

  private final def runAgent(agent: AgentParticipant,
                             conv: Conversation,
                             claimed: AgentState,
                             greeting: Boolean = false): Task[Unit] = {
    // Top-level operational wrap. The agent loop's existing
    // `handleError` chain still publishes the Failure Message and
    // releases the AgentState claim — this wrap layers a
    // `FrameworkWorkflowNotice` (workflowType = "agent-loop") on top
    // so operational observers (activity bars, latency traces) get an
    // explicit terminal pulse when the loop ends.
    //
    // Sigil #313 — both terminal phases fire. `AgentStateDelta(Idle)`
    // is a different channel keyed by agent/conversation, not the
    // workflowId; consumers tracking framework workflows by workflowId
    // can't correlate it, so a missing `Completed` left the Started
    // open forever (activity-bar rows ticking after idle). The wrap
    // must self-terminate: every Started gets a Completed or Failed.
    given sigilGiven: Sigil = this
    val unit = new RunUnit[Unit] {
      override val label = s"agent loop ${agent.id.value} / ${conv._id.value}"
      override val workflowType = "agent-loop"
      override val conversationId = Some(conv._id)
      override val run: Task[Unit] = runAgentLoopForUnit(agent, conv, claimed, greeting)
    }
    // `RunUnit.execute` re-throws the underlying exception after the
    // Failed Notice fires — matches the prior behaviour where
    // `runAgentLoop` itself rethrew via `Task.error(t)`. The fiber
    // boundary from `startUnit()` still logs the failure.
    RunUnit.execute(unit)
  }

  /** Inner body of [[runAgent]] — keeps the existing iteration
    * scaffolding intact. Split out so the top-level operational
    * [[RunUnit]] wrap can layer over it without disturbing the
    * lifecycle scaffolding the agent loop relies on
    * (`userVisibleSeen` / `turnExtractorFired` / `failurePublished`
    * / `discoveredCapabilitiesRef`). */
  private final def runAgentLoopForUnit(agent: AgentParticipant,
                                        conv: Conversation,
                                        claimed: AgentState,
                                        greeting: Boolean): Task[Unit] =
    // Once per claim: settle any detached invoke whose background task
    // died with a prior process — otherwise its frame reads "running as
    // task X" forever and the continuation the agent is waiting on
    // never comes.
    reconcileLostDetachedTools(conv._id).flatMap(_ =>
    runAgentLoop(
      agent,
      conv._id,
      claimed,
      iteration = 1,
      sinceTimestamp = claimed.timestamp,
      greeting = greeting,
      // Tracks "did the agent ever produce a user-visible terminal
      // signal across this loop's iterations?" The synthesized
      // placeholder Message in the no-more-triggers branch only fires
      // when this stays false. Bug #46 — without it, an agent that
      // chains tool calls without ever calling `respond` /
      // `no_response` / etc. ends the conversation in silence.
      userVisibleSeen = new java.util.concurrent.atomic.AtomicBoolean(false),
      // Bug #149 — the per-turn memory extractor must fire exactly
      // once per user turn (not once per agent-loop iteration). This
      // flag is threaded through every recursion so a CAS at the
      // terminate path guarantees a single fire across the whole
      // loop.
      turnExtractorFired = new java.util.concurrent.atomic.AtomicBoolean(false),
      // The post-error `publishFailureMessage` needs fire-once
      // semantics. Without this gate, an exception raised inside an
      // inner recursion level propagates up through every parent
      // level's `.handleError`, each of which publishes its own
      // Failure Message — surfacing N identical failure bubbles in
      // the chat for one failure. CAS-gated like `turnExtractorFired`.
      failurePublished = new java.util.concurrent.atomic.AtomicBoolean(false),
      // Per-loop `find_capability` cache. Shared across every
      // iteration of THIS loop so the agent doesn't re-discover
      // within the same task; a fresh AtomicReference per `runAgent`
      // call means the next user turn starts with an empty cache,
      // preventing prompt pollution from prior turns' discoveries.
      discoveredCapabilitiesRef = new AtomicReference(Map.empty),
      // Sigil #411 — turn-scoped read-tool result cache, fresh per turn so a
      // retry's identical READ is served from cache instead of re-executing.
      toolResultCacheRef = new AtomicReference(Map.empty),
      // Sigil #313 — one heal per turn. CAS-gated; a healed retry
      // that ALSO fails is treated as Exhausted, NOT retried again.
      healedThisTurn = new java.util.concurrent.atomic.AtomicBoolean(false),
      // Sigil #313 — correlation id shared across the durable triple
      // (CorruptionDetected → Healed → Exhausted) for THIS turn.
      healCorrelationId = new AtomicReference(None)
    ))

  /** Sigil #392 — completes once `flag.force` is set. Polled (not callback-
    * driven) because `StopFlag.force` is a plain `AtomicBoolean` flipped by
    * `applyStop`; a 25 ms tick is imperceptible against a user clicking Stop and
    * costs nothing while idle. Raced against the iteration drain so a force Stop
    * interrupts an in-flight tool — the winning waiter cancels the drain fiber.
    * When the drain finishes first, `Task.race` cancels THIS waiter (interrupts
    * its sleep), so it never leaks. */
  private def awaitForceStop(flag: StopFlag): Task[Unit] = Task.defer {
    if (flag.force.get()) Task.unit
    else Task.sleep(25.millis).flatMap(_ => awaitForceStop(flag))
  }

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
  private[sigil] final def runAgentLoop(agent: AgentParticipant,
                                 convId: Id[Conversation],
                                 claimed: AgentState,
                                 iteration: Int,
                                 sinceTimestamp: Timestamp,
                                 greeting: Boolean = false,
                                 userVisibleSeen: java.util.concurrent.atomic.AtomicBoolean,
                                 /** Bug #149 — single-shot fire-gate for the
                                   * per-turn memory extractor. Shared across
                                   * every iteration of the loop so the
                                   * extractor runs exactly once per user
                                   * turn at the terminate boundary, not
                                   * once per iteration. */
                                 turnExtractorFired: java.util.concurrent.atomic.AtomicBoolean,
                                 /** Sigil bug #200 — single-shot fire-gate for
                                   * `publishFailureMessage`. Threaded through
                                   * every recursion so the inner-most handler
                                   * publishes once and outer-level handlers
                                   * skip the duplicate publish on re-throw. */
                                 failurePublished: java.util.concurrent.atomic.AtomicBoolean,
//
                                 forceResponseSynthesis: Boolean = false,
                                 forcedReason: Option[ForcedSynthesisReason] = None,
                                 /** Sigil #257 — count of consecutive
                                   * full-roster retries already spent on
                                   * no-tool-call responses this turn. Any
                                   * iteration that makes real progress resets
                                   * it to 0 (the normal continuation simply
                                   * doesn't pass it); bounded by
                                   * [[noToolCallRetryLimit]]. */
                                 noToolCallRetries: Int = 0,
                                 discoveredCapabilitiesRef: AtomicReference[Map[String, sigil.conversation.DiscoveredCapability]],
                                 /** Turn-scoped read-tool result cache
                                   * (canonical key → cached read). Created once per
                                   * `runAgent` call and threaded through each iteration
                                   * so a retry re-issuing an identical READ is served
                                   * from cache. */
                                 toolResultCacheRef: AtomicReference[Map[String, sigil.tool.CachedToolRead]],
                                 /** Sigil #313 — single-shot fire-gate for the
                                   * reactive self-heal. Threaded through every
                                   * recursion so a healed retry that ALSO
                                   * fails does NOT trigger a second heal —
                                   * `HealingExhausted` publishes and the
                                   * standard failure path runs. The flag is
                                   * shared across the whole agent loop (one
                                   * heal per user turn). */
                                 healedThisTurn: java.util.concurrent.atomic.AtomicBoolean,
                                 /** Sigil #313 — correlation id shared between
                                   * the durable
                                   * [[sigil.event.ConversationCorruptionDetected]],
                                   * [[sigil.event.ConversationHealed]],
                                   * [[sigil.event.HealingExhausted]] triple
                                   * for a single turn's heal arc. Threaded so
                                   * the retry's audit pulse joins the same
                                   * arc. */
                                 healCorrelationId: AtomicReference[Option[String]],
                                 /** Sigil #413 — count of context-overflow
                                   * recoveries already spent this turn. Each
                                   * recovery re-runs the iteration with
                                   * `emergencyContextFactor = 0.5^n` so the
                                   * turn's input is force-refitted under the
                                   * wire window; bounded by
                                   * [[maxOverflowCompactions]], after which
                                   * the overflow surfaces as a clean terminal
                                   * failure. Threaded through every recursion
                                   * so the bound holds across the whole turn. */
                                 overflowCompactions: Int = 0
                                   ): Task[Unit] = Task.defer {
    // Bug #149 — release the agent's claim AND fire the per-turn
    // memory extractor exactly once. The CAS-guard guarantees a
    // single extraction across every terminal exit path of the
    // loop (Stop, max-iterations, cap-hit, checkpoint intervention,
    // post-stream error). The extractor itself runs on a fiber
    // so the release path isn't blocked by its LLM round-trip.
    def terminate(skipFallback: Boolean = false): Task[Unit] = {
      if (turnExtractorFired.compareAndSet(false, true)) {
        firePostTurnExtraction(agent, convId, claimed.timestamp).startUnit()
        fireReplySuggestions(agent, convId, claimed.timestamp).startUnit()
      }
      // Sigil bug #282 — defensive guarantee: never release the agent's
      // claim without something user-visible reaching the conversation.
      // The normal terminal paths set `userVisibleSeen` (respond family
      // settles, orchestrator silent-turn placeholder, …); the
      // handleError + Stop paths bypass the fallback explicitly because
      // they publish their own user-visible content (Failure Message,
      // user-initiated stop). Any other path that reaches here without
      // a user-visible reply gets a synthetic fallback Message composed
      // from the most recent ProgressCheckpoint's status so the UI
      // doesn't spin indefinitely on a silent turn end.
      val fallback: Task[Unit] =
        if (skipFallback || userVisibleSeen.get()) Task.unit
        else synthesizeFallbackRespond(agent, convId)
      // Post-release recovery for a message that landed between the
      // loop's final trigger check and the claim release: it found the
      // claim held, bailed at the gate, and would otherwise be
      // STRANDED until the next unrelated trigger. The
      // [[pendingTriggers]] marker such a publisher leaves is the
      // primary path; the store recheck backs it up for anything that
      // reached the DB without going through a declined `tryFire`.
      // Capture the last boundary the loop's checks covered (before
      // cleanup drops the registry entry) for that recheck.
      // Deliberately not `newTriggersExist`: that also counts the
      // turn's own Tool-role tail, which lands after the final
      // iteration's boundary on every tool-calling turn and would
      // re-fire a spurious LLM iteration per turn. Skipped when the
      // conversation is stop-latched — the user's Stop means stopped.
      // Best-effort: a recovery failure never breaks the release.
      val lastCheckedAt = Option(activeClaims.get(claimed._id))
        .map(_.lastTriggerCheckAt)
        .getOrElse(claimed.timestamp.value)
      fallback.flatMap(_ => releaseClaim(claimed)).flatMap { _ =>
        // Strictly after `releaseClaim`'s `activeClaims.remove`: a
        // publisher that reached the gate while the claim was live
        // either wins the free claim itself now, or left the marker
        // this consumes.
        if (stopLatches.containsKey(convId)) Task(clearPendingTriggers(convId))
        else {
          val handedOff = pendingTriggers.remove(claimed._id) != null
          withDB(_.conversations.transaction(_.get(convId))).flatMap {
            case Some(conv) if handedOff => tryFire(agent, conv)
            case Some(conv) =>
              externalTriggersExist(agent, conv, sinceTimestamp = Timestamp(lastCheckedAt)).flatMap {
                case true  => tryFire(agent, conv)
                case false => Task.unit
              }
            case None => Task.unit
          }.handleError(t => Task(scribe.warn(
            s"post-release trigger recheck failed for ${agent.id.value}/${convId.value}: ${t.getMessage}")))
        }
      }
    }
    // Snapshot the start of THIS iteration. The next iteration uses this as
    // its own `sinceTimestamp`, so events emitted during this iteration
    // (including self-emitted non-terminal tool results the agent acted on)
    // don't re-appear as triggers next time. Mirrored onto the claim
    // registry so the release path knows the newest boundary the loop's
    // trigger checks covered — its recheck only fires for events newer
    // than this.
    val thisIterationStart = Timestamp(Nowish())
    Option(activeClaims.get(claimed._id)).foreach(_.lastTriggerCheckAt = thisIterationStart.value)
    // This iteration reads the conversation as it stands now, so any
    // trigger already recorded as pending is folded into its context —
    // the release path must not re-fire for it.
    spendPendingTrigger(claimed._id, System.currentTimeMillis())
    // The cap-hit, no-tool-call, and stall-intervention branches each
    // recover by running ONE more iteration with `forceResponseSynthesis`
    // pinning `tool_choice` to the respond family. The recursive call is
    // identical across all three apart from `forcedReason`; this captures
    // the common shape so the three sites can't drift.
    def recurseForced(reason: ForcedSynthesisReason): Task[Unit] =
      runAgentLoop(
        agent                     = agent,
        convId                    = convId,
        claimed                   = claimed,
        iteration                 = iteration + 1,
        sinceTimestamp            = thisIterationStart,
        greeting                  = false,
        userVisibleSeen           = userVisibleSeen,
        turnExtractorFired        = turnExtractorFired,
        failurePublished          = failurePublished,
        forceResponseSynthesis    = true,
        forcedReason              = Some(reason),
        discoveredCapabilitiesRef = discoveredCapabilitiesRef,
        toolResultCacheRef = toolResultCacheRef,
        healedThisTurn            = healedThisTurn,
        healCorrelationId         = healCorrelationId,
        overflowCompactions       = overflowCompactions
      )
    // Sigil #257 — recovery for a no-tool-call response: run ONE more
    // iteration with the FULL roster + normal `tool_choice` intact (NOT
    // forced synthesis). A no-tool-call turn is usually a transient
    // reasoning-model hiccup; a plain re-prompt self-corrects far more
    // often than stripping the roster to the respond family does — the
    // latter guarantees a non-answer for any turn that needed tools.
    // `noToolCallRetries` increments so the loop strips to respond-only
    // only after [[noToolCallRetryLimit]] retries also miss.
    def recurseFullRosterRetry: Task[Unit] =
      runAgentLoop(
        agent                     = agent,
        convId                    = convId,
        claimed                   = claimed,
        iteration                 = iteration + 1,
        sinceTimestamp            = thisIterationStart,
        greeting                  = false,
        userVisibleSeen           = userVisibleSeen,
        turnExtractorFired        = turnExtractorFired,
        failurePublished          = failurePublished,
        forceResponseSynthesis    = false,
        forcedReason              = None,
        noToolCallRetries         = noToolCallRetries + 1,
        discoveredCapabilitiesRef = discoveredCapabilitiesRef,
        toolResultCacheRef = toolResultCacheRef,
        healedThisTurn            = healedThisTurn,
        healCorrelationId         = healCorrelationId,
        overflowCompactions       = overflowCompactions
      )
    val stopFlag = Option(stopFlags.get(claimed._id))
    // Bug #74 — flips when a `respond` settles with `endsTurn = false`
    // (a progress / status update). The post-drain decision below
    // iterates the loop without waiting for new triggers, so the
    // agent picks up its own respond Message in the next iteration's
    // history and continues working. Per-iteration scope (the next
    // iteration starts with its own fresh AtomicBoolean).
    val agentRequestedContinue = new java.util.concurrent.atomic.AtomicBoolean(false)
    // Flips when a user-visible terminal tool (`respond` with
    // `endsTurn = true`, `no_response`, the other `respond_*` family
    // members) settles this iteration. Every tool now emits a
    // `ToolResults` (role = Tool) which `TriggerFilter` counts as a
    // re-trigger; without this flag the agent's OWN terminal-tool
    // result would keep `newTriggersExist` true and the loop would
    // never end. When set, the post-drain check only continues for a
    // genuine *external* trigger (a message from someone else that
    // landed mid-turn), never for the turn's own emitted events.
    val terminalToolSettled = new java.util.concurrent.atomic.AtomicBoolean(false)
    // Set when the orchestrator emits a `_refusal_challenge` or
    // `_turn_decision_required` challenge this iteration — the agent's
    // terminal action was intercepted and replaced with a diagnostic it
    // must read and act on. The loop MUST run another iteration so the
    // agent decides explicitly; `terminalToolSettled` (set by a
    // suppressed respond's settle delta) or the absence of any other
    // continue signal would otherwise end the turn before the challenge
    // is ever acted on.
    val frameworkRequestedContinue = new java.util.concurrent.atomic.AtomicBoolean(false)
    // Sigil #275 — set when the model's response for this iteration
    // contained AT LEAST ONE non-internal `ToolInvoke`. The narrowed
    // runaway counter (sigil #273) MUST only count iterations with
    // genuinely zero `tool_use` blocks emitted; without this flag the
    // post-drain `newTriggersExist` predicate misclassifies successful
    // non-terminal tool calls (record_consent, list_theme_files, etc.)
    // as "no tool call" because their `ToolInvoke` event has the
    // default Standard role + the agent's own participantId, and
    // `TriggerFilter` excludes both. The flag is read inside
    // `shouldIterate` to force `case true =>` for any iteration the
    // agent actually dispatched a real tool on — the loop then runs
    // another iteration to read the tool's result. Framework-synthesised
    // diagnostic invokes (`_provider_error`, `_unknown_tool`,
    // `_cap_reached`, …) carry `internal = true` and don't count;
    // they're not signals that the model is following the protocol.
    val iterationHadToolCall = new java.util.concurrent.atomic.AtomicBoolean(false)
    // The model this iteration actually routed to (pin / classifier /
    // mid-turn escalation resolved). Captured off the built TurnContext
    // so the boundary's compaction decision is sized against the window
    // the prompt was built for, not the agent's nominal default.
    val routedModelId = new java.util.concurrent.atomic.AtomicReference[Id[Model]](agent.modelId)
    // Bug #57 — diagnostic logging at iteration boundaries so a
    // future repro of "agent parks at thinking" can be localised
    // by reading the server log for missing exit lines. The cost
    // of these scribe.debug calls is negligible compared to the
    // turn's actual work; volume is ~3 lines per iteration.
    scribe.debug(s"runAgentLoop[${agent.id.value}/${convId.value}] iter=$iteration enter")
    markTurnIteration(convId)
    // Batch this iteration's `events` writes into one transaction so
    // the Lucene-indexed event store commits once per iteration
    // instead of once per streamed Delta. `iterationStep` is a
    // `Task[Task[Unit]]`: the outer Task does this iteration's work
    // (publishes, tool dispatch) inside the batched scope; the inner
    // Task it yields is the continuation — the NEXT iteration's
    // `runAgentLoop` call, or the terminal release (`terminate()`)
    // for a terminal exit. The continuation runs AFTER
    // `withBatchedEvents` commits, so iteration N+1's reads see
    // iteration N's committed data, and the terminal
    // `AgentStateDelta(Idle, Complete)` is broadcast only once the
    // turn's events are durable — never racing the commit.
    val iterationStep: Task[Task[Unit]] =
    // A Stop may have landed before this iteration even starts; short-
    // circuit if so (graceful = "don't start another iteration"; force
    // = "same, plus the in-flight stream below won't run"). Either way,
    // release and exit.
    if (stopFlag.exists(_.requested)) Task(terminate(skipFallback = true))
    else
    // Reload the conversation each iteration — materialized projections
    // (currentMode, modified, etc.) update as Events flow through `publish`,
    // so the conversation we hand to the agent must reflect the latest state.
    withDB(_.conversations.transaction(_.get(convId))).flatMap {
      case None =>
        // Conversation deleted mid-turn — release the lock and exit cleanly.
        // Extractor isn't fired here — no conversation = nothing to extract.
        // Yielded as the continuation so the release runs post-commit.
        Task(releaseClaim(claimed))
      case Some(conv) =>
        // Sigil bug #169 — overlay persists across iterations within the
        // same user turn. Prerequisite calls (`record_consent`, etc.) and
        // multi-invocation flows (`create_workflow` → `add_workflow_step` 5×)
        // keep their discovered tools in scope until the loop terminates
        // (handled in `terminate()` above) or a new `find_capability` /
        // suggestion-emitting tool result replaces the list.
        {
          scribe.debug(s"runAgentLoop[${agent.id.value}/${convId.value}] iter=$iteration buildContext start")
          buildContext(agent, conv, sinceTimestamp = sinceTimestamp, claimedId = claimed._id, claimedTimestamp = claimed.timestamp, isGreeting = greeting && iteration == 1, discoveredCapabilitiesRef = discoveredCapabilitiesRef, toolResultCacheRef = toolResultCacheRef, healedThisTurn = Some(healedThisTurn)).flatMap {
            case (rawCtx, triggers) =>
              // Thread the claim's tool-cancellation token so in-flight
              // tool executions can cooperate with Stop via
              // `ctx.checkpoint`.
              val ctx0 = if (forceResponseSynthesis) rawCtx.copy(forceResponseSynthesis = true) else rawCtx
              val ctx1 =
                if (overflowCompactions > 0)
                  ctx0.copy(emergencyContextFactor = Some(math.pow(0.5, overflowCompactions)))
                else ctx0
              val ctx = stopFlag match {
                case Some(flag) => ctx1.copy(cancellation = Some(flag.cancellation))
                case None       => ctx1
              }
              routedModelId.set(ctx.model._id)
              scribe.debug(s"runAgentLoop[${agent.id.value}/${convId.value}] iter=$iteration buildContext done; dispatching agent.process")
              // Wrap the agent's signal stream with a force-stop check so a
              // Stop(force=true) mid-iteration terminates the stream promptly.
              // Greeting mode (only on iteration == 1): dispatch only behaviors
              // with `greetsOnJoin = true` against an empty trigger stream;
              // subsequent iterations (driven by the agent's own non-terminal
              // tool calls) revert to the standard process path.
              val rawStream =
                if (greeting && iteration == 1) agent.processGreeting(ctx)
                else agent.process(ctx, triggers)
              val interruptible = stopFlag match {
                case Some(flag) => rawStream.takeWhile(_ => !flag.force.get())
                case None       => rawStream
              }
              // Tap the stream for user-visible terminal signals. A
              // settled `ToolDelta` whose target ToolInvoke names a
              // user-visible terminal tool (`respond` / `no_response`
              // / etc.) flips the loop-wide flag — so the no-more-
              // triggers branch knows whether to synthesize a
              // placeholder Message. We watch the ToolInvoke (which
              // carries `toolName`) and remember matching invoke ids,
              // then flip the flag on their settle delta.
              // Map invoke id → tool name so we can distinguish `respond`
              // from the other user-visible-terminal names when the
              // settle delta lands (bug #74's `endsTurn` lever applies
              // to `respond` specifically).
              val activeUserVisibleInvokes = new java.util.concurrent.ConcurrentHashMap[Id[Event], String]()
              val drainTask =
                interruptible
                .evalTap {
                  case ti: ToolInvoke if _root_.sigil.tool.core.RespondFamilyTool.contains(ti.toolName) =>
                    Task {
                      activeUserVisibleInvokes.put(ti._id, ti.toolName.value)
                      // Sigil #275 — respond-family invokes count as "model
                      // emitted a tool_use block". Set the flag here too;
                      // the terminalToolSettled path takes over for the
                      // continuation decision but the runaway counter has
                      // already been told this wasn't an empty response.
                      if (!ti.internal) iterationHadToolCall.set(true)
                      ()
                    }
                  case ti: ToolInvoke if ti.toolName.value == Directive.RefusalChallengeName
                                      || ti.toolName.value == Orchestrator.TurnDecisionToolName =>
                    Task { frameworkRequestedContinue.set(true); () }
                  case ti: ToolInvoke if !ti.internal =>
                    // Sigil #275 — record that this iteration's response
                    // contained at least one real tool_use block. Filters
                    // out framework-synthesised diagnostic invokes
                    // (`internal = true`) so the flag tracks the model's
                    // actual behaviour, not the framework's bookkeeping.
                    Task { iterationHadToolCall.set(true); () }
                  // Silent-turn placeholder emitted by the orchestrator
                  // when Usage arrives with no target. Marked via
                  // `source = "orchestrator-silent-turn"` so the loop
                  // recognises it as a user-visible reply without
                  // matching every agent Standard Message.
                  case m: sigil.event.Message
                    if m.source.contains("orchestrator-silent-turn") && m.participantId == agent.id =>
                    Task { userVisibleSeen.set(true); () }
                  // Sigil #392 — a naked-text terminal answer (end_turn + text,
                  // no tool, on the no-forced-tool_choice path) was committed by
                  // the orchestrator. Treat it as a user-visible reply so the
                  // no-tool-call branch terminates instead of re-requesting the
                  // same prose (the Fable/Mythos 5 dupe loop).
                  case md: sigil.signal.MessageDelta if md.terminalReply =>
                    Task {
                      userVisibleSeen.set(true)
                      markTurnPhase(convId, TurnPhase.StreamEndToTerminal)
                      ()
                    }
                  case td: ToolDelta if td.state.contains(EventState.Complete)
                                     && activeUserVisibleInvokes.containsKey(td.target) =>
                    Task {
                      userVisibleSeen.set(true)
                      markTurnPhase(convId, TurnPhase.StreamEndToTerminal)
                      // Bug #74 — `respond(endsTurn = false)` keeps the
                      // turn open. The settled delta carries the parsed
                      // input; flip the continue flag when it's a
                      // RespondInput with endsTurn = false. Every other
                      // user-visible terminal tool (`respond` with
                      // endsTurn = true, `no_response`, the other
                      // `respond_*` family) ENDS the turn — flip
                      // `terminalToolSettled` so the post-drain check
                      // doesn't mistake the turn's own emitted
                      // `ToolResults` for a fresh trigger.
                      //
                      // Bug #226 — also drop the per-loop
                      // `find_capability` cache on respond(endsTurn =
                      // true). Covers the streaming-respond path
                      // (orchestrator settles the in-flight Message via
                      // `MessageDelta` and never calls
                      // `RespondTool.executeResult`); the atomic-respond
                      // path's clear is idempotent with this one.
                      val toolName = activeUserVisibleInvokes.get(td.target)
                      val keepsTurnOpen = toolName == "respond" && (td.input match {
                        case Some(r: sigil.tool.model.RespondInput) => !r.endsTurn
                        case _                                      => false
                      })
                      if (keepsTurnOpen) agentRequestedContinue.set(true)
                      else {
                        terminalToolSettled.set(true)
                        discoveredCapabilitiesRef.set(Map.empty)
                      }
                      ()
                    }
                  case _ => Task.unit
                }
                .evalTap(publish)
                .drain
              // Sigil #392 — race the drain against the force-stop flag so a
              // force Stop interrupts an in-flight tool's blocking call. The
              // `takeWhile(!force)` above only fires at element boundaries —
              // while a tool is mid-execution no element is in flight, so the
              // flag isn't observed until the tool returns (the worst case being
              // the tool's own timeout). The post-drain `stopFlag.exists(_.requested)` branch settles
              // any dangling invoke + terminates cleanly. `race` returns the
              // waiter's result and discards the cancelled drain's
              // InterruptedException, so no error surfaces.
              val drained = stopFlag match {
                case Some(flag) => Task.race(drainTask, awaitForceStop(flag)).map(_ => ())
                case None       => drainTask
              }
              // The model consumed this iteration's prompt and produced a
              // response — every settled tool result the prompt carried has
              // now been DELIVERED. Mark them so the compaction machinery
              // (intra-turn compactor, curator sheds, summary cover-sets)
              // may fold them from later prompts; unmarked settled results
              // stay whole via CompactionInvariant.UndeliveredToolResults.
              // Skipped on a stop (the response may not have been consumed)
              // and on drain error (the flatMap never runs) — both err
              // toward protecting, never toward dropping.
              drained.flatMap { _ =>
                Task {
                  if (!stopFlag.exists(_.requested)) markToolResultsDelivered(
                    convId,
                    ctx.turnInput.frames.collect {
                      case tc: sigil.conversation.ContextFrame.ToolCall
                        if tc.state.isInstanceOf[sigil.conversation.ToolCallState.Complete] && !tc.resultPending =>
                        tc.callId
                    }
                  )
                }
              }
          }
        }.flatMap { _ =>
          scribe.debug(s"runAgentLoop[${agent.id.value}/${convId.value}] iter=$iteration drain done")
          // After the iteration drains, check stop flags before anything
          // else — a Stop that fired mid-stream means exit now, don't
          // continue looping even if there are new triggers. A force Stop
          // may have truncated the stream between a tool's invoke and its
          // settle (`takeWhile(!force)` above) — settle any invoke left
          // dangling before releasing the claim so it doesn't render
          // "running" forever / poison the next turn's wire render.
          if (stopFlag.exists(_.requested))
            settleDanglingToolInvokes(convId, agent.id).flatMap(_ => Task(terminate(skipFallback = true)))
          else if (forceResponseSynthesis) {
            if (userVisibleSeen.get())
              Task(terminate())
            else
              // Routed through the handleError below — it owns the
              // failure publish + the post-commit terminal release.
              Task.error(buildRunawayException(
                agent, conv, iteration, maxAgentIterations, forcedReason))
          }
          else {
            // Bug #74 — `respond(endsTurn = false)` continues the
            // loop without waiting for an external trigger. The
            // agent's own progress respond IS the signal to keep
            // going; the next iteration will see it in history and
            // proceed with the announced work.
            val shouldIterate: Task[Boolean] =
              if (agentRequestedContinue.get() || frameworkRequestedContinue.get()) Task.pure(true)
              else if (terminalToolSettled.get())
                // The agent ended the turn with a user-visible terminal
                // tool. Continue ONLY for a genuine external trigger (a
                // message from someone else that landed mid-turn) — never
                // for the turn's own `ToolResults` / reply Message.
                externalTriggersExist(agent, conv, sinceTimestamp = thisIterationStart)
              else if (iterationHadToolCall.get())
                // Sigil #275 — the model dispatched a non-terminal tool
                // this iteration (record_consent, list_theme_files, …).
                // Its `ToolInvoke` has the default `Standard` role + the
                // agent's own participantId, so `TriggerFilter` excludes
                // it from `newTriggersExist`. But the tool call IS the
                // continuation signal — the next iteration sees the
                // tool's result in the conversation context and decides
                // what to do. `maxAgentIterations` caps runaway tool-
                // calling spirals; this branch keeps the loop alive long
                // enough for the cap to bound cost without misclassifying
                // a productive turn as "no tool call".
                Task.pure(true)
              else newTriggersExist(agent, conv, sinceTimestamp = thisIterationStart)
            shouldIterate
              // #355 — instrument the post-drain decision so a silent stall
              // is diagnosable. The trace "drain done" → "shouldIterate=X" →
              // "committed; running continuation" → "iter N+1 enter" pinpoints
              // WHERE a hang sits: no `shouldIterate` log = hung in the trigger
              // query (the in-batch decision); `shouldIterate` but no
              // `running continuation` = hung committing the batch; `running
              // continuation` but no next `iter enter` = hung in the
              // continuation (recurse / intra-turn compaction).
              .flatMap { si =>
                scribe.debug(s"runAgentLoop[${agent.id.value}/${convId.value}] iter=$iteration " +
                  s"shouldIterate=$si (iteration<max=${iteration < maxAgentIterations}, " +
                  s"forceResponseSynthesis=$forceResponseSynthesis)")
                Task.pure(si)
              }
              .flatMap {
            case true if iteration < maxAgentIterations =>
              // Bug #54 / #349 — un-stick the consumer's state at the
              // iteration boundary. Without a pulse, a multi-iteration
              // loop pins the consumer at `typing` (or whatever the last
              // streaming activity was) for the whole outer loop, so
              // clients can't render an accurate Stop button or per-turn
              // UX. #54 originally pulsed `Idle → Thinking`, but `Idle`
              // also means "turn complete" — overloading it made clients
              // reset their per-turn UI (timer, thinking buffer, Stop
              // button) on EVERY tool call (#349). Emit only the next
              // iteration's real activity (`Thinking`): the
              // `Typing → Thinking` delta is itself the visible change #54
              // needed, and `Idle` now fires solely at the genuine turn
              // end — restoring the invariant `Idle` ⇔ turn complete.
              //
              // The pulse doesn't change the AgentState event's `state`
              // (still Active — claim still held) — it mutates `activity`
              // only, so the framework's claim-lock semantics are
              // preserved. The next iteration runs in the same outer fiber.
              publish(AgentStateDelta(
                target = claimed._id,
                conversationId = convId,
                activity = Some(AgentActivity.Thinking)
              )).flatMap { _ =>
                val nextIteration = iteration + 1
                val governorContext = GovernorContext(
                  agent              = agent,
                  conversation       = conv,
                  claimed            = claimed,
                  iteration          = iteration,
                  nextIteration      = nextIteration,
                  modelProfile       = modelProfileForId(agent.modelId),
                  checkpointInterval = effectiveProgressCheckpointInterval(agent.modelId),
                  plannerCadence     = effectivePlannerCadence(agent.modelId)
                )
                // Sigil #285 — the normal continuation consults the
                // intra-turn compactor before the next iteration. When
                // budget pressure or a natural boundary fires, the
                // framework folds older this-turn events into a
                // ContextSummary so the next iteration's wire prompt is
                // smaller. The helper is a no-op when no compaction is
                // needed.
                def continueLoop: Task[Unit] =
                  maybeIntraTurnCompact(agent, convId, claimed, routedModelId.get())
                    .flatMap(_ => runAgentLoop(agent, convId, claimed, nextIteration, thisIterationStart,
                      userVisibleSeen = userVisibleSeen,
                      turnExtractorFired = turnExtractorFired,
                      failurePublished = failurePublished,
                      discoveredCapabilitiesRef = discoveredCapabilitiesRef,
                      toolResultCacheRef = toolResultCacheRef,
                      healedThisTurn = healedThisTurn,
                      healCorrelationId = healCorrelationId,
                      overflowCompactions = overflowCompactions))
                // Fold the boundary guards in list order; the first
                // non-Proceed vote wins and later governors don't run at
                // this boundary. Laziness is load-bearing — the progress
                // governor's checkpoint (an LLM round-trip) must not fire
                // at a boundary the budget gate already claimed.
                def foldGovernors(remaining: List[TurnGovernor]): Task[GovernorVote] = remaining match {
                  case Nil => Task.pure(GovernorVote.Proceed)
                  case governor :: rest => governor.evaluate(governorContext).flatMap {
                    case GovernorVote.Proceed => foldGovernors(rest)
                    case vote                 => Task.pure(vote)
                  }
                }
                foldGovernors(turnGovernors).flatMap {
                  case GovernorVote.Proceed => Task.pure(continueLoop)
                  case GovernorVote.Intervene(publishDirective, forceSynthesis) =>
                    publishDirective.map(_ => forceSynthesis.fold(continueLoop)(recurseForced))
                }
              }
            case true if !forceResponseSynthesis =>
              // Sigil bug #125 — cap hit on a normal iteration. Instead of
              // throwing AgentRunawayException and discarding whatever
              // context the agent has gathered, inject a Tool-role
              // "cap reached, respond NOW" diagnostic and run ONE more
              // forced-synthesis iteration with `tool_choice: respond`.
              // The agent synthesises a reply from the conversation it
              // already built up. Only fall through to the hard throw
              // if THAT iteration also fails (`case true if
              // forceResponseSynthesis` below).
              // Synthetic ToolInvoke parent so the Tool-role diagnostic
              // satisfies the framework's "every Tool-role event MUST
              // carry origin" invariant. Marked `internal = true` so
              // client UIs filter it out of the user-facing chip
              // stream — this is framework-internal model nudging.
              val capDirective = Directive.CapReached(maxAgentIterations)
              val capInvoke = sigil.orchestrator.SyntheticDiagnostic
                .invoke(capDirective, agent.id, convId, conv.currentTopicId)
              val capDiagnostic = Message(
                participantId  = agent.id,
                conversationId = convId,
                topicId        = conv.currentTopicId,
                content        = Vector(_root_.sigil.tool.model.ResponseContent.Text(capDirective.render)),
                state          = EventState.Complete,
                role           = MessageRole.Tool,
                visibility     = MessageVisibility.Agents,
                origin         = Some(capInvoke._id)
              )
              publish(capInvoke).flatMap(_ => publish(capDiagnostic)).flatMap { _ =>
                // Bug #128 composition — when `escalateOnCapHit` is on,
                // bump the cached complexity tier one step up before
                // the forced-synthesis turn. The recovery attempt then
                // resolves to whichever model in the chain supports
                // the elevated tier. No-op when the flag is off.
                escalateForCapHit(convId).map(_ =>
                  recurseForced(ForcedSynthesisReason.CapHit))
              }
            case true =>
              // Cap hit on the forced-synthesis iteration too. The model
              // failed to call `respond` despite `tool_choice` pinning it
              // (very weak / non-instruction-following local models, or
              // a buggy provider). Soft path exhausted — surface the hard
              // failure so the calling fiber's error boundary logs it.
              // Routed through the handleError below for the failure
              // publish + post-commit terminal release.
              Task.error(buildRunawayException(
                agent, conv, iteration, maxAgentIterations, forcedReason))
            case false =>
              // No more triggers to chase, no continue requested. If the
              // agent already spoke this turn we're done. Otherwise the
              // turn ended with no tool call — a transient hiccup most
              // of the time (reasoning models drop the tool call after
              // their reasoning block).
              //
              // Sigil #257 — recover in two stages. First, retry up to
              // `noToolCallRetryLimit` times with the FULL roster +
              // normal `tool_choice` intact: a plain re-prompt usually
              // self-corrects. Only once those retries are exhausted do
              // we force ONE iteration with the roster restricted to the
              // respond family so the model MUST emit a real reply
              // (respond / respond_options / … / no_response). If THAT
              // forced iteration also fails to call respond, the
              // `forceResponseSynthesis` branch raises
              // AgentRunawayException — model is broken; surface the
              // hard failure instead of papering over it.
              if (userVisibleSeen.get()) {
                scribe.debug(s"runAgentLoop[${agent.id.value}/${convId.value}] iter=$iteration yielding terminate continuation")
                Task(terminate())
              }
              else if (forceResponseSynthesis)
                // Routed through the handleError below for the failure
                // publish + post-commit terminal release.
                Task.error(buildRunawayException(
                  agent, conv, iteration, maxAgentIterations, forcedReason))
              else if (noToolCallRetries < noToolCallRetryLimit) {
                // Recover a transient reasoning-model tool-call drop with a
                // plain re-prompt — in BOTH user-facing and worker
                // conversations. A worker-conv agent that genuinely meant
                // to reply (e.g. a supervisor answering the worker) but
                // dropped the call must get these retries, or the task
                // stalls silently.
                scribe.warn(
                  s"runAgentLoop[${agent.id.value}/${convId.value}] iter=$iteration returned no " +
                    s"tool call; retrying with the full roster (retry ${noToolCallRetries + 1}/$noToolCallRetryLimit)"
                )
                Task.pure(recurseFullRosterRetry)
              }
              else if (isDirectedWorkerConversation(conv)) {
                // #327 chat-fidelity — once the drop-recovery retries are
                // exhausted in a directed worker sub-conversation (linked to
                // a parent, two+ agents), a woken agent that still has
                // nothing to add simply RESTS, exactly like a user who
                // doesn't reply. Replacing forced synthesis with rest is the
                // natural termination: the supervisor relays its result up
                // to the parent and, with nothing left for the worker,
                // settles silently here; the worker (un-addressed) is never
                // re-woken. (User-facing conversations force a reply below,
                // where the agent owes the user an answer.) skipFallback —
                // resting must NOT synthesize the #282 "turn ended without a
                // reply" placeholder; rest is the intended outcome.
                scribe.debug(
                  s"runAgentLoop[${agent.id.value}/${convId.value}] no tool call after " +
                    s"$noToolCallRetries retries in a worker sub-conversation; settling silently (chat-fidelity rest)"
                )
                Task(terminate(skipFallback = true))
              }
              else
                Task.pure(recurseForced(ForcedSynthesisReason.NoToolCall))
            }
          }
        }
    }.handleError { t =>
      // Sigil #313 — reactive self-heal: BEFORE the standard failure
      // surface fires, walk `healingStrategies` looking for a match.
      // First match wins. In `HealingMode.Recover` (production
      // default), the strategy publishes its corrections, the audit
      // triple (CorruptionDetected → Healed) lands, and we recurse
      // the iteration ONCE. In `HealingMode.Strict` (TestSigil
      // default, dev/CI), the corruption is recorded but the heal
      // does NOT run and the original error re-throws — so the
      // developer hits the failure and gets a chance to fix the
      // underlying cause rather than rely on the patch.
      val tryHeal: Task[Option[Task[Unit]]] = tryHealAgentLoopError(
        agent             = agent,
        convId            = convId,
        claimed           = claimed,
        thrown            = t,
        healedThisTurn    = healedThisTurn,
        healCorrelationId = healCorrelationId
      )
      tryHeal.flatMap {
        case Some(retryTask) =>
          // Heal applied; recurse the iteration. The recurse runs as
          // the post-commit continuation so the heal's published
          // events are durable before the retry reads them. Wrap in
          // Task.pure so it becomes the iterationStep's continuation
          // rather than executing inline.
          Task.pure(retryTask)
        case None if stopFlag.exists(_.requested) || stopLatches.containsKey(convId) =>
          // Sigil #415 — the user stopped this conversation; whatever
          // error the stop's stream-cancel (or the wedged state that
          // prompted the stop) produced is a byproduct of the stop, not
          // a failure to surface. End quietly: no Failure bubble, no
          // recovery machinery, no re-raise — just release the claim.
          // But ALWAYS settle in-flight tool invokes first: a force-Stop
          // that cancels the drain mid-tool kills exactly the
          // continuation that would have settled the invoke, and this
          // exit was the one stop route that skipped the dangling settle
          // — leaving the invoke Active forever (spinner never stops,
          // permanently-unsettled row until boot reconciliation).
          scribe.info(
            s"runAgent for ${agent.id.value} in ${convId.value} ended by user Stop " +
              s"(suppressing ${t.getClass.getSimpleName}: ${Option(t.getMessage).getOrElse("")})")
          Task.pure(
            settleDanglingToolInvokes(convId, agent.id).handleError(_ => Task.unit)
              .flatMap(_ => terminate(skipFallback = true).handleError(_ => Task.unit)))
        case None if _root_.sigil.provider.Provider.isContextOverflow(t)
                  && overflowCompactions < maxOverflowCompactions =>
          // Sigil #413 — the provider hard-rejected the request as over
          // the model's context window (or the pre-flight gate threw
          // after exhausting its own shed). The wire is ground truth
          // that the estimator under-counted, so instead of failing the
          // turn, re-run the iteration with an emergency refit: the
          // recursion carries `overflowCompactions + 1`, which sets
          // `emergencyContextFactor = 0.5^n` on the rebuilt TurnContext
          // — the full shed cascade runs against HALF the window (then a
          // quarter), and its stage-3 persistence (summary + clearedAt
          // advance) keeps the NEXT turn under the wall too. Bounded by
          // `maxOverflowCompactions`; exhaustion falls through to the
          // clean terminal failure below on the next propagation.
          scribe.warn(
            s"Sigil #413 — context overflow for ${agent.id.value} in ${convId.value} " +
              s"(attempt ${overflowCompactions + 1}/$maxOverflowCompactions): re-running iteration " +
              s"with emergency refit factor ${math.pow(0.5, overflowCompactions + 1)}")
          Task.pure(runAgentLoop(
            agent                     = agent,
            convId                    = convId,
            claimed                   = claimed,
            iteration                 = iteration + 1,
            sinceTimestamp            = sinceTimestamp,
            greeting                  = false,
            userVisibleSeen           = userVisibleSeen,
            turnExtractorFired        = turnExtractorFired,
            failurePublished          = failurePublished,
            forceResponseSynthesis    = forceResponseSynthesis,
            forcedReason              = forcedReason,
            noToolCallRetries         = noToolCallRetries,
            discoveredCapabilitiesRef = discoveredCapabilitiesRef,
            toolResultCacheRef        = toolResultCacheRef,
            healedThisTurn            = healedThisTurn,
            healCorrelationId         = healCorrelationId,
            overflowCompactions       = overflowCompactions + 1
          ))
        case None =>
          // No heal applied (no match, healing exhausted, or strict
          // refusal). Fall through to the standard failure path.
          //
          // Any unhandled failure mid-turn — surface the failure to the
          // user so the chat doesn't go silent (Bug #6), then release the
          // lock so the agent isn't stuck Active forever, then re-raise
          // so the fiber's error boundary logs it. Each step is
          // independently best-effort: a downstream failure (DB
          // unavailable, hub closed, missing topic, etc.) doesn't mask
          // the original error.
          //
            //
          scribe.error(s"runAgent failed for ${agent.id.value} in ${convId.value}", t)
          val publishOnce: Task[Unit] =
            if (failurePublished.compareAndSet(false, true))
              publishFailureMessage(agent, convId, t).handleError(_ => Task.unit)
            else Task.unit
          // The Failure Message persists inside the batched transaction;
          // the terminal release + re-raise run as the post-commit
          // continuation so the Idle/Complete signal never races the commit.
          publishOnce.map(_ =>
            terminate(skipFallback = true).handleError(_ => Task.unit).flatMap(_ => Task.error(t)))
      }
    }
    // Hold one `events` transaction open across this iteration's
    // work — every `publish` → `apply` and every event read routed
    // through `eventsTransaction(convId)` joins it — then commit
    // once at scope exit. `iterationStep` yields the continuation
    // Task (the next iteration, or the terminal `terminate()`);
    // running it AFTER `withBatchedEvents` returns means the next
    // iteration — or the terminal release — starts only once this
    // iteration's writes are committed, so independent reads see the
    // durable data.
    withDB(_.withBatchedEvents(convId)(iterationStep)).flatMap { continuation =>
      // #355 — the iteration's events are committed here; the continuation
      // (next iteration's runAgentLoop, or the terminal release) runs next.
      // Logging the handoff distinguishes a commit hang (this line never
      // prints after `shouldIterate=…`) from a continuation hang (this line
      // prints but the next `iter … enter` / terminal release never does).
      scribe.debug(s"runAgentLoop[${agent.id.value}/${convId.value}] iter=$iteration committed; running continuation")
      continuation
    }
  }


  private final def buildRunawayException(agent: AgentParticipant,
                                          conv: Conversation,
                                          iteration: Int,
                                          maxIter: Int,
                                          reasonOpt: Option[ForcedSynthesisReason]): AgentRunawayException = {
    val reason = reasonOpt.getOrElse(ForcedSynthesisReason.CapHit)
    val convPart = s"in conversation ${conv._id.value}"
    val cause = reason match {
      case ForcedSynthesisReason.CapHit =>
        s"hit maxAgentIterations ($maxIter) and the forced-synthesis turn at iteration $iteration " +
          s"also failed to call `respond`. Check LLM behavior or raise the cap."
      case ForcedSynthesisReason.NoToolCall =>
        // Sigil #273 — the narrowed signal: model emitted zero `tool_use`
        // blocks for `noToolCallRetryLimit + 1` consecutive iterations
        // despite `tool_choice: required`, and the forced respond-only
        // retry also produced no tool call. Parse failures / unknown tool
        // names land elsewhere (Tool-role Failure pairing → normal retry)
        // and do not trip this path.
        s"emitted zero `tool_use` blocks across $iteration consecutive iterations (cap $maxIter) despite " +
          s"`tool_choice: required`, and the forced respond-only recovery turn also produced no tool call. " +
          "Model is not following the tool-use protocol — verify provider plumbing, `tool_choice` wiring, " +
          "or downgrade to a model that honors the contract."
      case ForcedSynthesisReason.StallIntervention =>
        s"stalled at iteration $iteration of cap $maxIter (progress-checkpoint intervention) and " +
          s"the forced-synthesis recovery turn also failed to call `respond`. Check LLM behavior."
      case ForcedSynthesisReason.BudgetCeiling =>
        s"crossed its hard spend ceiling at iteration $iteration and the forced wrap-up turn also " +
          s"failed to call `respond`. Check LLM behavior; the spend gate held either way."
    }
    new AgentRunawayException(s"Agent ${agent.id.value} $cause $convPart", reason)
  }

  /** Cached `(expiry, names)` for [[mutatingToolNames]]. */
  private val mutatingToolNamesCache: java.util.concurrent.atomic.AtomicReference[(Long, Set[String])] =
    new java.util.concurrent.atomic.AtomicReference((0L, Set.empty[String]))

  /** How long [[mutatingToolNames]] holds its snapshot before
    * re-reading the tool catalog. A tool's effect profile is a
    * compile-time property of its spec — the only churn is a
    * registration / de-registration, so a few minutes of staleness
    * only ever mis-labels a brand-new tool's first turns. */
  def mutatingToolNamesTtlMs: Long = 5 * 60 * 1000L

  /** Names of every persisted tool whose effect profile mutates,
    * memoized for [[mutatingToolNamesTtlMs]]. The per-turn extraction
    * pathway needs this to decide whether a settled turn changed
    * external state; decoding the whole catalog per turn made a
    * hot-path cost out of a value that changes only when the tool
    * registry does. Lenient decode — a de-registered tool's stale row
    * must not abort extraction. */
  def mutatingToolNames: Task[Set[String]] = Task.defer {
    val now = System.currentTimeMillis()
    val (expiry, cached) = mutatingToolNamesCache.get()
    if (now < expiry) Task.pure(cached)
    else withDB(_.tools.transaction(_.jsonStream.toList)).map(Sigil.decodeToolsLeniently).map { toolRows =>
      val names = toolRows.iterator.filter(_.spec.profile.effect.mutates).map(_.name.value).toSet
      mutatingToolNamesCache.set((now + mutatingToolNamesTtlMs, names))
      names
    }
  }

  /** Bug #149 — assemble the per-turn extractor's `(userMessage,
    * agentResponse)` arguments from the conversation's events since
    * the turn started, and fire `memoryExtractor.extract`. Runs once
    * per user turn at the agent loop's terminate boundary (see
    * `terminate()` inside `runAgentLoop`). Background fiber —
    * failures are logged + swallowed; the agent's settle path never
    * blocks on extraction. */
  /** Assemble the reply-suggestion consult's inputs from the settled
    * turn and dispatch it. Fires once per turn at the agent loop's
    * terminate boundary, on a background fiber — every failure is
    * logged at WARN and swallowed so the turn's settle path never
    * depends on it.
    *
    * Skipped entirely for worker scratchpads (`parentConversationId`
    * set) and staging conversations: nobody is composing a reply
    * there. */
  private final def fireReplySuggestions(agent: AgentParticipant,
                                         convId: Id[Conversation],
                                         turnStartTimestamp: Timestamp): Task[Unit] =
    replySuggestions match {
      case None => Task.unit
      case Some(config) =>
        withDB(_.conversations.transaction(_.get(convId)))
          .flatMap {
            case Some(conv) if conv.parentConversationId.isEmpty && conv.stagingFor.isEmpty =>
              suggestRepliesFor(agent, conv, config, turnStartTimestamp)
            case _ => Task.unit
          }
          .handleError { e =>
            Task(scribe.warn(s"reply-suggestion consult failed for conversation ${convId.value}: ${e.getMessage}"))
          }
    }

  private final def suggestRepliesFor(agent: AgentParticipant,
                                      conv: Conversation,
                                      config: ReplySuggestionsConfig,
                                      turnStartTimestamp: Timestamp): Task[Unit] = {
    val convId = conv._id
    withDB(_.conversationEventsConsistent(convId)).flatMap { all =>
      val convEvents = all.iterator
        .filter(_.state == EventState.Complete)
        .toVector
        .sortBy(_.timestamp.value)
      val turnReplies = convEvents.iterator.collect {
        case m: Message
          if m.timestamp.value >= turnStartTimestamp.value && m.participantId == agent.id &&
            m.role == MessageRole.Standard => m
      }.toVector
      val optionsOffered = turnReplies.exists(m => contentOffersOptions(m.content))
      val settledReply = turnReplies.filter(m => allowsNonAgentViewer(m.visibility, conv)).lastOption
      settledReply match {
        case Some(reply) if !(config.skipWhenOptionsOffered && optionsOffered) =>
          // Render rather than concatenate text blocks: a reply built
          // from options / fields / a table carries no Text block at
          // all, and the prediction needs to read what the user saw.
          val agentReply = PlainTextRenderer.render(reply.content).trim
          // The triggering message precedes the agent's claim, so it
          // lives outside the turn window — take the conversation's
          // most recent user-authored Message instead.
          val userEvent = convEvents.reverseIterator.collectFirst {
            case m: Message if !m.participantId.isInstanceOf[AgentParticipantId] && m.role == MessageRole.Standard => m
          }
          if (agentReply.isEmpty) Task.unit
          else framesFor(convId).flatMap { frames =>
            val anchored = (reply._id :: userEvent.map(_._id).toList).toSet
            val tail = frames.filterNot(f => anchored.contains(f.sourceEventId)).takeRight(replySuggestionFrameTail)
            runReplySuggestionConsult(agent, config, ReplySuggestionContext(
              conversationId = convId,
              replyMessageId = reply._id,
              agentReply     = agentReply,
              userMessage    = userEvent.map(m => PlainTextRenderer.render(m.content).trim).getOrElse(""),
              recentExchange = TranscriptRenderer.render(tail).trim,
              count          = config.count
            ))
          }
        case _ => Task.unit
      }
    }
  }

  private final def runReplySuggestionConsult(agent: AgentParticipant,
                                              config: ReplySuggestionsConfig,
                                              ctx: ReplySuggestionContext): Task[Unit] = {
    val userPrompt = config.promptOverride.fold(renderReplySuggestionPrompt(ctx))(_(ctx))
    ConsultTool
      .invokeRouted[SuggestReplyInput](
        sigil           = this,
        tool            = SuggestReplyTool,
        chain           = List(agent.id),
        fallbackModelId = config.fallbackModelId,
        systemPrompt =
          "You predict what a person will type next in their conversation with an assistant. " +
            "You answer only by calling `suggest_reply`.",
        userPrompt = userPrompt
      )
      .flatMap {
        case Some(result) =>
          val cleaned = result.suggestions.iterator
            .map(cleanReplySuggestion)
            .filter(_.nonEmpty)
            .distinct
            .take(ctx.count)
            .toList
          if (cleaned.isEmpty) Task.unit
          else publish(SuggestedReplies(ctx.conversationId, ctx.replyMessageId, cleaned))
        case None => Task.unit
      }
  }

  /** The reply-suggestion consult's user prompt. Branches on
    * `ctx.count`: one suggestion is a type-ahead completion, several
    * are candidates that must differ in intent. Public so a
    * [[ReplySuggestionsConfig.promptOverride]] can extend the
    * framework's phrasing rather than replace it wholesale. */
  def renderReplySuggestionPrompt(ctx: ReplySuggestionContext): String = {
    val sb = new StringBuilder
    if (ctx.recentExchange.nonEmpty) sb.append("Earlier in the conversation:\n").append(ctx.recentExchange).append("\n\n")
    if (ctx.userMessage.nonEmpty) sb.append("The person just said:\n").append(ctx.userMessage).append("\n\n")
    sb.append("The assistant replied:\n").append(ctx.agentReply).append("\n\n")
    sb.append(
      if (ctx.count == 1)
        "Predict the single most likely message this person sends next. Write it the way they would type it — " +
          "first person, one short line, no quotation marks and no markup. Return exactly one suggestion."
      else
        s"Predict ${ctx.count} candidate next messages with DISTINCT intents — one that continues the current " +
          "thread, one that redirects to something else, one that digs into a specific detail of the reply. " +
          "Write each the way this person would type it — first person, one short line, no quotation marks and " +
          s"no markup. Return exactly ${ctx.count} suggestions, most likely first."
    )
    sb.toString
  }

  private def cleanReplySuggestion(raw: String): String = {
    val stripped = raw.trim.stripPrefix("- ").stripPrefix("* ").trim
    if (stripped.length >= 2 && stripped.startsWith("\"") && stripped.endsWith("\"")) stripped.substring(1, stripped.length - 1).trim
    else stripped
  }

  /** True when at least one non-agent participant of `conv` may see a
    * message tagged with `visibility`. Falls back to the tag alone for
    * conversations with no human participant on the roster (a
    * signal-driven UI can still be watching). */
  private def allowsNonAgentViewer(visibility: MessageVisibility, conv: Conversation): Boolean = {
    val humans = conv.participants.iterator.map(_.id).filterNot(_.isInstanceOf[AgentParticipantId]).toList
    if (humans.nonEmpty) humans.exists(v => visibilityAllows(visibility, v))
    else visibility match {
      case MessageVisibility.Agents            => false
      case MessageVisibility.Participants(ids) => ids.exists(!_.isInstanceOf[AgentParticipantId])
      case _                                   => true
    }
  }

  /** True when the reply offered the user structured choices —
    * [[ResponseContent.Options]] either at the top level or nested in
    * a [[ResponseContent.Card]] emitted by `respond_card` /
    * `respond_cards`. */
  private def contentOffersOptions(content: Vector[ResponseContent]): Boolean = content.exists {
    case _: ResponseContent.Options => true
    case c: ResponseContent.Card    => contentOffersOptions(Card.typedSections(c))
    case _                          => false
  }

  private final def firePostTurnExtraction(agent: AgentParticipant,
                                           convId: Id[Conversation],
                                           turnStartTimestamp: Timestamp): Task[Unit] =
    withDB(_.conversationEventsConsistent(convId)).flatMap { all =>
      val convEvents = all.iterator
        .filter(_.state == EventState.Complete)
        .toVector
        .sortBy(_.timestamp.value)
      // The turn window: events at or after `claimed.timestamp`.
      val turnEvents = convEvents.filter(_.timestamp.value >= turnStartTimestamp.value)
      // Agent response: text frames the agent authored DURING this turn.
      val agentResponse = turnEvents.iterator
        .collect {
          case m: Message if m.participantId == agent.id && m.role == MessageRole.Standard =>
            m.content.collect { case sigil.tool.model.ResponseContent.Text(t) => t }.mkString("")
        }
        .mkString("\n")
        .trim
      // User message: the most recent user-authored Message in the
      // entire conversation. The triggering message PRECEDES the
      // agent's claim timestamp (it's what woke the agent), so
      // turn-window filtering would miss it.
      val userMessageEvent = convEvents.reverseIterator
        .collectFirst {
          case m: Message if !m.participantId.isInstanceOf[sigil.participant.AgentParticipantId] && m.role == MessageRole.Standard => m
        }
      val userMessage = userMessageEvent
        .map(_.content.collect { case sigil.tool.model.ResponseContent.Text(t) => t }.mkString(""))
        .getOrElse("")
        .trim
      // Provenance: the extraction window is the triggering user
      // message plus the turn's events.
      val windowEventIds = (userMessageEvent.map(_._id).toList ::: turnEvents.iterator.map(_._id).toList).distinct
      // Structured turn evidence: names of tools that settled
      // successfully during the turn with a mutating effect profile.
      // Resolved from the persisted tool catalog only when the turn
      // actually invoked tools.
      val successfulInvokes = turnEvents.collect {
        case ti: sigil.event.ToolInvoke if ti.outcome == sigil.event.ToolOutcome.Success && !ti.internal => ti
      }
      val settledMutations: Task[List[sigil.tool.ToolName]] =
        if (successfulInvokes.isEmpty) Task.pure(Nil)
        else mutatingToolNames.map { mutating =>
          successfulInvokes.iterator
            .filter(ti => mutating.contains(ti.toolName.value))
            .map(_.toolName)
            .toList
            .distinct
        }
      if (userMessage.isEmpty && agentResponse.isEmpty) Task.unit
      else settledMutations
        .flatMap { mutations =>
          memoryExtractor.extractTurn(
            sigil          = this,
            conversationId = convId,
            modelId        = agent.modelId,
            chain          = List(agent.id),
            turn           = sigil.conversation.compression.extract.ExtractionTurn(
              userMessage      = userMessage,
              agentResponse    = agentResponse,
              sourceEventIds   = windowEventIds,
              settledMutations = mutations
            )
          )
        }
        .unit
        .handleError { e =>
          Task(scribe.warn(s"MemoryExtractor failed for conversation ${convId.value}: ${e.getMessage}"))
        }
    }

  /** Sigil #285 — consult [[intraTurnCompactor]] at an iteration
    * boundary and, if it fires, run [[intraTurnCompressor.compressCovering]]
    * to fold this turn's eligible events into a [[ContextSummary]]
    * tagged with their event ids. The next iteration's curator picks
    * the summary up and filters those events out of the wire prompt,
    * shrinking the per-iteration cost without touching the durable
    * event log.
    *
    * The decision is computed from what the NEXT prompt will actually
    * carry, against the model that will actually receive it:
    *
    *   - the slice is the turn's frame-bearing events, oldest-first —
    *     the order every [[sigil.conversation.compression.CompactionInvariant]]
    *     reads ([[sigil.conversation.compression.CompactionInvariant.RecentTail]]
    *     protects the END of the vector, the claim anchor is found from
    *     the START). Frameless control events render nothing, so
    *     counting them would both inflate the estimate and let them
    *     occupy the protected tail in place of the results the agent
    *     just read;
    *   - events an earlier fold already subsumed are excluded from the
    *     estimate — they are no longer in the prompt, and counting them
    *     would latch the predicate true for the rest of the turn;
    *   - the threshold and the summarizing model come from `routedModelId`
    *     — the model this turn actually routes to. The agent's nominal id
    *     is frequently a small-context default, and after a mid-turn tier
    *     escalation it is not even the tier in play.
    *
    * Best-effort: a failure inside the compactor or compressor is
    * logged at WARN and swallowed — the agent loop continues with
    * the un-folded history (degraded but functional). The compactor
    * predicate is cheap; the compress call only fires when the
    * predicate returns true AND there's foldable content. */
  private final def maybeIntraTurnCompact(agent: AgentParticipant,
                                          convId: Id[Conversation],
                                          claimed: AgentState,
                                          routedModelId: Id[Model]): Task[Unit] = Task.defer {
    val compactor = intraTurnCompactor
    eventsFor(convId, minTimestamp = Some(claimed.timestamp)).flatMap { page =>
      val turnEvents = page.events.toVector.filter(_.contextFrame.nonEmpty)
      if (turnEvents.isEmpty) Task.unit
      else summariesFor(convId).flatMap { summaries =>
        val alreadyFolded = summaries.iterator.flatMap(_.coversEventIds).toSet
        val estimated = turnEvents.iterator
          .filterNot(e => alreadyFolded.contains(e._id))
          .map(e => sigil.tokenize.HeuristicTokenizer.count(eventTextForHeuristic(e)))
          .sum
          .toLong
        val threshold = compressionTriggerTokens(routedModelId)
        if (!compactor.shouldCompact(turnEvents, estimated, threshold)) Task.unit
        else {
          val ctx = _root_.sigil.conversation.compression.TurnEventsContext(
            conversationId       = convId,
            claimedAt            = Some(claimed.timestamp),
            agentId              = Some(agent.id),
            deliveredToolResults = deliveredToolResultIds(convId)
          )
          val coverIds = compactor.selectFoldable(turnEvents, ctx).toSet
          if (coverIds.isEmpty) Task.unit
          else framesFor(convId).flatMap { allFrames =>
            val coveredFrames = allFrames.filter(f => coverIds.contains(f.sourceEventId))
            if (coveredFrames.isEmpty) Task.unit
            else intraTurnCompressor
              .compressCovering(
                sigil           = this,
                callerModelId   = routedModelId,
                chain           = List(agent.id),
                frames          = coveredFrames,
                conversationId  = convId,
                coversEventIds  = coverIds.toList
              )
              .map(_ => ())
              .handleError(t => Task {
                scribe.warn(s"Sigil #285 — intra-turn compaction failed for ${agent.id.value}/${convId.value}: ${t.getMessage}")
              })
          }
        }
      }
    }
  }

  /** Heuristic text rendering for an Event purely for the
    * intra-turn-compactor's size estimation. Doesn't need to match
    * any provider's exact wire shape — only stable enough that a
    * vector of these is a fair proxy for cumulative cost. */
  private def eventTextForHeuristic(e: Event): String = e match {
    case m: Message =>
      m.content.iterator.map {
        case t: sigil.tool.model.ResponseContent.Text => t.text
        case other => other.toString
      }.mkString(" ")
    case ti: ToolInvoke =>
      // A settled invoke carries its own result; on the unified shape
      // that result IS the bulk of the exchange's wire cost, so an
      // estimate that reads only the call side under-counts a
      // tool-heavy turn by most of its weight.
      s"${ti.toolName.value} ${ti.input.fold("")(_.toString)} ${ti.output match {
        case sigil.tool.ToolOutput.Pending => ""
        case out                           => out.toString
      }}"
    case other => other.toString
  }

  /** Publish a `Failure`-content Message into the conversation when
    * `runAgentLoop` crashes mid-turn. Lets clients render a red error
    * bubble in place of the frozen "still typing" indicator the
    * activity-state delta from `releaseClaim` would leave on its own.
    *
    * Best-effort: degenerate states (conversation gone, no topics) skip
    * publication rather than fabricate a topic id; the caller's
    * `releaseClaim` still flips the agent state to Idle/Complete. */
  /** Sigil bug #282 — publish a synthetic user-visible Message when
    * the agent loop terminates without any respond having fired.
    * Composes its body from the most recent [[sigil.event.ProgressCheckpoint]]
    * the agent persisted this turn (if any) so the user sees the
    * agent's own stated status / stuck-on reason instead of a blank
    * spinner. Falls back to a generic placeholder when no checkpoint
    * exists for this turn.
    *
    * Idempotent in spirit — callers should only invoke when
    * `userVisibleSeen` is false; the helper itself does NOT re-check
    * to keep the contract surfaced at the call site. */
  private final def synthesizeFallbackRespond(agent: AgentParticipant,
                                              convId: Id[Conversation]): Task[Unit] =
    latestCheckpointStatus(agent.id, convId).flatMap { checkpoint =>
      val body = checkpoint match {
        case Some(status) =>
          s"I wasn't able to complete the request before the turn ended. Status: $status"
        case None =>
          "The agent's turn ended without producing a reply. Please re-prompt or clarify the request."
      }
      withDB(_.conversations.transaction(_.get(convId))).flatMap {
        case None       => Task.unit
        case Some(conv) => conv.topics.headOption match {
          case None        => Task.unit
          case Some(topic) =>
            publish(Message(
              participantId  = agent.id,
              conversationId = convId,
              topicId        = topic.id,
              content        = Vector(ResponseContent.Text(body)),
              disposition    = sigil.event.MessageDisposition.Failure(recoverable = true),
              state          = EventState.Complete,
              role           = MessageRole.Standard,
              // Sigil bug #284 — stamp `source` so consumers render the
              // framework-synthesised fallback distinctly from a real
              // agent reply.
              source         = Some("orchestrator-fallback-respond")
            )).map(_ => ())
        }
      }
    }.handleError { e =>
      Task(scribe.warn(
        s"synthesizeFallbackRespond failed for ${agent.id.value}/${convId.value}: ${e.getMessage}"
      ))
    }

  private final def publishFailureMessage(agent: AgentParticipant,
                                          convId: Id[Conversation],
                                          t: Throwable): Task[Unit] =
    withDB(_.conversations.transaction(_.get(convId))).flatMap {
      case None => Task.unit
      case Some(conv) => conv.topics.headOption match {
        case None        => Task.unit
        case Some(topic) =>
          // Sigil #413 — a context overflow that exhausted the emergency
          // recovery gets a user-readable explanation, not a raw wire
          // blob. Everything else runs through the #410 scrub so vendor
          // support URLs and similar noise never reach the chat; the
          // provider name isn't resolvable at this seam, so the scrub
          // receives the "provider" placeholder.
          val reason =
            if (_root_.sigil.provider.Provider.isContextOverflow(t))
              "The conversation has grown past the model's context window, and automatic emergency " +
                "compaction could not bring it back under the limit. Start a new conversation, or reduce " +
                "the request (fewer attached files / shorter input) and try again."
            else if (_root_.sigil.provider.Provider.isInvalidRequest(t))
              // A non-overflow invalid_request 400 — malformed content the
              // model API refused. Surface the provider's concise message
              // (it names the offending part, e.g. an empty image source)
              // instead of the raw wire blob the #410 scrub would pass
              // through: the scrub strips vendor identity, not JSON noise.
              _root_.sigil.provider.Provider.invalidRequestDetail(t) match {
                case Some(detail) =>
                  s"The provider rejected the request as invalid: ${sanitizeProviderError("provider", detail)}"
                case None =>
                  "The provider rejected the request as invalid (malformed content in the conversation)."
              }
            else sanitizeProviderError("provider", Option(t.getMessage).filter(_.nonEmpty)
              .map(m => s"${t.getClass.getSimpleName}: $m")
              .getOrElse(t.getClass.getSimpleName))
          val ec = sigil.event.ErrorContext.classify(t)
          // Sigil bug #282 — enrich the user-facing failure body with
          // the agent's most recent self-reported status (from a
          // ProgressCheckpoint event) when available. The agent
          // typically knows what it was working on / stuck on; surfacing
          // that text alongside the exception class makes the failure
          // bubble actionable instead of just technical.
          latestCheckpointStatus(agent.id, convId).flatMap { checkpoint =>
            val body = checkpoint match {
              case Some(status) => s"$reason\n\nLast reported status: $status"
              case None         => reason
            }
            publish(Message(
              participantId  = agent.id,
              conversationId = convId,
              topicId        = topic.id,
              content        = Vector(sigil.tool.model.ResponseContent.Text(body)),
              disposition    = sigil.event.MessageDisposition.Failure(
                // Sigil #376 — a runaway/stall terminal hands back as
                // recoverable (a follow-up user message re-engages the
                // agent); a genuine crash stays non-recoverable.
                recoverable  = Sigil.isStallFailure(t),
                errorContext = Some(ec)
              ),
              state          = EventState.Complete,
              role           = MessageRole.Standard
            )).map(_ => ())
          }
      }
    }

  private final def releaseClaim(claimed: AgentState): Task[Unit] = {
    // Always-run cleanup — the flag must never leak even if the
    // Idle/Complete publish fails (broadcaster error, DB error, etc.).
    // A leaked flag is a minor map entry, but across many failures it'd
    // accumulate and match future Stop events against a non-existent
    // claim.
    val cleanup = Task {
      stopFlags.remove(claimed._id)
      checkpointStates.remove(claimed._id)
      activeClaims.remove(claimed._id)
      ()
    }
    // Founding-turn topic re-stamp runs before the Idle delta so
    // clients receive the turn's TopicDeltas while the turn is still
    // visually "settling". The turn log closes here regardless of
    // sweep outcome; best-effort — a sweep failure never blocks the
    // release.
    Task(Option(activeTurnEvents.remove(claimed.conversationId)))
      .flatMap {
        case Some(turnLog) => restampTopicFoundingTurn(claimed.conversationId, turnLog)
        case None          => Task.unit
      }
      .handleError(t => Task(scribe.warn(
        s"founding-turn topic re-stamp failed for ${claimed.conversationId.value}: ${t.getMessage}")))
      .flatMap(_ => publish(AgentStateDelta(
        target = claimed._id,
        conversationId = claimed.conversationId,
        activity = Some(AgentActivity.Idle),
        state = Some(EventState.Complete))))
      .flatMap(_ => closeTurnPhases(claimed.conversationId, claimed.agentId))
      .flatMap(_ => cleanup)
      .handleError(t => cleanup.flatMap(_ => Task.error(t)))
  }

  /**
   * Re-home the founding turn of a topic switch onto the topic it
   * founded. A [[TopicChange]] Switch lands at the END of its turn —
   * the classifier only decides once the respond proposes a label —
   * so every event of the turn that CAUSED the change (the triggering
   * user message included) was stamped with the previous topic, and
   * the misattribution would otherwise be permanent: the divider
   * claims a boundary the stamps beside it contradict, and anything
   * topic-scoped (per-topic history, topic summaries feeding
   * compression) attributes the new topic's most defining content to
   * the topic before it.
   *
   * Runs at claim release (every terminal exit of the agent loop),
   * consuming the [[TurnEventLog]] the claim opened: the turn's event
   * ids and their original topic stamps were recorded as they
   * published (plus the trigger events, recorded at context build),
   * so the sweep needs no store query — the split store's index
   * refresh is asynchronous and a turn-end query can miss the turn's
   * own tail. Each Switch folds a [[TopicDelta]] onto every recorded
   * event still carrying the pre-switch topic — persistence,
   * broadcast, and replay all ride the standard Delta plumbing.
   *
   * Events from before the turn keep their stamps — they legitimately
   * belong to the previous topic. Rename changes need no sweep (same
   * topic id), and the bootstrap-era rename path never emits a
   * Switch.
   */
  private final def restampTopicFoundingTurn(convId: Id[Conversation], turnLog: TurnEventLog): Task[Unit] = {
    import scala.jdk.CollectionConverters.*
    val switches = turnLog.switches.asScala.toList.sortBy(_.timestamp.value)
    if (switches.isEmpty) Task.unit
    else withDB(_.conversations.transaction(_.get(convId))).flatMap {
      case None => Task.unit
      case Some(conv) =>
        // The founding user message published BEFORE the claim opened
        // the turn log, so the publish hook missed it. It is pre-turn
        // committed data — safely queryable, unlike the turn's own
        // tail (whose index refresh the log exists to sidestep).
        withDB(_.conversationEvents(convId)).flatMap { history =>
          def foundingMessage(tc: TopicChange): Option[Event] =
            history.iterator
              .filter(e => e.timestamp.value <= tc.timestamp.value && !turnLog.stamps.containsKey(e._id))
              .foldLeft(Option.empty[Event]) { (best, e) =>
                e match {
                  case m: Message
                    if m.role == MessageRole.Standard
                      && !m.participantId.isInstanceOf[AgentParticipantId]
                      && best.forall(_.timestamp.value < m.timestamp.value) => Some(m)
                  case _ => best
                }
              }
          // Fold over the switches with a live view of each event's
          // current stamp, so a turn with chained switches re-homes
          // its events through each hop.
          val stamps = scala.collection.mutable.Map.from(turnLog.stamps.asScala)
          switches.foldLeft(Task.unit) { (acc, tc) =>
            acc.flatMap { _ =>
              val newIdx = conv.topics.indexWhere(_.id == tc.topicId)
              val previousTopicId = tc.kind match {
                case TopicChangeKind.Switch(prev) => prev
                case _                            => tc.topicId
              }
              if (newIdx < 0 || previousTopicId == tc.topicId) Task.unit
              else {
                foundingMessage(tc).foreach(m => stamps.getOrElseUpdate(m._id, m.topicId))
                val affected = stamps.iterator.collect {
                  case (id, topicId) if topicId == previousTopicId && id != tc._id => id
                }.toList
                affected.foreach(id => stamps.update(id, tc.topicId))
                affected.foldLeft(Task.unit) { (a, id) =>
                  a.flatMap(_ => publish(TopicDelta(
                    target         = id,
                    conversationId = convId,
                    topicId        = tc.topicId,
                    topicIndex     = newIdx
                  )).unit)
                }
              }
            }
          }
        }
    }
  }


  private final def buildContext(agent: AgentParticipant,
                                 conv: Conversation,
                                 sinceTimestamp: Timestamp,
                                 claimedId: Id[Event],
                                 claimedTimestamp: Timestamp,
                                 isGreeting: Boolean = false,
                                 discoveredCapabilitiesRef: AtomicReference[Map[String, sigil.conversation.DiscoveredCapability]] =
                                   new AtomicReference(Map.empty),
                                 // Turn-scoped read-tool result cache, threaded onto the TurnContext.
                                 toolResultCacheRef: AtomicReference[Map[String, sigil.tool.CachedToolRead]] =
                                   new AtomicReference(Map.empty),
                                 healedThisTurn: Option[java.util.concurrent.atomic.AtomicBoolean] = None): Task[(TurnContext, Stream[Event])] =
    for {
      triggerEvents <- withDB(_.conversationEventsConsistent(conv._id)).map { all =>
        all.view
          .filter(e => e.timestamp.value > sinceTimestamp.value
                    && TriggerFilter.isTriggerFor(agent, e)
                    && visibilityAllows(e.visibility, agent.id))
          .toList
      }
      _ = {
        // Sigil #313 — reset the heal allowance when a fresh user-
        // authored Standard Message drains as a trigger. The agent's
        // claim can survive across multiple user messages (the #307
        // scenario — new user msg arriving mid-loop joins the
        // existing claim), so the heal boundary aligns with the
        // human notion of "turn" (one user message → one heal
        // chance) rather than the wider claim arc. Predicate matches
        // [[sigil.conversation.compression.CompactionInvariant.CurrentUserTaskMessage]]'s
        // rule so the heal and curator boundaries are derived from
        // the same source.
        healedThisTurn.foreach { flag =>
          val freshUserTurn = triggerEvents.exists {
            case m: Message
              if m.role == MessageRole.Standard
              && !m.participantId.isInstanceOf[AgentParticipantId] => true
            case _ => false
          }
          if (freshUserTurn) flag.set(false)
        }
      }
      chain = buildChain(triggerEvents, agent)
      _ = markTurnPhase(conv._id, TurnPhase.ClaimToRouting)
      // Sigil bug #205 — resolve the actually-routed model up-front so
      // the curator's budget gate sees the right context window. The
      // agent's nominal `modelId` is frequently a small-context default
      // (e.g. a local llama loaded at boot from
      // `provider.models.headOption`), but per-turn routing escalates
      // to a frontier candidate via `classifyForRoute`. Budgeting against
      // `agent.modelId` triggers aggressive compression that strips
      // context the routed model would have comfortably accepted.
      routedModelId <- resolveRoutedModelId(agent, conv, chain, claimedId)
      _ = markTurnPhase(conv._id, TurnPhase.Routing)
      // Sigil #277 — resolve the routed id to a registered Model record.
      // Cache miss throws UnregisteredModelException at the boundary
      // (instead of silently falling through per-fact defaults later).
      routedModel = cache.find(routedModelId).getOrElse(
                      throw new sigil.provider.UnregisteredModelException(routedModelId, cache.all.map(_._id))
                    )
      input         <- curate(conv._id, routedModelId, chain)
      // Space-scoped always-on skills are injected HERE — after the
      // curator, at the framework-owned choke point every agent turn
      // passes through — so custom curators and apps that suppress
      // `find_capability` still get per-space baseline context with no
      // wiring, and skill edits apply on the very next iteration.
      alwaysOn      <- alwaysOnSkillsFor(conv)
      _ = markTurnPhase(conv._id, TurnPhase.ContextAssembly)
    } yield {
      val triggers: Stream[Event] = Stream.emits(triggerEvents)
      val ctx = TurnContext(
        sigil               = this,
        chain               = chain,
        conversation        = conv,
        turnInput           = input.copy(alwaysOnSkills = alwaysOn),
        model               = routedModel,
        currentAgentStateId = Some(claimedId),
        turnStartedAt       = Some(claimedTimestamp),
        isGreeting          = isGreeting,
        discoveredCapabilitiesRef = discoveredCapabilitiesRef,
        toolResultCacheRef = toolResultCacheRef
      )
      (ctx, triggers)
    }

  /** Sigil bug #205 — resolve the model id this turn will route to,
    * before [[curate]] runs. Delegates to the shared [[resolveRouting]]
    * helper (same logic `runAgentTurn` uses), but passes a stub
    * TurnContext for the classifier callbacks since the full one (with
    * curated turnInput) isn't yet available.
    *
    * `classifyForRoute` memoizes on the user message id, so the
    * duplicate classifier call inside `runAgentTurn` later in the turn
    * hits the cache and adds no LLM round-trip cost. */
  private final def resolveRoutedModelId(agent: AgentParticipant,
                                         conv: Conversation,
                                         chain: List[ParticipantId],
                                         claimedId: Id[Event]): Task[Id[Model]] = {
    // Stub TurnContext for classifier callbacks. `turnInput` is empty
    // because curate hasn't run yet — classifiers that need full
    // context override the strategy's classifier implementation
    // entirely; the default classifiers only read userText + the
    // conversation reachable through the stub.
    //
    // Sigil #277 — the stub's `model` is the agent's nominal Model
    // (resolved at the boundary). The routing might escalate to a
    // different model after the classifier runs, but the nominal is a
    // sane placeholder for the pre-route phase.
    val nominalModel = cache.find(agent.modelId).getOrElse(
      throw new sigil.provider.UnregisteredModelException(agent.modelId, cache.all.map(_._id))
    )
    val stubCtx = TurnContext(
      sigil               = this,
      chain               = chain,
      conversation        = conv,
      turnInput           = sigil.conversation.TurnInput(sigil.conversation.ConversationView(conversationId = conv._id)),
      model               = nominalModel,
      currentAgentStateId = Some(claimedId)
    )
    resolveRouting(agent, conv, stubCtx).map(_.modelId)
  }

  private final def newTriggersExist(agent: AgentParticipant,
                                     conv: Conversation,
                                     sinceTimestamp: Timestamp): Task[Boolean] =
    withDB(_.conversationEventsConsistent(conv._id)).map { all =>
      all.exists(e => e.timestamp.value > sinceTimestamp.value && TriggerFilter.isTriggerFor(agent, e))
    }

  /** Whether `e` is a trigger for `agent` authored by someone OTHER
    * than the agent itself. The turn's own emitted events (the reply
    * `Message`, the terminal tool's `ToolResults`) must never keep the
    * loop alive or leave a re-fire marker — only genuine external work
    * may. A detached tool's completion is agent-attributed (the agent
    * made the call) but IS external work arriving, so it counts;
    * without it a completion landing mid-turn would evaporate with the
    * turn and the agent would never see the result. */
  private final def isExternalTrigger(agent: AgentParticipant, e: Event): Boolean =
    (e.participantId != agent.id
      || e.source.contains(sigil.orchestrator.Orchestrator.DetachedContinuationSource)) &&
      TriggerFilter.isTriggerFor(agent, e)

  /** Like [[newTriggersExist]] but only counts triggers that satisfy
    * [[isExternalTrigger]]. Used after a turn the agent ended with a
    * user-visible terminal tool. */
  private final def externalTriggersExist(agent: AgentParticipant,
                                          conv: Conversation,
                                          sinceTimestamp: Timestamp): Task[Boolean] =
    withDB(_.conversationEventsConsistent(conv._id)).map { all =>
      all.exists(e => e.timestamp.value > sinceTimestamp.value && isExternalTrigger(agent, e))
    }

  private final def buildChain(triggers: List[Event], agent: AgentParticipant): List[ParticipantId] = {
    val source = triggers.find(_.participantId != agent.id).map(_.participantId)
    source.toList :+ agent.id
  }

  /** Stable per-(agent, conversation) id used as both the AgentState key and
    * the lock-acquisition target inside `tx.modify`. */
  private[sigil] final def agentStateLockId(agentId: AgentParticipantId, convId: Id[Conversation]): Id[Event] =
    Id(s"agentlock:${agentId.value}:${convId.value}")
}
