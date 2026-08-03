package sigil.conversation

import lightdb.id.Id
import sigil.db.Model

/**
 * Enables framework-generated reply suggestions. Set
 * [[sigil.Sigil.replySuggestions]] to `Some(...)` to turn the feature
 * on; while it is `None` no consult is ever dispatched and the
 * feature costs nothing.
 *
 * After a turn settles with a user-visible reply, a cheap background
 * consult predicts what the user will say next and the framework
 * publishes a transient [[sigil.signal.SuggestedReplies]] notice.
 *
 * @param fallbackModelId model used when no
 *                        [[sigil.provider.ProviderStrategy]] routes
 *                        [[sigil.provider.SummarizationWork]] — pass a
 *                        small, fast model.
 * @param count how many candidates to predict. `1` (the default) asks
 *              for the single most likely message, phrased as inline
 *              type-ahead; `> 1` asks for that many candidates with
 *              deliberately distinct intents, for a chip UI.
 * @param skipWhenOptionsOffered suppress the consult when the settling
 *                               turn already offered the user
 *                               structured options — the options ARE
 *                               the suggestions.
 * @param promptOverride replaces the framework's user prompt. Receives
 *                       the assembled [[ReplySuggestionContext]]; the
 *                       system prompt and the forced
 *                       [[sigil.tool.consult.SuggestReplyTool]] call
 *                       are unchanged.
 */
case class ReplySuggestionsConfig(fallbackModelId: Id[Model],
                                  count: Int = 1,
                                  skipWhenOptionsOffered: Boolean = true,
                                  promptOverride: Option[ReplySuggestionContext => String] = None) {
  require(count >= 1, s"ReplySuggestionsConfig.count must be at least 1, got $count")
}
