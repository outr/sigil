package sigil.provider

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import rapid.Stream
import _root_.sigil.conversation.ContextFrame
import _root_.sigil.event.Event
import _root_.sigil.tool.ToolName
import _root_.sigil.tool.core.{CoreTools, RespondOptionsTool}

/**
 * Coverage for sigil bug #19 — atomic content tools like
 * `respond_options` emit a Standard-role Message instead of a
 * Tool-role ToolResults, leaving the model's `function_call`
 * orphaned in wire history. OpenAI's Responses API rejects on the
 * next request. The framework's frame renderer pairs each atomic
 * call with an empty synthetic `function_call_output` so the wire
 * shape stays valid.
 */
class AtomicContentToolWirePairingSpec extends AnyWordSpec with Matchers {

  // Test-only Provider exposing `renderFrames` (which is
  // `protected[provider]` in the framework). Lives in `sigil.provider`
  // package so the visibility check passes.
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
  private val callId: Id[Event] = Id[Event]("call-respond-options-1")

  "Provider.renderFrames" should {
    "synthesize an empty function_call_output paired with respond_options call (sigil bug #19)" in {
      val frames = Vector[ContextFrame](
        ContextFrame.Text(
          content = "Bind the workspace.",
          participantId = spec.TestUser,
          sourceEventId = Id[Event]("user-msg")
        ),
        ContextFrame.ToolCall(
          toolName = RespondOptionsTool.schema.name,
          argsJson = """{"prompt":"OK?","options":[{"label":"Yes","value":"yes"}],"allowMultiple":false}""",
          callId = callId,
          participantId = agent,
          sourceEventId = Id[Event]("frame-source-1")
        ),
        ContextFrame.Text(
          content = "Workspace bound.",
          participantId = agent,
          sourceEventId = Id[Event]("agent-msg")
        )
      )
      val messages = TestProvider.render(frames, agent)

      val cidStr = callId.value
      val assistantWithCall = messages.collectFirst {
        case a: ProviderMessage.Assistant if a.toolCalls.exists(_.id == cidStr) => a
      }
      assistantWithCall should not be empty

      val pairedOutput = messages.collectFirst {
        case t: ProviderMessage.ToolResult if t.toolCallId == cidStr => t
      }
      pairedOutput should not be empty
      pairedOutput.get.content shouldBe ""
    }

    "list all 7 atomic content tools" in {
      CoreTools.atomicContentToolNames should have size 7
      CoreTools.atomicContentToolNames.map(_.value) shouldBe Set(
        "respond",
        "respond_options",
        "respond_field",
        "respond_failure",
        "respond_card",
        "respond_cards",
        "no_response"
      )
    }
  }
}
