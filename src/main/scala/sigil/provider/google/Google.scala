package sigil.provider.google

/**
 * Google / Gemini provider constants. Model metadata lives in
 * [[sigil.db.SigilDB.model]] — populated by
 * [[sigil.controller.OpenRouter.refreshModels]] — and is read fresh on
 * each access via `Provider.models`.
 */
object Google {
  val Provider: String = "google"

  def stripProviderPrefix(sigilModelId: String): String = {
    val prefix = s"$Provider/"
    if (sigilModelId.startsWith(prefix)) sigilModelId.drop(prefix.length) else sigilModelId
  }
}
