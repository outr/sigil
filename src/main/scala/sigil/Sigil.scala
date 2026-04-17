package sigil

import profig.Profig
import rapid.{Task, logger}
import sigil.db.{Model, SigilDB}

private case class Sigil(config: Config,
                         db: SigilDB)

object Sigil {
  val instance: Task[Sigil] = Task.defer {
    for {
      _ <- logger.info("Sigil initializing...")
      _ <- Task(Profig.initConfiguration())
      config = Profig("sigil").as[Config]
      db = SigilDB(Some(config.dbPath))
      _ <- db.init
    } yield Sigil(
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
}
