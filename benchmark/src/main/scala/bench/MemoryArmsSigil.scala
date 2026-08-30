package bench

import fabric.rw.*
import lightdb.id.Id
import rapid.Task
import sigil.{Sigil, SpaceId}
import sigil.conversation.TurnInput
import sigil.conversation.compression.{MemoryDistiller, MemoryRetriever, NoOpMemoryRetriever, StandardContextCurator}
import sigil.db.Model
import sigil.embedding.{EmbeddingProvider, NoOpEmbeddingProvider}
import sigil.participant.{AgentParticipantId, DefaultAgentParticipant, Participant, ParticipantId}
import sigil.provider.{ModelResolver, Provider, ProviderModel}
import sigil.tool.util.SemanticSearchTool
import sigil.vector.{NoOpVectorIndex, VectorIndex}

import java.util.concurrent.atomic.AtomicReference

/** The bench viewer (user-side) participant. */
case object ArmsBenchUser extends ParticipantId {
  override val value: String = "arms-bench-user"
}

/** The bench agent participant id. */
case object ArmsBenchAgent extends AgentParticipantId {
  override val value: String = "arms-bench-agent"
}

/** Per-arm memory space — the isolation boundary between arms: each
  * arm's corpus is seeded into its own space and the active arm's
  * space is the only accessible one, so no arm can recall another
  * arm's rows (the cross-arm contamination class). */
case class ArmSpace(arm: String) extends SpaceId derives RW {
  override val value: String = s"memory-arms-$arm"
  override val displayName: String = s"Memory arms bench: $arm"
}

/**
 * Sigil host for [[MemoryArmsBench]]. Every per-arm switch is an
 * [[AtomicReference]] the runner flips between arms (arms run
 * sequentially in one process): the passive retriever, the accessible
 * space, the distiller, and the provider.
 */
final class MemoryArmsSigil extends Sigil {
  override type DB = sigil.db.DefaultSigilDB
  override protected def buildDB(directory: Option[java.nio.file.Path],
                                 storeManager: lightdb.store.CollectionManager,
                                 appUpgrades: List[lightdb.upgrade.DatabaseUpgrade]): DB =
    new sigil.db.DefaultSigilDB(directory, storeManager, appUpgrades)

  private val providerRef = new AtomicReference[Option[Provider]](None)
  private val embeddingRef = new AtomicReference[EmbeddingProvider](NoOpEmbeddingProvider)
  private val vectorRef = new AtomicReference[VectorIndex](NoOpVectorIndex)
  private val retrieverRef = new AtomicReference[MemoryRetriever](NoOpMemoryRetriever)
  private val armSpaceRef = new AtomicReference[Option[SpaceId]](None)
  private val distillerRef = new AtomicReference[Option[MemoryDistiller]](None)
  private val lastInjectedRef = new AtomicReference[List[String]](Nil)

  /** Facts injected by the most recent `curate` — the retrieval-level
    * observation the runner scores recall@k from. Answer accuracy
    * conflates retrieval with the runtime model's reading of what it
    * got; this isolates the half the fusion weights actually move. */
  def lastInjected: List[String] = lastInjectedRef.get()

  def setProvider(p: Provider): Unit = providerRef.set(Some(p))
  def setEmbedding(e: EmbeddingProvider, v: VectorIndex): Unit = {
    embeddingRef.set(e)
    vectorRef.set(v)
  }
  def setRetriever(r: MemoryRetriever): Unit = retrieverRef.set(r)
  def setArmSpace(s: SpaceId): Unit = armSpaceRef.set(Some(s))
  def setDistiller(d: MemoryDistiller): Unit = distillerRef.set(Some(d))
  def clearDistiller(): Unit = distillerRef.set(None)

  override def embeddingProvider: EmbeddingProvider = embeddingRef.get()
  override def vectorIndex: VectorIndex = vectorRef.get()
  override def memoryDistiller: Option[MemoryDistiller] = distillerRef.get()

  override def modelResolver: ModelResolver = (modelId: Id[Model]) =>
    providerRef.get().flatMap(p => cache.find(modelId).map(ProviderModel(p, _)))

  override def curate(conversationId: Id[sigil.conversation.Conversation],
                      modelId: Id[Model],
                      chain: List[ParticipantId]): Task[TurnInput] =
    StandardContextCurator(this, memoryRetriever = retrieverRef.get())
      .curate(conversationId, modelId, chain)
      .map { input =>
        lastInjectedRef.set(input.memories.toList.flatMap { id =>
          withDB(_.memories.transaction(_.get(id))).sync().map(_.fact)
        })
        if (sys.env.contains("ARMS_DEBUG")) {
          println(s"  [debug] curated memories=${input.memories.size} critical=${input.criticalMemories.size} (vectorWired=$vectorWired) for ${conversationId.value}")
          input.memories.foreach { id =>
            withDB(_.memories.transaction(_.get(id))).sync().foreach(m => println(s"  [debug]   - ${m.fact.take(90)}"))
          }
        }
        input
      }

  override def accessibleSpaces(chain: List[ParticipantId]): Task[Set[SpaceId]] =
    Task.pure(armSpaceRef.get().toSet)
  override def accessibleSpaces(chain: List[ParticipantId],
                                conversationId: Id[sigil.conversation.Conversation]): Task[Set[SpaceId]] =
    Task.pure(armSpaceRef.get().toSet)
  override def defaultRecallSpaces(conversationId: Id[sigil.conversation.Conversation]): Task[Set[SpaceId]] =
    Task.pure(armSpaceRef.get().toSet)

  override def staticTools: List[sigil.tool.Tool] = super.staticTools :+ SemanticSearchTool

  /** The benchmark-defined consult inputs. A consult's tool is never
    * rostered, but its `ToolInput` subtype still has to round-trip
    * through fabric's poly RW or the provider's tool-call decode fails
    * with "Type not found". */
  override def toolInputRegistrations: List[RW[? <: sigil.tool.ToolInput]] =
    super.toolInputRegistrations :+ summon[RW[JudgeVerdictInput]]

  private lazy val wireLogPath: java.nio.file.Path = {
    val dir = java.nio.file.Path.of("target", "wire-logs")
    java.nio.file.Files.createDirectories(dir)
    dir.resolve("MemoryArmsBench.jsonl")
  }

  override def wireInterceptor: spice.http.client.intercept.Interceptor =
    sigil.provider.debug.JsonLinesInterceptor(wireLogPath)

  override protected def participantIds: List[RW[? <: ParticipantId]] =
    List(RW.static(ArmsBenchUser), RW.static(ArmsBenchAgent))
  override protected def participants: List[RW[? <: Participant]] =
    List(summon[RW[DefaultAgentParticipant]])
  override protected def spaceIds: List[RW[? <: SpaceId]] = List(summon[RW[ArmSpace]])
}
