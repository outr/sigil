package sigil.provider

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import rapid.Stream
import _root_.sigil.conversation.ContextFrame
import _root_.sigil.event.Event
import _root_.sigil.tool.ToolName
import _root_.sigil.tool.core.{CoreTools, RespondOptionsTool}

/** Coverage for atomic-content tool wire pairing.
  *
  * Atomic-content tools (`respond_options`, the `respond` family,
  * `no_response`) emit a real `ToolResults` event under the typed
  * tool-execution model — so `renderFrames` pairs each `function_call`
  * with the real `function_call_output`, exactly one, never a
  * fabricated synthetic duplicate (Sigil #259: a leftover synthetic
  * pairing on top of the real one made Anthropic reject the request
  * with two `tool_result` blocks for one `tool_use`). */
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
    "render exactly one function_call_output for a respond_options call, paired by its real ToolResults" in {
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
        ),
        // The respond_options call's real ToolResults — every tool
        // call, atomic-content included, emits one under the typed
        // tool-execution model.
        ContextFrame.ToolResult(
          callId = callId,
          content = """{"text":""}""",
          sourceEventId = Id[Event]("toolresult-source-1")
        )
      )
      val messages = TestProvider.render(frames, agent)

      val cidStr = callId.value
      val assistantWithCall = messages.collectFirst {
        case a: ProviderMessage.Assistant if a.toolCalls.exists(_.id == cidStr) => a
      }
      assistantWithCall should not be empty

      // Exactly one function_call_output for the call — the real
      // ToolResult, never a fabricated synthetic duplicate (#259).
      val pairedOutputs = messages.collect {
        case t: ProviderMessage.ToolResult if t.toolCallId == cidStr => t
      }
      pairedOutputs should have size 1
      pairedOutputs.head.content shouldBe """{"text":""}"""
    }

    "list all 7 atomic content tools" in {
      CoreTools.atomicContentToolNames should have size 7
      CoreTools.atomicContentToolNames.map(_.value) shouldBe Set(
        "respond", "respond_options", "respond_field",
        "respond_failure", "respond_card", "respond_cards", "no_response"
      )
    }
  }
}
