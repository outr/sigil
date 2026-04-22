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
import sigil.conversation.{ActiveSkillSlot, ContextKey, ContextMemory, ContextSummary, Conversation, ConversationView, FrameBuilder, MemorySpaceId, ParticipantProjection, SkillSource, TurnInput}
import sigil.db.{Model, SigilDB}
import sigil.dispatcher.{StopFlag, TriggerFilter}
import sigil.event.{AgentState, Event, ModeChange, Stop, TitleChange}
import sigil.provider.Mode
import sigil.information.Information
import sigil.participant.{AgentParticipant, AgentParticipantId, DefaultAgentParticipant, Participant, ParticipantId}
import sigil.provider.Provider
import sigil.signal.{AgentActivity, AgentStateDelta, CoreSignals, EventState, Signal}
import sigil.tool.core.CoreTools
import sigil.tool.{ToolFinder, ToolInput}

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

trait Sigil {

  /**
   * App-specific Signal subtypes (custom Events / Deltas the app introduces
   * beyond what sigil ships). The framework's [[CoreSignals]] are registered
   * automatically; this list extends the polymorphic discriminator with
   * additional types.
   */
  protected def signals: List[RW[? <: Signal]] = Nil

  /**
   * App-specific ParticipantId subtypes. Apps register their own
   * `ParticipantId` implementations here for polymorphic serialization.
   */
  protected def participantIds: List[RW[? <: ParticipantId]] = Nil

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
   * Per-turn curator: given the current [[ConversationView]], produce the
   * [[TurnInput]] the provider will render. Policy lives here — pick which
   * memories/summaries/information to surface, apply app-specific
   * overlays, add extra context, etc.
   *
   * Apps that need nothing beyond the raw view return
   * `Task.pure(TurnInput(view))`. The default implementation does exactly
   * that so implementors can opt in incrementally.
   */
  def curate(view: ConversationView): Task[TurnInput] = Task.pure(TurnInput(view))

  // -- information lookup --

  /**
   * Resolve the full content of an [[Information]] catalog entry. Default
   * returns `None` — apps that use the Information catalog override to
   * dispatch on their own subtype registry and fetch the real content from wherever
   * it lives (DB, filesystem, web, memory store).
   */
  def getInformation(id: Id[Information]): Task[Option[Information]] = Task.pure(None)

  // -- memory --

  /**
   * App-specific [[MemorySpaceId]] subtypes registered into the polymorphic
   * discriminator so [[ContextMemory.spaceId]] values round-trip through
   * fabric RW. Apps define concrete spaces (GlobalSpace, ProjectSpace,
   * UserSpace, etc.) and list their RWs here.
   */
  protected def memorySpaceIds: List[RW[? <: MemorySpaceId]] = Nil

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
   * Default returns `None` for every mode — apps override to map modes to
   * concrete skill playbooks.
   */
  def modeSkill(mode: Mode): Task[Option[ActiveSkillSlot]] = Task.pure(None)

  def findMemories(spaces: Set[MemorySpaceId]): Task[List[ContextMemory]] =
    if (spaces.isEmpty) Task.pure(Nil)
    else withDB(_.memories.transaction { tx =>
      import lightdb.filter.*
      tx.query
        .filter(m => spaces.map(s => m.spaceId === s).reduce(_ || _))
        .toList
    })

  // -- broadcasting --

  /**
   * The wire transport for [[Signal]]s. The framework calls
   * `broadcaster.handle(signal)` after persisting and before fanning out.
   * Apps override to push to WebSocket / SSE / DurableSocket; the default
   * drops everything silently.
   */
  def broadcaster: SignalBroadcaster = SignalBroadcaster.NoOp

  // -- participants (registration for polymorphic RW) --

  /**
   * App-specific [[Participant]] subtypes registered into the polymorphic
   * discriminator so [[sigil.conversation.Conversation.participants]] can
   * round-trip them through fabric RW. Framework subtypes
   * ([[DefaultAgentParticipant]]) are registered automatically; this list
   * extends the poly with app-specific agent types (Planner, Critic, etc.).
   */
  protected def participants: List[RW[? <: Participant]] = Nil

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
   *      (`currentMode`, `title`) for Mode/Title changes.
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
      _ <- applyStop(signal)
      _ <- broadcaster.handle(signal).handleError(logBroadcastError(signal, _))
      _ <- signal match {
             case e: Event => fanOut(e)
             case _: sigil.signal.Delta => Task.unit
           }
    } yield ()

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
    publish(Stop(
      participantId = requestedBy,
      conversationId = conversationId,
      targetParticipantId = targetParticipantId,
      force = force,
      reason = reason
    ))

  /** Persist a new [[ContextSummary]] and return the stored record. The
    * caller (curator or app-specific summarizer) owns the generation
    * policy; this helper just writes. */
  def persistSummary(summary: ContextSummary): Task[ContextSummary] =
    withDB(_.summaries.transaction(_.upsert(summary)))

  /** Load all summaries for a conversation, oldest-first. */
  def summariesFor(conversationId: Id[Conversation]): Task[List[ContextSummary]] =
    withDB(_.summaries.transaction { tx =>
      import lightdb.filter.*
      tx.query
        .filter(_.conversationId === conversationId)
        .toList
        .map(_.sortBy(_.created.value))
    })

  /** Maintain materialized projections on the [[Conversation]] record:
    *   - `currentMode` tracks the latest [[ModeChange]]
    *   - `title` tracks the latest [[TitleChange]]
    *
    * Fires only on the SETTLE (an Event already at `Complete`, or a
    * `Delta` that transitions its target to `Complete`), never on the
    * initial Active pulse — so `Conversation.currentMode` / `.title` are
    * written exactly once per transition even though each change flows
    * through `publish` twice (event + state delta). */
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
      case Some(tc: TitleChange) =>
        withDB(_.conversations.transaction(_.modify(tc.conversationId) {
          case Some(conv) if conv.title != tc.title =>
            Task.pure(Some(conv.copy(title = tc.title, modified = Timestamp(Nowish()))))
          case Some(conv) => Task.pure(Some(conv))
          case None       => Task.pure(None)
        })).unit
      case _ => Task.unit
    }
  }

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
      input <- curate(view)
      triggerEvents <- withDB(_.events.transaction(_.list)).map { all =>
        all.view
          .filter(e => e.conversationId == conv._id
                    && e.timestamp.value > sinceTimestamp.value
                    && TriggerFilter.isTriggerFor(agent, e))
          .toList
      }
    } yield {
      val triggers: Stream[Event] = Stream.emits(triggerEvents)
      val chain = buildChain(triggerEvents, agent)
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
