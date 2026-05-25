package sigil.tool.core

import fabric.rw.*
import rapid.Task
import sigil.tool.ToolContext
import sigil.event.Message
import sigil.signal.EventState
import sigil.tool.{TextToolOutput, ToolExample, ToolName, ToolResult}
import sigil.tool.model.{RespondOptionsInput, ResponseContent, SelectOption}

/**
 * Ask the user to pick from a fixed set of selectable choices. Distinct
 * from `respond` — `respond` is for telling, `respond_options` is for
 * asking. The reply correlates with the next user message.
 *
 * Emits a fresh Complete `Message` carrying the Options block as its
 * single content entry. The user may answer by picking from the list
 * or by replying in natural language — both flow back through the
 * normal Message channel.
 */
case object RespondOptionsTool extends RespondFamilyTool {
  type Input  = RespondOptionsInput
  type Output = TextToolOutput
  val inputRW  = summon[RW[RespondOptionsInput]]
  val outputRW = summon[RW[TextToolOutput]]

  val name = ToolName("respond_options")
  val description =
    """Ask the user to pick from a closed set of options. Options render as clickable controls
      |(buttons / radio / checkboxes); markdown bullets in `respond.content` do not.
      |
      |**Use ONLY** when your reply is a question whose answer must come from a fixed set you supply:
      |  - "Would you like: A / B / Both / Neither?"
      |  - "Should I X?"  (Yes / No)
      |  - "Which Y?"  (one or more from a fixed list)
      |
      |**DO NOT use** for: factual answers, explanations, status pulses, open-ended questions (free-text
      |reply expected), replies that mention items without asking the user to pick, or post-action
      |reports. Those go through `respond`.
      |
      |`allowMultiple` — `true` when the question admits picking several (plural Xs, independent
      |attributes, "any of these"); `false` for binary or mutually-exclusive picks. Decide from the
      |user's original question, not the options you generated. When unsure, prefer `true` — the user
      |can still pick exactly one, but can't escape a forced single-select if multi was correct.
      |
      |An option with `exclusive = true` (multi-select only) cannot be combined with others (e.g. a
      |"None of these" escape hatch).""".stripMargin

  override val examples: List[ToolExample] = List(
    ToolExample(
      "single-select — forced choice between mutually-exclusive options",
      RespondOptionsInput(
        prompt        = "Should I commit this change?",
        options       = List(SelectOption("Yes", "yes"), SelectOption("No", "no")),
        allowMultiple = false
      )
    ),
    ToolExample(
      "multi-select — independent choices the user can pick in any combination",
      RespondOptionsInput(
        prompt        = "Which integrations should I enable?",
        options       = List(
          SelectOption("Slack", "slack"),
          SelectOption("Email", "email"),
          SelectOption("Discord", "discord")
        ),
        allowMultiple = true
      )
    )
  )

  override def executeResult(input: RespondOptionsInput, context: ToolContext): Task[ToolResult[TextToolOutput]] = {
    val block = ResponseContent.Options(
      prompt = input.prompt,
      options = input.options,
      allowMultiple = input.allowMultiple
    )
    context.emit(Message(
      participantId = context.caller,
      conversationId = context.conversation.id,
      topicId = context.conversation.currentTopicId,
      content = Vector(block),
      state = EventState.Complete,
      modelId = Some(context.modelId)
    )).map(_ => ToolResult.Success(TextToolOutput("")))
  }
}
