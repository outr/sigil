package sigil.provider.anthropic

/**
 * Anthropic-specific constants. Model metadata lives in
 * [[sigil.cache.ModelRegistry]] — populated by
 * [[sigil.controller.OpenRouter.refreshModels]] — and is read fresh on
 * each access via `Provider.models`.
 */
object Anthropic {
  val Provider: String = "anthropic"
  val ApiVersion: String = "2023-06-01"

  def stripProviderPrefix(sigilModelId: String): String = {
    val prefix = s"$Provider/"
    if (sigilModelId.startsWith(prefix)) sigilModelId.drop(prefix.length) else sigilModelId
  }
}
