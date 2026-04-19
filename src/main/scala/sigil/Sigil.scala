package sigil

import fabric.rw.RW
import profig.Profig
import rapid.{Task, logger}
import sigil.db.{Model, SigilDB}
import sigil.participant.ParticipantId
import sigil.signal.{CoreSignals, Signal}
import sigil.tool.core.{CoreTools, ToolManager}

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
   * App-provided tool discovery. Consulted by `find_capability` and any
   * application-level `/find_capability` slash command. The implementation
   * decides how to interpret the participant chain (auth, roles, scoping)
   * and which tools to return.
   *
   * Declared as a `val` so implementations supply a single, stable instance —
   * constructing a fresh `ToolManager` on every access would break any
   * internal caching the implementation relies on.
   */
  val toolManager: ToolManager

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

  val instance: Task[SigilInstance] = Task.defer {
    for {
      _ <- logger.info("Sigil initializing...")
      _ <- Task(Profig.initConfiguration())
      _ = Signal.register((CoreSignals.all ++ signals)*)
      _ = sigil.tool.ToolInput.register((CoreTools.inputRWs ++ toolManager.all.map(_.inputRW))*)
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
