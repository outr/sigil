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
      |- `topicLabel` — 3-6 words describing what the CONVERSATION IS ABOUT, not who you are. Fresh
      |  label when the user starts a new subject; keep the current label when following up; reuse a
      |  prior label when the user returns to a previous topic.
      |
      |  Do NOT use your own name (`Sage`, `Claude`, `Assistant`), the conversation's app name, or
      |  generic catch-alls (`Chat`, `Help`) as `topicLabel` — they describe the agent, not the
      |  topic. If the user's first message is a greeting with no actual task yet, use `Greeting`
      |  or `Initial setup` and let the next user turn drive the real topic. Reserved labels stick
      |  to the conversation forever and pollute the topic-shift classifier on every later turn —
      |  pick a real subject as soon as one exists.
      |- `topicSummary` — 1-2 sentences.
      |- `content` — markdown.
      |- `endsTurn` (optional, default `true`) — `true` when this respond is your COMPLETE reply
      |  for this turn (the work is done, the user has the final answer). Set `false` only when
      |  you intend to continue working on this turn after the user sees this message — e.g.
      |  status updates like "Let me check…", "Reading the auth files now…", "Found 47 matches;
      |  narrowing to admin/…". With `endsTurn = false` the framework iterates the loop again
      |  immediately so you can run more tools; the respond's content shows the user a progress
      |  pulse rather than a permanent reply.
      |
      |  Use `endsTurn = false` ONLY if your very next iteration will actually do the announced
      |  work. Don't promise follow-up that won't happen — the user reads "Let me check…" and
      |  expects results, not silence. If you don't have a concrete next step, finish the turn
      |  cleanly with `endsTurn = true`.
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
      content = blocks,
      modelId = context.modelId
    )
    rapid.Stream.emits(List[Event](message))
  }
}
