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
 *
 * **Deprecated** (sigil bug #157) — the unified `respond` tool accepts
 * `RespondContent.Options(...)` directly. Kept for backwards-
 * compatibility with apps that registered this tool by name.
 */
@deprecated("Use `RespondTool` with `RespondContent.Options(...)`. Sigil bug #157.", "0.x")
case object RespondOptionsTool extends TypedTool[RespondOptionsInput](
  name = ToolName("respond_options"),
  description =
    """Offer the user a fixed set of selectable choices. The user may also answer in natural language.
      |
      |## How to set `allowMultiple` (required)
      |
      |**Decide based on the user's ORIGINAL QUESTION, not the options you construct.** Even if the
      |options you generate happen to look mutually exclusive, if the question admits picking more than
      |one, set `allowMultiple = true`.
      |
      |**Use `allowMultiple = true` when:**
      |- The question asks `What X should I have/use/add/include/list/enable?` with X plural (features,
      |  dependencies, skills, languages, channels, integrations, plugins, settings, tags, categories,
      |  toppings, options).
      |- The question asks `Which X (plural)?` and the X are independent attributes the user might combine.
      |- Anywhere the user could reasonably answer with `several` or `all of them` or `none of them`.
      |
      |**Use `allowMultiple = false` only when:**
      |- The question is a binary Yes/No / True/False / On/Off / Confirm/Cancel.
      |- The question asks the user to pick exactly one of mutually-exclusive states (`development` OR
      |  `production`, `free` OR `pro` OR `enterprise`, `light` OR `dark` theme).
      |- The question explicitly contains `which one` or `pick one`.
      |
      |**When in doubt, use `true`** — the user can still pick exactly one if that is what they want, but
      |they cannot escape a forced single-select if multi was correct.
      |
      |### Worked examples
      |
      |- `What test dependencies should I add?` → `allowMultiple = true`.
      |- `What features should the app have?` → `allowMultiple = true`.
      |- `Should I commit this change?` → `allowMultiple = false`.
      |- `Which plan: free, pro, or enterprise?` → `allowMultiple = false`.
      |
      |An option with `exclusive = true` (multi-select only) cannot be combined with others (e.g. a
      |"None of these" escape hatch).""".stripMargin,
  examples = List(
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
) with RespondFamilyTool {
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
      state = EventState.Complete,
      modelId = context.modelId
    )))
  }
}
