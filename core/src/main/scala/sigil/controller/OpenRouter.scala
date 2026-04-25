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
  def loadModels: Task[List[Model]] =
    HttpClient
      .url(url"https://openrouter.ai/api/v1/models")
      .call[Json]
      .map { json =>
        val vector = json
          .filterOne(SnakeToCamelFilter)("data")
          .asVector
        vector.map { json =>
          json
            .merge(
              obj(
                "_id" -> json("id")
              ))
            .as[Model]
        }.toList
      }

  def refreshModels(sigil: Sigil): Task[Unit] =
    for {
      models <- loadModels
      _ <- sigil.cache.replace(models)
      _ <- logger.info(s"Refreshed model registry with ${models.length} models from OpenRouter.")
    } yield ()
}
