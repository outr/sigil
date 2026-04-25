package sigil.provider.deepseek

/**
 * DeepSeek-specific constants. DeepSeek uses an OpenAI-chat-completions-
 * compatible API at `https://api.deepseek.com/v1/chat/completions`.
 * Model metadata lives in [[sigil.db.SigilDB.model]] — populated by
 * [[sigil.controller.OpenRouter.refreshModels]] — and is read fresh on
 * each access via `Provider.models`.
 */
object DeepSeek {
  val Provider: String = "deepseek"

  def stripProviderPrefix(sigilModelId: String): String = {
    val prefix = s"$Provider/"
    if (sigilModelId.startsWith(prefix)) sigilModelId.drop(prefix.length) else sigilModelId
  }
}
