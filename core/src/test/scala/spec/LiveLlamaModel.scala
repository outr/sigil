package spec

import lightdb.id.Id
import rapid.Task
import sigil.Sigil
import sigil.db.Model
import sigil.provider.llamacpp.LlamaCpp
import spice.net.URL

/**
 * Resolves the model id the live llama.cpp server actually serves and
 * merges it into the registry, so a deployment model swap doesn't
 * desync a hard-coded id. Falls back to a default when the server is
 * unreachable (the live spec self-skips anyway).
 */
object LiveLlamaModel {
  private val Fallback: Id[Model] = Model.id(LlamaCpp.Provider, "qwen3.5-9b-q4_k_m")

  def resolve(sigil: Sigil, host: URL): Id[Model] =
    LlamaCpp.loadModels(host)
      .flatMap(models => sigil.cache.merge(models).map(_ => models.headOption.map(_._id)))
      .handleError(_ => Task.pure(None))
      .sync()
      .getOrElse(Fallback)
}
