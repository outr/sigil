package sigil

import fabric.rw.RW
import lightdb.id.Id
import profig.Profig
import rapid.{Task, logger}
import sigil.conversation.ConversationContext
import sigil.db.{Model, SigilDB}
import sigil.information.{FullInformation, Information}
import sigil.participant.ParticipantId
import sigil.signal.{CoreSignals, Signal}
import sigil.tool.core.CoreTools
import sigil.tool.{ToolFinder, ToolInput}

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
   * representative test response instead. The `ToolContext` passed to
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
   * enforces. Apps that don't curate return `Task.pure(ctx)`. The
   * orchestrator invokes this between turns (the cost of a no-op is
   * negligible, so no `shouldCurate` gate).
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

  // -- lifecycle --

  val instance: Task[SigilInstance] = Task.defer {
    for {
      _ <- logger.info("Sigil initializing...")
      _ <- Task(Profig.initConfiguration())
      _ = Signal.register((CoreSignals.all ++ signals)*)
      _ = ToolInput.register((CoreTools.inputRWs ++ findTools.toolInputRWs)*)
      _ = ParticipantId.register(participantIds*)
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
