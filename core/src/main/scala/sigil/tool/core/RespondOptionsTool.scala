package sigil.tool.core

import sigil.TurnContext
import sigil.event.{Event, Message}
import sigil.signal.EventState
import sigil.tool.{ToolExample, ToolName, TypedTool}
import sigil.tool.model.{RespondOptionsInput, ResponseContent, SelectOption}

/**
 * Emit a structured multiple-choice block as part of the agent's reply.
 * Markdown can't natively express interactive choices, so this is one of
 * the small set of atomic content tools that complement the plain
 * markdown content stream.
 *
 * Emits a fresh Complete `Message` carrying the Options block as its
 * single content entry. Multi-block replies that mix markdown with
 * structured Options will produce multiple Message events on the wire;
 * each renders as its own UI bubble / card.
 */
case object RespondOptionsTool extends TypedTool[RespondOptionsInput](
  name = ToolName("respond_options"),
  description =
    """Offer the user a fixed set of selectable choices. Use when the user benefits from a structured
      |question rather than a free-text reply. The user can still answer in natural language.
      |
      |- `prompt` — the question shown above the options.
      |- `options` — the choices, in display order.
      |- `allowMultiple` — false (default) = exactly one choice; true = zero or more, with any
      |  option marked exclusive=true unable to be combined with others (e.g. "None of the above").""".stripMargin,
  examples = List(
    ToolExample(
      "Single-select region picker",
      RespondOptionsInput(
        prompt = "Region",
        options = List(
          SelectOption(label = "US East", value = "us-east"),
          SelectOption(label = "EU West", value = "eu-west")
        )
      )
    ),
    ToolExample(
      "Multi-select with exclusive 'None'",
      RespondOptionsInput(
        prompt = "Notification channels",
        options = List(
          SelectOption(label = "Email", value = "email"),
          SelectOption(label = "SMS", value = "sms"),
          SelectOption(label = "None", value = "none", exclusive = true)
        ),
        allowMultiple = true
      )
    )
  )
) {
  override protected def executeTyped(input: RespondOptionsInput, context: TurnContext): rapid.Stream[Event] = {
    val block = ResponseContent.Options(
      prompt = input.prompt,
      options = input.options,
      allowMultiple = input.allowMultiple
    )
    rapid.Stream.emits(List(Message(
      participantId = context.caller,
      conversationId = context.conversation.id,
      topicId = context.conversation.currentTopicId,
      content = Vector(block),
      state = EventState.Complete
    )))
  }
}
