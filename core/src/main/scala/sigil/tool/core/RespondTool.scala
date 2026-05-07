package sigil.tool.core

import sigil.TurnContext
import sigil.event.{Event, Message}
import sigil.signal.EventState
import sigil.tool.{ToolExample, ToolName, TypedTool}
import sigil.tool.model.{MarkdownContentParser, RespondInput}

/**
 * The respond tool — every user-facing reply goes through here. The
 * `content` field is plain markdown (code fences, headings, images,
 * lists, links, tables); the framework parses it into typed
 * [[sigil.tool.model.ResponseContent]] blocks at turn-settle time.
 *
 * Topic-shift resolution (NoChange / Refine / New / Return) is handled
 * by the orchestrator at [[sigil.provider.ProviderEvent.ToolCallComplete]]
 * time using `topicLabel` + `topicSummary` and the framework's two-step
 * classifier (see [[sigil.Sigil.classifyTopicShift]]).
 */
case object RespondTool extends TypedTool[RespondInput](
  name = ToolName("respond"),
  description =
    """Deliver text to the user. Use ONLY when:
      |  (a) the user is chatting or asking a question you can answer from your own knowledge, OR
      |  (b) you've already executed the requested action via another tool and are reporting the outcome.
      |
      |DO NOT use `respond` as a substitute for an action you haven't run. If the user asked you
      |to DO anything — wait, fetch, save, send, run, edit, look up, etc. — your first call must
      |be `find_capability`. `respond` is the final step after the action completes, not a way to
      |skip the action.
      |
      |- `topicLabel` — 3-6 words. Fresh label when the user starts a new subject; keep the current
      |  label when following up; reuse a prior label when the user returns to a previous topic.
      |- `topicSummary` — 1-2 sentences.
      |- `content` — markdown.
      |
      |For interactive choices, labeled key/value cards, or failure signals: use `respond_options`,
      |`respond_field`, `respond_failure`.""".stripMargin,
  examples = Nil
) {
  override protected def executeTyped(input: RespondInput, context: TurnContext): rapid.Stream[Event] = {
    val blocks = MarkdownContentParser.parse(input.content)
    val message = Message(
      participantId = context.caller,
      conversationId = context.conversation.id,
      topicId = context.conversation.currentTopicId,
      content = blocks
    )
    rapid.Stream.emits(List[Event](message))
  }
}
