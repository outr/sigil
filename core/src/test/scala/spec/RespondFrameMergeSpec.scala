package sigil.provider

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import rapid.Stream
import _root_.sigil.conversation.ContextFrame
import _root_.sigil.event.Event
import _root_.sigil.tool.core.RespondTool

/**
 * Coverage for sigil bug #210 — `respond` (and respond-family)
 * emissions render as TWO adjacent assistant messages in the prompt
 * (one plain text from `RespondTool.executeTyped`'s Message event,
 * one tool_call from the framework's `ToolInvoke` event), doubling
 * per-call context cost and reinforcing respond-loop patterns.
 *
 * The OpenAI / Anthropic protocols permit a single assistant message
 * with both `content` and `tool_calls` populated. `renderFrames`
 * should merge the adjacent `ContextFrame.ToolCall` (respond-family)
 * + agent `ContextFrame.Text` into one `ProviderMessage.Assistant`
 * carrying both.
 */
class RespondFrameMergeSpec extends AnyWordSpec with Matchers {

  private object TestProvider extends Provider {
    override protected def sigil: _root_.sigil.Sigil = spec.TestSigil
    override def `type`: ProviderType = ProviderType.OpenAI
    override def call(input: ProviderCall): Stream[ProviderEvent] = Stream.empty
    override def httpRequestFor(input: ProviderCall): rapid.Task[spice.http.HttpRequest] =
      rapid.Task.error(new RuntimeException("not implemented"))
    def render(frames: Vector[ContextFrame], agentId: spec.TestAgent.type): Vector[ProviderMessage] =
      renderFrames(frames, Some(agentId))
  }

  private val agent = spec.TestAgent
  private val callId: Id[Event] = Id[Event]("call-respond-1")

  "Provider.renderFrames (sigil bug #210)" should {

    "collapse adjacent `respond` ToolCall + agent Text into ONE assistant message with content + toolCalls" in {
      val replyText = "Here is the answer to your question."
      val frames = Vector[ContextFrame](
        ContextFrame.Text(
          content       = "What's the answer?",
          participantId = spec.TestUser,
          sourceEventId = Id[Event]("user-msg")
        ),
        ContextFrame.ToolCall(
          toolName      = RespondTool.schema.name,
          argsJson      = s"""{"topicLabel":"x","topicSummary":"y","content":"$replyText","disposition":"Success","endsTurn":true}""",
          callId        = callId,
          participantId = agent,
          sourceEventId = Id[Event]("toolinvoke-respond-1")
        ),
        ContextFrame.Text(
          content       = replyText,
          participantId = agent,
          sourceEventId = Id[Event]("message-respond-1")
        )
      )

      val rendered = TestProvider.render(frames, agent)

      val assistantMessages = rendered.collect { case a: ProviderMessage.Assistant => a }

      // There should be exactly ONE assistant message for the respond
      // (carrying both the text and the tool_call), not two.
      assistantMessages should have size 1
      val a = assistantMessages.head
      a.content shouldBe replyText
      a.toolCalls should have size 1
      a.toolCalls.head.name shouldBe RespondTool.schema.name.value

      // The synthetic empty function_call_output paired with the
      // atomic content tool (bug #19) must still be present.
      val toolResults = rendered.collect { case t: ProviderMessage.ToolResult => t }
      toolResults should have size 1
      toolResults.head.toolCallId shouldBe callId.value
    }

    "merge regardless of which respond-family variant fired" in {
      val replyText = "I cannot complete this task."
      val frames = Vector[ContextFrame](
        ContextFrame.ToolCall(
          toolName      = _root_.sigil.tool.core.RespondFailureTool.schema.name,
          argsJson      = s"""{"reason":"$replyText","recoverable":false}""",
          callId        = callId,
          participantId = agent,
          sourceEventId = Id[Event]("toolinvoke-respond-failure-1")
        ),
        ContextFrame.Text(
          content       = replyText,
          participantId = agent,
          sourceEventId = Id[Event]("message-respond-failure-1")
        )
      )

      val rendered = TestProvider.render(frames, agent)
      val assistants = rendered.collect { case a: ProviderMessage.Assistant => a }
      assistants should have size 1
      assistants.head.content shouldBe replyText
      assistants.head.toolCalls.head.name shouldBe _root_.sigil.tool.core.RespondFailureTool.schema.name.value
    }

    "still produce two separate assistant entries when the agent's Text frame is unrelated to the tool call" in {
      // Defensive: only merge when the Text frame is the immediate
      // successor of the ToolCall AND comes from the same agent. If
      // there's an intervening User text or the order is reversed,
      // leave them as separate messages.
      val unrelated = "Some other text the agent emitted earlier."
      val frames = Vector[ContextFrame](
        ContextFrame.Text(
          content       = unrelated,
          participantId = agent,
          sourceEventId = Id[Event]("earlier-agent-msg")
        ),
        ContextFrame.Text(
          content       = "Now the user asks something.",
          participantId = spec.TestUser,
          sourceEventId = Id[Event]("user-msg-2")
        ),
        ContextFrame.ToolCall(
          toolName      = RespondTool.schema.name,
          argsJson      = """{"topicLabel":"x","topicSummary":"y","content":"reply","disposition":"Success","endsTurn":true}""",
          callId        = callId,
          participantId = agent,
          sourceEventId = Id[Event]("toolinvoke-respond-3")
        ),
        ContextFrame.Text(
          content       = "reply",
          participantId = agent,
          sourceEventId = Id[Event]("message-respond-3")
        )
      )

      val rendered = TestProvider.render(frames, agent)
      val assistants = rendered.collect { case a: ProviderMessage.Assistant => a }
      // Earlier agent text → its own Assistant. Respond merged
      // (ToolCall + Text → one Assistant). Total: 2 assistants.
      assistants should have size 2
      assistants.head.content shouldBe unrelated
      assistants.head.toolCalls shouldBe empty
      assistants.last.content shouldBe "reply"
      assistants.last.toolCalls should have size 1
    }
  }
}
