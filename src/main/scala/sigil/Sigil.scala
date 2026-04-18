package sigil

import fabric.rw.RW
import profig.Profig
import rapid.{Task, logger}
import sigil.db.{Model, SigilDB}
import sigil.event.Event
import sigil.participant.ParticipantId
import sigil.tool.ToolInput

trait Sigil {
  protected def events: List[RW[? <: Event]]
  protected def toolInputs: List[RW[? <: ToolInput]]
  protected def participantIds: List[RW[? <: ParticipantId]]

  val instance: Task[SigilInstance] = Task.defer {
    for {
      _ <- logger.info("Sigil initializing...")
      _ <- Task(Profig.initConfiguration())
      _ = Event.register(events*)
      _ = ToolInput.register(toolInputs*)
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

    def findModel(provider: Option[String] = None,
                  model: Option[String] = None): rapid.Stream[Model] = rapid.Stream.force(withDB { db =>
      db.model.transaction { modelCache =>
        modelCache.query
          .filterOption(mc => provider.map(p => mc.provider === p.toLowerCase))
          .filterOption(mc => model.map(m => mc.model === m.toLowerCase))
          .toList
      }
    }.map(rapid.Stream.emits))

    def apply(provider: String, model: String): Task[Option[Model]] = withDB { db =>
      db.model.transaction { modelCache =>
        modelCache.get(Model.id(provider, model))
      }
    }
  }

  case class SigilInstance(config: Config, db: SigilDB)
}
