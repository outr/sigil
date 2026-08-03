package sigil.conversation

import lightdb.id.Id
import sigil.event.Event

/**
 * Everything the reply-suggestion consult knows about the turn it is
 * predicting from. Handed to
 * [[ReplySuggestionsConfig.promptOverride]] so apps can rewrite the
 * user prompt without re-deriving the conversation state the
 * framework already assembled.
 *
 * `agentReply` is the settled reply's text; `userMessage` is the
 * message that triggered the turn; `recentExchange` is a bounded
 * plain-text tail of the conversation rendered by
 * [[sigil.conversation.compression.TranscriptRenderer]] (empty when
 * the reply IS the whole exchange). `count` is the number of
 * suggestions requested — an override that ignores it will disagree
 * with the notice the framework publishes.
 */
case class ReplySuggestionContext(conversationId: Id[Conversation],
                                  replyMessageId: Id[Event],
                                  agentReply: String,
                                  userMessage: String,
                                  recentExchange: String,
                                  count: Int)
