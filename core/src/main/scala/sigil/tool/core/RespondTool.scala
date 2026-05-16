package sigil.tool.core

import sigil.TurnContext
import sigil.conversation.ContextFrame
import sigil.event.{Event, Message, MessageDisposition}
import sigil.signal.EventState
import sigil.tool.{ToolExample, ToolName, TypedTool}
import sigil.tool.model.{MarkdownContentParser, RespondInput, ResponseContent, ResponseDisposition}

/**
 * The respond tool — every user-facing reply goes through here. The
 * `content` field is markdown; the framework parses it into typed
 * [[sigil.tool.model.ResponseContent]] blocks via
 * [[sigil.tool.model.MarkdownContentParser]] at turn-settle time. The
 * parser also recognises two markdown extensions:
 *
 *   - `> [!Field icon="…"]\n> Label: Value` — typed [[ResponseContent.Field]]
 *   - `## Heading` — opens a [[ResponseContent.Card]] section
 *
 * `disposition` (Success or Failure) is stamped onto the resulting
 * Message via [[sigil.event.MessageDisposition]] so downstream code
 * (refusal challenge, UI chrome, "show me failed turns" queries)
 * keys off it directly.
 *
 * Topic-shift resolution (NoChange / Refine / New / Return) is handled
 * here from the atomic-respond path using `topicLabel` + `topicSummary`
 * via [[sigil.Sigil.classifyTopicShift]] so every provider (streaming
 * and tool-call-only) produces the same TopicChange shape.
 */
case object RespondTool extends TypedTool[RespondInput](
  name = ToolName("respond"),
  description =
    """Deliver text to the user. Use ONLY when:
      |  (a) the user is chatting or asking a question you can answer from your own knowledge, OR
      |  (b) you've already executed the requested action via another tool and are reporting the outcome.
      |
      |DO NOT use `respond` as a substitute for an action you haven't run. If the user asked you
      |to DO anything — wait, fetch, save, send, run, edit, look up, etc. — first check the
      |listed modes for an obvious match and switch only if one fits the task directly;
      |otherwise search the capability catalog. `respond` is the final step after the action
      |completes, not a way to skip the action.
      |
      |DO NOT use `respond` to ask the user to pick from a fixed set of choices. If your reply
      |would contain a bullet list of options the user is meant to choose from ("Would you like
      |to: A / B / Both / Neither?"), call `respond_options` instead — its options render as
      |clickable controls; bullets inside `respond.content` are inert markdown the user cannot
      |interact with.
      |
      |- `topicLabel` — 3-6 words describing what the CONVERSATION IS ABOUT, not who you are. Fresh
      |  label when the user starts a new subject; keep the current label when following up; reuse a
      |  prior label when the user returns to a previous topic. Avoid agent names (`Sage`, `Claude`,
      |  `Assistant`) and generic catch-alls (`Chat`, `Help`) — they describe the agent, pollute the
      |  topic-shift classifier, and stick forever. For pure-greeting first turns use `Greeting`;
      |  pick a real subject as soon as the next user message reveals one.
      |- `topicSummary` — 1-2 sentences.
      |- `content` — markdown body. Standard markdown (paragraphs, code fences, tables, lists,
      |  links, images, headings) is parsed into typed content blocks. Two markdown extensions
      |  are also recognised inside content:
      |    * `> [!Field icon="…"]\n> Label: Value` — emits a typed Field block (labeled metadata
      |      with optional icon). Use for compact status/source/metadata cards.
      |    * `## Heading` — opens a Card section. Every block under the heading (until the next
      |      `##`) becomes the Card's contents, with the heading as the title. Use `##` to
      |      group related blocks under a labeled container.
      |- `disposition` — `"success"` for normal answers / status / explanations (the default in
      |  spirit; set explicitly per turn). `"failure"` when you cannot complete the requested
      |  work (out of scope, missing capability, a tool failed and you're reporting that). The
      |  framework stamps the resulting Message's disposition accordingly.
      |- `endsTurn` — `true` when this respond is your COMPLETE reply for this turn. Set `false`
      |  for in-flight status pulses you intend to follow up on the same turn.""".stripMargin,
  examples = Nil
) with RespondFamilyTool {
  override def paginate: Boolean = false


  override protected def executeTyped(input: RespondInput, context: TurnContext): rapid.Stream[Event] = {
    val blocks = MarkdownContentParser.parse(input.content)
    val disposition = input.disposition match {
      case ResponseDisposition.Success => MessageDisposition.Success
      case ResponseDisposition.Failure => MessageDisposition.Failure()
    }
    val message = Message(
      participantId  = context.caller,
      conversationId = context.conversation.id,
      topicId        = context.conversation.currentTopicId,
      content        = blocks,
      disposition    = disposition,
      modelId        = context.modelId
    )
    // Topic resolution + keyword update fire here so the atomic-respond
    // path (every provider whose `respond` materialises as a function
    // call: llama.cpp grammar-constrained, OpenAI strict-mode,
    // Anthropic tool_use, Google functionCall) produces the same
    // TopicChange shape as the streaming-respond branch.
    context.modelId match {
      case None =>
        rapid.Stream.emits(List[Event](message))
      case Some(modelId) =>
        val userMessage = context.turnInput.frames.reverseIterator.collectFirst {
          case t: ContextFrame.Text if t.participantId != context.caller => t.content
        }.getOrElse("")
        rapid.Stream.force(
          for {
            topicEvents <- context.sigil.resolveTopicShift(
              proposedLabel   = input.topicLabel,
              proposedSummary = input.topicSummary,
              caller          = context.caller,
              conversation    = context.conversation,
              currentTopic    = context.conversation.currentTopic,
              previousTopics  = context.conversation.previousTopics,
              modelId         = modelId,
              chain           = context.chain,
              userMessage     = userMessage
            )
            _ <- context.sigil.updateConversationKeywords(context.conversation.id, input.keywords)
          } yield rapid.Stream.emits[Event](topicEvents :+ message)
        )
    }
  }
}
