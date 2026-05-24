package sigil.tool.core

import fabric.rw.*
import rapid.Task
import sigil.tool.ToolContext
import sigil.event.Message
import sigil.signal.EventState
import sigil.tool.{TextToolOutput, ToolName, ToolResult}
import sigil.tool.model.RespondCardInput

/**
 * Emit a single composite Card. The agent constructs a card from the
 * standard [[sigil.tool.model.ResponseContent]] palette (Heading,
 * Field, Code, ItemList, Image, Options, Table, …) and the framework
 * wraps it in one Message — a natural unit for the UI to render as a
 * styled block, for renderers (markdown / HTML / Slack mrkdwn) to
 * project into their format, and for the wire to carry as one event.
 *
 * For multi-card responses (a list of dashboard tiles, a result set),
 * use `respond_cards` instead — it emits all cards in a single
 * Message rather than forcing N separate ones.
 */
case object RespondCardTool extends RespondFamilyTool {
  type Input  = RespondCardInput
  type Output = TextToolOutput
  val inputRW  = summon[RW[RespondCardInput]]
  val outputRW = summon[RW[TextToolOutput]]

  val name = ToolName("respond_card")
  val description =
    """Emit a composite Card — a titled, optionally-kinded grouping of standard content blocks
      |(Heading, Field, Code, ItemList, Image, Options, Table, etc.). Use when several blocks
      |belong together as one logical unit (a status panel, a recipe summary, a metric card).
      |
      |- `topicLabel` — 3-6 words. Fresh on a new subject; reuse the current label otherwise.
      |- `topicSummary` — 1-2 sentences.
      |- `card.title` — optional card header (renderer styles distinct from inner Heading blocks).
      |- `card.kind` — optional UI styling hint (e.g. "alert", "info", "metric", "recipe").
      |- `card.sections` — the building blocks, in order. Recursive: nested Cards are allowed.""".stripMargin

  override def executeResult(input: RespondCardInput, context: ToolContext): Task[ToolResult[TextToolOutput]] =
    context.emit(Message(
      participantId = context.caller,
      conversationId = context.conversation.id,
      topicId = context.conversation.currentTopicId,
      content = Vector(input.card),
      state = EventState.Complete,
      modelId = context.modelId
    )).map(_ => ToolResult.Success(TextToolOutput("")))
}
