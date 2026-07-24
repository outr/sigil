package sigil.tool.core

import fabric.rw.*
import rapid.Task
import sigil.tool.ToolContext
import sigil.event.Message
import sigil.signal.EventState
import sigil.tool.{TextToolOutput, ToolName, ToolResult}
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
case object RespondCardsTool extends RespondFamilyTool {
  type Input = RespondCardsInput
  type Output = TextToolOutput
  val inputRW = summon[RW[RespondCardsInput]]
  val outputRW = summon[RW[TextToolOutput]]

  val name = ToolName("respond_cards")
  val description =
    """Emit a sequence of composite Cards in one reply — for dashboards (multiple metric tiles),
      |result sets (one card per hit), or any response composed of several distinct grouped units.
      |Each card carries its own optional title + kind + sections.
      |
      |- `topicLabel` — 3-6 words.
      |- `topicSummary` — 1-2 sentences.
      |- `cards` — the cards, in order.""".stripMargin

  override def executeResult(input: RespondCardsInput, context: ToolContext): Task[ToolResult[TextToolOutput]] =
    context.emit(Message(
      participantId = context.caller,
      conversationId = context.conversation.id,
      topicId = context.conversation.currentTopicId,
      content = input.cards.map(c => c: sigil.tool.model.ResponseContent),
      state = EventState.Complete,
      modelId = Some(context.modelId)
    )).map(_ => ToolResult.Success(TextToolOutput("")))
}
