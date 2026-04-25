package sigil.provider.openai

/**
 * OpenAI-specific constants. Model metadata (context, pricing,
 * tokenizer, etc.) lives in [[sigil.db.SigilDB.model]] — populated by
 * [[sigil.controller.OpenRouter.refreshModels]] — and is read fresh on
 * each access via `Provider.models`.
 */
object OpenAI {
  val Provider: String = "openai"

  /** Strip the `openai/` provider prefix from a sigil model id so the
    * wire request carries the bare OpenAI id. */
  def stripProviderPrefix(sigilModelId: String): String = {
    val prefix = s"$Provider/"
    if (sigilModelId.startsWith(prefix)) sigilModelId.drop(prefix.length) else sigilModelId
  }
}
