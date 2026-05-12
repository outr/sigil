package sigil.tool.core

import sigil.TurnContext
import sigil.conversation.ContextFrame
import sigil.event.{Event, Message}
import sigil.signal.EventState
import sigil.tool.{ToolExample, ToolName, TypedTool}
import sigil.tool.model.{MarkdownContentParser, RespondContent, RespondInput, ResponseContent}

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
      |to DO anything — wait, fetch, save, send, run, edit, look up, etc. — call `change_mode`
      |first when a listed mode obviously matches the task; otherwise call `find_capability`.
      |`respond` is the final step after the action completes, not a way to skip the action.
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
      |`content` is a tagged-union picking the reply shape:
      |  - `Text(content)`              — plain markdown reply (chat, explanations, code).
      |  - `Failure(reason, recoverable)` — honest "can't do that" signal. `recoverable = true` for
      |    transient failures (rate limits, network) the user can retry; `false` for permanent
      |    (missing permissions, unsupported input).
      |  - `Field(label, value, icon?)` — single labeled key/value card (status, source, metadata).
      |  - `Options(prompt, options, allowMultiple)` — structured multi-choice prompt. The user can
      |    still answer in natural language. `allowMultiple = true` when independent choices the
      |    user might combine; `false` for forced single selection (Yes/No, plan tier, theme).""".stripMargin,
  examples = Nil
) with RespondFamilyTool {

  override protected def executeTyped(input: RespondInput, context: TurnContext): rapid.Stream[Event] = {
    // Bug #157 — dispatch on the tagged-union content slot. Text
    // still flows through MarkdownContentParser; the other kinds
    // map 1:1 to typed ResponseContent blocks. Topic-resolution +
    // keyword-update fires for every kind so atomic-call paths
    // stay symmetric with the streaming-text path.
    val blocks: Vector[ResponseContent] = input.content match {
      case RespondContent.Text(content) =>
        MarkdownContentParser.parse(content)
      case RespondContent.Failure(reason, recoverable) =>
        Vector(ResponseContent.Failure(reason = reason, recoverable = recoverable))
      case RespondContent.Field(label, value, icon) =>
        Vector(ResponseContent.Field(label = label, value = value, icon = icon))
      case RespondContent.Options(prompt, options, allowMultiple) =>
        Vector(ResponseContent.Options(prompt = prompt, options = options, allowMultiple = allowMultiple))
    }
    val message = Message(
      participantId = context.caller,
      conversationId = context.conversation.id,
      topicId = context.conversation.currentTopicId,
      content = blocks,
      modelId = context.modelId
    )
    // Sigil bug: topic resolution + keyword update used to fire only
    // from the orchestrator's streaming-respond branch. Atomic-respond
    // calls (llama.cpp grammar-constrained, OpenAI strict-mode, every
    // provider whose respond materialises as a function call) skipped
    // both, so TopicChange events never fired and `currentKeywords`
    // never updated for those providers. Fire both here so the
    // behaviour is uniform across paths. Bug #2 from the
    // path-discrepancy audit.
    context.modelId match {
      case None =>
        // No modelId on the context (test harnesses, off-turn
        // invocation) — skip topic classification (the classifier
        // needs a model to route through). Just emit the Message.
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
