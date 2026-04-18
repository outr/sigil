package sigil.controller

import fabric.*
import fabric.rw.*
import fabric.filter.SnakeToCamelFilter
import rapid.{Task, logger}
import sigil.Sigil
import sigil.db.Model
import spice.http.client.HttpClient
import spice.net.*

object OpenRouter {
  def loadModels: Task[List[Model]] = HttpClient
    .url(url"https://openrouter.ai/api/v1/models")
    .call[Json]
    .map { json =>
      val vector = json
        .filterOne(SnakeToCamelFilter)("data")
        .asVector
      vector.map { json =>
        json.merge(obj(
          "_id" -> json("id")
        )).as[Model]
      }.toList
    }

  def refreshModels(sigil: Sigil): Task[Unit] = for {
    instance <- sigil.instance
    models <- loadModels
    _ <- instance.db.model.transaction { modelCache =>
      for {
        deleted <- modelCache.truncate
        _ <- logger.info(s"Truncated $deleted cached model records. Inserting ${models.length} models...")
        _ <- modelCache.insert(rapid.Stream.emits(models))
        _ <- logger.info(s"Successfully updated ${models.length} models.")
      } yield ()
    }
  } yield ()
}
