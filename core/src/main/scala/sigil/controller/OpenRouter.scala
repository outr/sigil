package sigil.controller

import fabric.*
import fabric.rw.*
import fabric.filter.SnakeToCamelFilter
import lightdb.time.Timestamp
import rapid.{Task, logger}
import sigil.Sigil
import sigil.db.{Model, Models}
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
          // OpenRouter's `name` field is the friendly label
          // ("GPT-5.5", "Claude Opus 4.7", …). Mirror it into
          // `displayName` so clients always have a UI-ready label
          // without falling back to the raw id tail.
          val displayNameJson: Json = json.get("name") match {
            case Some(n) => obj("displayName" -> n)
            case None    => obj()
          }
          json
            .merge(obj("_id" -> json("id")))
            .merge(displayNameJson)
            .as[Model]
        }.toList
      }

  /** Fetch the OpenRouter catalog, persist it to `db.models`, and seed
    * the in-memory [[sigil.cache.ModelRegistry]]. Public post-boot entry —
    * resolves the DB via `sigil.withDB`, then delegates to the
    * boot-safe overload below. Apps call this directly to force an
    * out-of-cycle refresh. */
  def refreshModels(sigil: Sigil): Task[Unit] =
    sigil.withDB(db => refreshModels(sigil, db.asInstanceOf[_root_.sigil.db.SigilDB]))

  /** Boot-safe variant — takes the already-resolved `db` directly so the
    * boot path's `loadAndRefreshModels` can call it without re-entering
    * `sigil.withDB` (which awaits the in-flight `Sigil.instance.singleton`
    * and deadlocks the boot fiber against itself). Sigil bug #281. */
  def refreshModels(sigil: Sigil, db: _root_.sigil.db.SigilDB): Task[Unit] =
    for {
      models <- loadModels
      _      <- db.models.set(Models(models, Timestamp()))
      _      <- sigil.cache.replace(models)
      _      <- logger.info(s"Refreshed model registry with ${models.length} models from OpenRouter.")
    } yield ()
}
