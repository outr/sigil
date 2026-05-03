package sigil.tool.core

import sigil.TurnContext
import sigil.event.{Event, Message}
import sigil.signal.EventState
import sigil.tool.{ToolName, TypedTool}
import sigil.tool.model.RespondCardsInput

/**
 * Emit a sequence of Cards in a single Message. Multi-card variant
 * of [[RespondCardTool]]; the agent uses this when several distinct
 * cards belong to the same logical reply (a dashboard with multiple
 * tiles, a search result set rendered card-per-hit).
 *
 * Each card is one [[sigil.tool.model.ResponseContent.Card]] block;
 * the Message's `content` carries them in order so renderers project
 * each card with its own native grouping.
 */
case object RespondCardsTool extends TypedTool[RespondCardsInput](
  name = ToolName("respond_cards"),
  description =
    """Emit a sequence of composite Cards in one reply — for dashboards (multiple metric tiles),
      |result sets (one card per hit), or any response composed of several distinct grouped units.
      |Each card carries its own optional title + kind + sections.
      |
      |- `topicLabel` — 3-6 words.
      |- `topicSummary` — 1-2 sentences.
      |- `cards` — the cards, in order.""".stripMargin,
  examples = Nil
) {
  override protected def executeTyped(input: RespondCardsInput, context: TurnContext): rapid.Stream[Event] =
    rapid.Stream.emits(List(Message(
      participantId = context.caller,
      conversationId = context.conversation.id,
      topicId = context.conversation.currentTopicId,
      content = input.cards.map(c => c: sigil.tool.model.ResponseContent),
      state = EventState.Complete
    )))
}
