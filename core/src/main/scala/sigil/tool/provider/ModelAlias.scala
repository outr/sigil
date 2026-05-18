package sigil.tool.provider

import lightdb.id.Id
import rapid.Task
import sigil.TurnContext
import sigil.db.Model

/**
 * Resolves friendly model references — `"current"`, `"this"`,
 * `"local"`, `"llama"`, `"openai"`, `"gpt"`, `"anthropic"`,
 * `"claude"`, `"google"`, `"gemini"`, `"deepseek"` — to a real
 * registered [[Model]] id. Used by [[PinModelTool]] and
 * [[SwitchModelTool]] before they create an ad-hoc strategy so
 * `pin_model("local")` reliably pins to whatever the LlamaCpp
 * provider has loaded, not to the literal string `"local"` (which
 * stamps phantom modelIds and silently routes through the
 * provider-prefix fallback).
 *
 * Apps with bespoke aliases override [[Sigil.modelAliases]] (or
 * extend the resolution chain in their own pin/switch tool wrappers).
 */
object ModelAlias {

  /**
   * Symmetric alias groups — each key resolves to the same canonical
   * provider name as the OpenRouter / LlamaCpp catalog uses.
   */
  val providerAliases: Map[String, String] = Map(
    "openai" -> "openai",
    "gpt" -> "openai",
    "anthropic" -> "anthropic",
    "claude" -> "anthropic",
    "google" -> "google",
    "gemini" -> "google",
    "deepseek" -> "deepseek",
    "local" -> "llamacpp",
    "llama" -> "llamacpp",
    "llamacpp" -> "llamacpp",
    "llama-cpp" -> "llamacpp"
  )

  /**
   * Aliases resolved via the active conversation's lookup chain
   * rather than the static provider mapping.
   */
  val conversationAliases: Set[String] = Set("current", "this")

  /**
   * All alias names — surfaced in the refusal message so the agent
   * can re-prompt the user with the legal options.
   */
  val allAliasNames: List[String] = (providerAliases.keys ++ conversationAliases).toList.distinct.sorted

  /**
   * Resolve `input` (case-insensitive, trimmed) to a registered
   * model id. Returns `None` if the input isn't an alias the
   * resolver knows about — callers fall through to strict id
   * matching against the registry.
   */
  def resolve(input: String, ctx: TurnContext): Task[Option[Id[Model]]] = {
    val key = input.toLowerCase.trim
    if (conversationAliases.contains(key)) ctx.sigil.currentModelFor(ctx.conversation)
    else providerAliases.get(key) match {
      case None => Task.pure(None)
      case Some(canonicalProvider) =>
        Task.pure(
          ctx.sigil.cache.find(provider = Some(canonicalProvider), model = None)
            .sortBy(_._id.value)
            .headOption
            .map(_._id)
        )
    }
  }
}
