package sigil.provider

import lightdb.id.Id
import sigil.db.Model

/**
 * Resolves a model id to the [[Provider]] that serves it (and the
 * registered [[Model]] record), as a [[ProviderModel]]. `None` when
 * nothing serves the id.
 *
 * A single [[Provider]] is itself a `ModelResolver` — it serves any model
 * in the registry. [[ProviderRegistry]] composes several providers and
 * dispatches by the id's provider namespace, so model→provider mapping is
 * the framework's job rather than something every consumer re-implements
 * by hand (the gap that produced the namespace mismatch in sigil #333).
 *
 * `Sigil.modelResolver` is the framework-internal / fallback binding —
 * typically a single provider or a [[ProviderRegistry]] of the app's
 * built-in providers. Apps that let users bring their own provider /
 * credentials layer per-user resolution above this seam.
 */
trait ModelResolver {
  def resolve(modelId: Id[Model]): Option[ProviderModel]
}
