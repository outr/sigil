package bench

import fabric.rw.*
import lightdb.id.Id
import rapid.Task
import sigil.{Sigil, SpaceId, TurnContext}
import sigil.conversation.{ConversationView, TurnInput}
import sigil.db.Model
import sigil.embedding.{EmbeddingProvider, NoOpEmbeddingProvider}
import sigil.information.Information
import sigil.participant.{AgentParticipantId, Participant, ParticipantId}
import sigil.provider.{Mode, Provider}
import sigil.signal.Signal
import sigil.tool.{DiscoveryRequest, InMemoryToolFinder, Tool, ToolFinder, ToolInput, ToolName}
import sigil.tool.core.CoreTools
import sigil.vector.{NoOpVectorIndex, VectorIndex}

import java.util.concurrent.atomic.AtomicReference

/**
 * Sigil variant for agent-loop benchmarks.
 *
 * Differs from [[BenchmarkSigil]] in two ways:
 *
 *   1. **Agent-aware** — the agent participant id, viewer participant
 *      id, and any AgentParticipant subclasses the benchmark uses are
 *      registered for fabric polymorphic RW (so events / participants
 *      round-trip through the DB cleanly).
 *   2. **Per-scenario tool catalog** — `findTools` is backed by an
 *      [[AtomicReference]]. Apps wire scenario-scoped tool finders via
 *      [[setToolFinder]] (or [[AgentBenchHarness#withToolFinder]] for
 *      scoped install/restore).
 *
 * Tool input types must still be registered at construct time via
 * `toolInputs` (fabric RW registration only happens once at Sigil
 * initialization). Each scenario's tool instances close over their
 * own state — typically a mutable env reference — and the active
 * `ToolFinder` exposes whichever instances the scenario built.
 */
final class BenchmarkAgentSigil(viewer: ParticipantId,
                                agentParticipantId: AgentParticipantId,
                                extraParticipantIds: List[RW[? <: ParticipantId]],
                                participantRWs: List[RW[? <: Participant]],
                                providerFactory: Id[Model] => Task[Provider],
                                toolInputs: List[RW[? <: ToolInput]],
                                extraSignalRegistrations: List[RW[? <: Signal]] = Nil,
                                wireLogPath: Option[java.nio.file.Path] = None,
                                override val embeddingProvider: EmbeddingProvider = NoOpEmbeddingProvider,
                                override val vectorIndex: VectorIndex = NoOpVectorIndex) extends Sigil {

  override type DB = sigil.db.DefaultSigilDB
  override protected def buildDB(directory: Option[java.nio.file.Path],
                                  storeManager: lightdb.store.CollectionManager,
                                  appUpgrades: List[lightdb.upgrade.DatabaseUpgrade]): DB =
    new sigil.db.DefaultSigilDB(directory, storeManager, appUpgrades)

  private val toolFinderRef = new AtomicReference[ToolFinder](InMemoryToolFinder(Nil))

  /** Install a [[ToolFinder]] for subsequent agent turns. Returns the
    * previous finder so callers can restore it. */
  def setToolFinder(finder: ToolFinder): ToolFinder = toolFinderRef.getAndSet(finder)

  /** Static catalog of framework essentials (`respond`, `no_response`,
    * `change_mode`, `stop`, `find_capability`) — always available to
    * the agent regardless of which scenario tool finder is installed.
    * Without these, the agent has no way to finalize a turn (the
    * system prompt instructs it to use `respond` but the tool isn't
    * findable, so the agent loops on data tools until
    * `maxAgentIterations`). */
  private val coreFinder: ToolFinder = InMemoryToolFinder(CoreTools.all.toList)

  /** Active finder. The wrapper always reports the union of
    * `toolInputs` and core-tool input RWs as its `toolInputRWs` (so
    * Sigil's init-time `ToolInput.register` call sees every benchmark
    * input type plus the essentials), and forwards `apply` / `byName`
    * to a fall-through chain: scenario finder first, then
    * [[coreFinder]]. The scenario can shadow a core tool by emitting
    * the same name, but in practice they don't overlap. */
  override def findTools: ToolFinder = new ToolFinder {
    override val toolInputRWs: List[RW[? <: ToolInput]] =
      (toolInputs ++ coreFinder.toolInputRWs).distinctBy(_.definition.className)
    override def apply(request: DiscoveryRequest) =
      toolFinderRef.get().apply(request).flatMap(scenario =>
        coreFinder.apply(request).map(core =>
          (scenario ++ core).distinctBy(_.name.value)
        )
      )
    override def byName(name: ToolName) =
      toolFinderRef.get().byName(name).flatMap {
        case Some(t) => rapid.Task.pure(Some(t))
        case None => coreFinder.byName(name)
      }
  }

  override def toolInputRegistrations: List[RW[? <: ToolInput]] = toolInputs

  /** Empty static catalog — the framework essentials live in
    * [[coreFinder]] (returned via the `findTools` fallback) rather
    * than persisted to `SigilDB.tools`, since this benchmark Sigil
    * doesn't run [[sigil.tool.StaticToolSyncUpgrade]]. */
  override def staticTools: List[sigil.tool.Tool] = Nil

  override protected def signalRegistrations: List[RW[? <: Signal]] = extraSignalRegistrations
  override protected def participantIds: List[RW[? <: ParticipantId]] =
    List(RW.static(viewer), RW.static(agentParticipantId)) ++ extraParticipantIds
  override protected def spaceIds: List[RW[? <: SpaceId]] = Nil
  override protected def participants: List[RW[? <: Participant]] = participantRWs
  override protected def modes: List[Mode] = Nil

  override def curate(view: ConversationView,
                      modelId: Id[Model],
                      chain: List[ParticipantId]): Task[TurnInput] =
    Task.pure(TurnInput(view))

  override def getInformation(id: Id[Information]): Task[Option[Information]] = Task.pure(None)
  override def putInformation(information: Information): Task[Unit] = Task.unit
  override def compressionMemorySpace(conversationId: Id[sigil.conversation.Conversation]): Task[Option[SpaceId]] =
    Task.pure(None)

  override def wireInterceptor: spice.http.client.intercept.Interceptor =
    wireLogPath match {
      case Some(p) => sigil.provider.debug.JsonLinesInterceptor(p)
      case None => spice.http.client.intercept.Interceptor.empty
    }

  override def providerFor(modelId: Id[Model], chain: List[ParticipantId]): Task[Provider] =
    providerFactory(modelId)
}
