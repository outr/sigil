package sigil.provider.google

/**
 * Google / Gemini provider constants. Model metadata lives in
 * [[sigil.cache.ModelRegistry]] — populated by
 * [[sigil.controller.OpenRouter.refreshModels]] — and is read fresh on
 * each access via `Provider.models`.
 */
object Google {
  val Provider: String = "google"

  def stripProviderPrefix(sigilModelId: String): String = {
    val prefix = s"$Provider/"
    if (sigilModelId.startsWith(prefix)) sigilModelId.drop(prefix.length) else sigilModelId
  }

  /** Whether a model (post [[stripProviderPrefix]]) supports explicit
    * `cachedContents` context caching. The stable Gemini 2.x families
    * do; the gate keeps the cache-create path off any non-Gemini
    * vendor model that happens to be routed through the Google
    * generative-language wire, and off the experimental / preview
    * variants whose caching behaviour is not contractually stable. */
  def supportsContextCaching(strippedModelName: String): Boolean = {
    val name = strippedModelName.toLowerCase
    name.contains("gemini") &&
    (name.contains("2.0") || name.contains("2.5")) &&
    !name.contains("exp") &&
    !name.contains("preview")
  }
}
