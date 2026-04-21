package sigil

import fabric.rw.RW
import lightdb.id.Id
import lightdb.time.Timestamp
import lightdb.util.Nowish
import profig.Profig
import rapid.{Stream, Task, logger}
import sigil.conversation.{Conversation, ConversationContext, ContextMemory, MemorySpaceId}
import sigil.db.{Model, SigilDB}
import sigil.dispatcher.TriggerFilter
import sigil.event.{AgentState, Event, ModeChange}
import sigil.information.{FullInformation, Information}
import sigil.participant.{AgentParticipant, AgentParticipantId, DefaultAgentParticipant, Participant, ParticipantId}
import sigil.provider.Provider
import sigil.signal.{AgentActivity, AgentStateDelta, CoreSignals, EventState, Signal}
import sigil.tool.core.CoreTools
import sigil.tool.{ToolFinder, ToolInput}

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
   * Transform the conversation context between turns — prune, summarize,
   * extract memories, collapse stale tool pairs, whatever policy this app
   * enforces. Apps that don't curate return `Task.pure(ctx)`.
   */
  def curate(ctx: ConversationContext): Task[ConversationContext]

  // -- information lookup --

  /**
   * Resolve the full content of an [[Information]] catalog entry. Default
   * returns `None` — apps that use the Information catalog override to
   * dispatch on their own subtype registry and fetch the real content from wherever
   * it lives (DB, filesystem, web, memory store).
   */
  def getInformation(id: Id[Information]): Task[Option[FullInformation]] = Task.pure(None)

  // -- memory --

  /**
   * App-specific [[MemorySpaceId]] subtypes registered into the polymorphic
   * discriminator so [[ContextMemory.spaceId]] values round-trip through
   * fabric RW. Apps define concrete spaces (GlobalSpace, PersonaSpace,
   * ProjectSpace, UserSpace, etc.) and list their RWs here.
   */
  protected def memorySpaceIds: List[RW[? <: MemorySpaceId]] = Nil

  /**
   * Search memories across the given spaces. Default queries
   * [[SigilDB.memories]] by indexed `spaceId`. Apps override for relevance
   * ranking, recency weighting, embedding search, caching, etc.
   *
   * Typically called from `curate` when assembling a turn's
   * `ConversationContext.memories`: the curator picks which returned
   * records to include (by id) based on its policy.
   */
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
   * Inject a [[Signal]] into the framework. Persists it, broadcasts to wire,
   * and fans out to participants whose `TriggerFilter` matches. Used both
   * externally (apps push a user-typed Message in via this) and internally
   * (every Signal an agent emits during its turn flows back through here).
   *
   * Apps don't override this — it's the framework's pipeline.
   */
  final def publish(signal: Signal): Task[Unit] =
    for {
      _ <- withDB(_.apply(signal))
      _ <- updateConversationProjection(signal)
      _ <- broadcaster.handle(signal).handleError(logBroadcastError(signal, _))
      _ <- signal match {
             case e: Event => fanOut(e)
             case _: sigil.signal.Delta => Task.unit
           }
    } yield ()

  /** Maintain materialized projections on the [[Conversation]] record. Today
    * just `currentMode` derived from the latest [[ModeChange]]; future
    * projections (e.g. title, last-activity timestamp) accrue here too. */
  private final def updateConversationProjection(signal: Signal): Task[Unit] = signal match {
    case mc: ModeChange =>
      withDB(_.conversations.transaction(_.modify(mc.conversationId) {
        case Some(conv) => Task.pure(Some(conv.copy(currentMode = mc.mode, modified = Timestamp(Nowish()))))
        case None       => Task.pure(None)
      })).unit
    case _ => Task.unit
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
          // We won the claim. Broadcast manually (modify already persisted),
          // then fire the agent on its own fiber.
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
    // Reload the conversation each iteration — materialized projections
    // (currentMode, modified, etc.) update as Events flow through `publish`,
    // so the conversation we hand to the agent must reflect the latest state.
    withDB(_.conversations.transaction(_.get(convId))).flatMap {
      case None =>
        // Conversation deleted mid-turn — release the lock and exit cleanly.
        releaseClaim(claimed)
      case Some(conv) =>
        buildContext(agent, conv, sinceTimestamp = sinceTimestamp, claimedId = claimed._id).flatMap {
          case (ctx, triggers) =>
            agent.process(ctx, triggers).evalTap(publish).drain
        }.flatMap { _ =>
          newTriggersExist(agent, conv, sinceTimestamp = thisIterationStart).flatMap {
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

  private final def releaseClaim(claimed: AgentState): Task[Unit] =
    publish(AgentStateDelta(
      target = claimed._id,
      conversationId = claimed.conversationId,
      activity = Some(AgentActivity.Idle),
      state = Some(EventState.Complete)))

  private final def buildContext(agent: AgentParticipant,
                                 conv: Conversation,
                                 sinceTimestamp: Timestamp,
                                 claimedId: Id[Event]): Task[(TurnContext, Stream[Event])] =
    withDB(_.events.transaction(_.list)).flatMap { all =>
      val convEvents = all.filter(_.conversationId == conv._id).sortBy(_.timestamp.value).toVector
      val baseCtx = ConversationContext(events = convEvents)
      curate(baseCtx).map { curated =>
        val triggerEvents = convEvents.filter(e =>
          e.timestamp.value > sinceTimestamp.value && TriggerFilter.isTriggerFor(agent, e)
        )
        val triggers: Stream[Event] = Stream.emits(triggerEvents.toList)
        val chain = buildChain(triggerEvents.toList, agent)
        val ctx = TurnContext(
          sigil = this,
          chain = chain,
          conversation = conv,
          conversationContext = curated,
          currentAgentStateId = Some(claimedId)
        )
        (ctx, triggers)
      }
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
      db = SigilDB(Some(config.dbPath))
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
