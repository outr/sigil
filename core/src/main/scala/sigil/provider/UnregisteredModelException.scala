package sigil.provider

import lightdb.id.Id
import sigil.db.Model

/**
 * Thrown when a runtime path attempts to use a [[Model]] id that isn't
 * present in [[sigil.cache.ModelRegistry]]. Sigil #277.
 *
 * Every per-model fact (context length, max output tokens, supports
 * tools, supports images, pricing) lives in the registry. Allowing a
 * runtime call against an unregistered id would silently fall through
 * to per-fact defaults that mostly aren't correct — exactly the silent-
 * truncation failure mode that motivated this enforcement.
 *
 * Resolution paths:
 *
 *   - **OpenRouter consumers**: `OpenRouter.refreshModels` populates
 *     the registry automatically on the background refresh interval.
 *     If your model isn't appearing, check the refresh fiber is running
 *     and the upstream catalog actually carries it.
 *
 *   - **Anthropic / OpenAI / Google direct (no OpenRouter)**: call
 *     `Anthropic.registerKnownModels(sigil).sync()` (or the
 *     corresponding helper for your provider) at boot. Each per-
 *     provider helper ships a curated catalog of the current public
 *     models with full metadata.
 *
 *   - **llama.cpp / fine-tuned variants / custom models**: apps
 *     register manually via `sigil.cache.merge(List(myModel))` — the
 *     catalog is user-managed by definition.
 *
 *   - **Tests**: `TestSigil` auto-registers a synthetic model for the
 *     test-fixture ids the framework's own specs use; downstream apps
 *     register their test models with the same pattern.
 */
final class UnregisteredModelException(val modelId: Id[Model],
                                       val registeredIds: List[Id[Model]])
  extends RuntimeException(UnregisteredModelException.message(modelId, registeredIds))

object UnregisteredModelException {
  private def message(modelId: Id[Model], registeredIds: List[Id[Model]]): String = {
    val knownPreview =
      if (registeredIds.isEmpty) "<none — registry empty>"
      else if (registeredIds.size <= 10) registeredIds.map(_.value).sorted.mkString(", ")
      else registeredIds.take(10).map(_.value).sorted.mkString(", ") + s" (+${registeredIds.size - 10} more)"
    s"Model `${modelId.value}` is not in the ModelRegistry. " +
      s"Registered: $knownPreview. " +
      "Populate via OpenRouter.refreshModels, the per-provider registerKnownModels helper " +
      "or sigil.cache.merge for custom catalogs."
  }
}
