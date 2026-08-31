package sigil.governor

import lightdb.id.Id
import sigil.conversation.{Conversation, Topic}
import sigil.db.Model
import sigil.event.Event
import sigil.participant.ParticipantId
import sigil.provider.StopReason

/**
 * The evidence an [[OutcomeGovernor]] reads: everything the just-drained
 * iteration produced, distilled from the orchestrator's turn state at the
 * moment the provider stream closed.
 *
 * The addressing fields (`caller` / `conversationId` / `topicId` /
 * `modelId` / `modelDisplayName`) are carried alongside the evidence so a
 * governor can mint the events it votes for without reaching back into
 * the request.
 *
 * @param caller                 the agent whose turn produced this outcome
 * @param conversationId         the conversation the turn ran in
 * @param topicId                the topic active for the turn
 * @param modelId                the model that generated the response
 * @param modelDisplayName       display name stamped onto minted Messages
 * @param stopReason             why the provider ended the stream
 * @param sawToolCall            the response contained at least one tool call
 * @param activeMessageCreated   a user-visible Message was born mid-stream
 *                               and has already streamed to clients
 * @param activeMessageId        that Message's id — set by a `ThinkingDelta`
 *                               even when no Message was born, so it is the
 *                               id a mint reuses rather than proof of one
 * @param streamedText           text accumulated for the in-flight Message
 *                               (the `ContentBlockDelta` wire shape)
 * @param bufferedText           assistant prose with no Message behind it
 *                               (the `TextDelta` chat-completions shape)
 * @param generatedText          every content token the turn generated —
 *                               the repetition detector's input
 * @param forceResponseSynthesis this turn was the framework's forced
 *                               wrap-up; the framework has had its say
 * @param contextPressured       this turn's context was elided under budget
 *                               pressure, so the model could only narrate
 */
final case class TurnOutcome(caller: ParticipantId,
                             conversationId: Id[Conversation],
                             topicId: Id[Topic],
                             modelId: Id[Model],
                             modelDisplayName: Option[String],
                             stopReason: StopReason,
                             sawToolCall: Boolean,
                             activeMessageCreated: Boolean,
                             activeMessageId: Option[Id[Event]],
                             streamedText: String,
                             bufferedText: String,
                             generatedText: String,
                             forceResponseSynthesis: Boolean,
                             contextPressured: Boolean)
