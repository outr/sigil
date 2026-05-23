package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import rapid.Stream
import sigil.conversation.{ContextFrame, ToolCallState}
import sigil.event.Event
import sigil.provider.{Provider, ProviderCall, ProviderEvent, ProviderMessage, ProviderType}
import sigil.tool.ToolName

/**
 * Coverage for `Provider.renderFrames` when an unpaired
 * `ContextFrame.ToolCall` reaches the renderer.
 *
 * Under the typed tool-execution model every tool call is paired
 * with its result event by construction, so the wire-side
 * orphan-heal that used to fabricate a `function_call_output` is
 * removed. This spec pins the new behavior: unpaired calls render
 * WITHOUT a synthesized output (a real bug surfaces loudly rather
 * than being masked), paired calls render their real result, and a
 * stray `ToolResult` for an unknown call doesn't crash the renderer.
 */
class UnpairedFunctionCallSpec extends AnyWordSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private object TestProvider extends Provider {
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def `type`: ProviderType = ProviderType.OpenAI
    override def call(input: ProviderCall): Stream[ProviderEvent] = Stream.empty
    override def httpRequestFor(input: ProviderCall): rapid.Task[spice.http.HttpRequest] =
      rapid.Task.error(new RuntimeException("not implemented"))
    def render(frames: Vector[ContextFrame], agentId: TestAgent.type): Vector[ProviderMessage] =
      renderFrames(frames, Some(agentId))
  }

  private val agent = TestAgent
  private val callA: Id[Event] = Id[Event]("call-A")
  private val callB: Id[Event] = Id[Event]("call-B")
  private val callC: Id[Event] = Id[Event]("call-C")
  private val nonAtomicName = ToolName("vector_lookup")

  "Provider.renderFrames with unpaired tool calls" should {

    "synthesize NO fallback output for unpaired tool calls" in {
      // Two non-atomic ToolCalls in a row with no intervening
      // ToolResult. The wire-side orphan-heal is removed — the
      // renderer ships them as-is (and logs a framework-bug error);
      // it does NOT fabricate placeholder outputs.
      val frames = Vector[ContextFrame](
        ContextFrame.ToolCall(
          toolName = nonAtomicName,
          argsJson = """{"q":"a"}""",
          callId = callA,
          participantId = agent,
          sourceEventId = Id[Event]("frame-A")
        ),
        ContextFrame.ToolCall(
          toolName = nonAtomicName,
          argsJson = """{"q":"b"}""",
          callId = callB,
          participantId = agent,
          sourceEventId = Id[Event]("frame-B")
        )
      )
      val messages = TestProvider.render(frames, agent)
      val outputIds = messages.collect {
        case t: ProviderMessage.ToolResult => t.toolCallId
      }.toSet
      outputIds shouldBe empty
    }

    "render a real ToolResult for a paired call and nothing for the unpaired one" in {
      // callA paired (Complete state), callB unpaired (Active state).
      val frames = Vector[ContextFrame](
        ContextFrame.ToolCall(nonAtomicName, """{"q":"a"}""", callA, agent,
          sourceEventId = Id[Event]("frame-A"),
          state = ToolCallState.Complete("real-result-A")),
        ContextFrame.ToolCall(nonAtomicName, """{"q":"b"}""", callB, agent,
          sourceEventId = Id[Event]("frame-B"))
      )
      val messages = TestProvider.render(frames, agent)
      val resultsByCall = messages.collect {
        case t: ProviderMessage.ToolResult => t.toolCallId -> t.content
      }.toMap
      // The paired call renders its real result; the unpaired call
      // gets no synthesized output.
      resultsByCall(callA.value) shouldBe "real-result-A"
      resultsByCall.keySet should not contain callB.value
    }

    "tolerate a ToolResult arriving for a call that was never seen (no crash)" in {
      // TODO(#261): semantics changed, review — under the unified
      // ToolCall(state) model, an orphan ToolResult cannot exist as
      // a frame; FrameBuilder.appendFor folds it into a Text fallback.
      // Simulate that fallback at the renderer level.
      val frames = Vector[ContextFrame](
        ContextFrame.Text(
          content       = s"[framework: orphan tool result for callId=${callC.value} — content: orphan-result]",
          participantId = agent,
          sourceEventId = Id[Event]("result-C")
        )
      )
      noException should be thrownBy TestProvider.render(frames, agent)
    }
  }

  "Provider.renderFrames atomic-content tool pairing (Sigil #259)" should {

    "render exactly one ToolResult for a settled atomic-content (respond) call" in {
      // A settled `respond` turn: the tool call, the user-visible reply
      // Text, and the real ToolResult. `respond` is an atomic-content
      // tool — pre-fix, `renderFrames` ALSO fabricated a synthetic
      // empty ToolResult for atomic tools, so the call rendered TWO
      // results. Anthropic rejects that ("each tool_use must have a
      // single result"); the framework now pairs every call by its
      // real ToolResults, so exactly one must be rendered.
      val respondCall: Id[Event] = Id[Event]("respond-call")
      val frames = Vector[ContextFrame](
        ContextFrame.ToolCall(
          toolName = ToolName("respond"),
          argsJson = """{"content":"hi"}""",
          callId = respondCall,
          participantId = agent,
          sourceEventId = Id[Event]("respond-invoke"),
          state = ToolCallState.Complete("""{"text":""}""")
        ),
        ContextFrame.Text(
          content = "hi",
          participantId = agent,
          sourceEventId = Id[Event]("respond-message")
        )
      )
      val messages = TestProvider.render(frames, agent)
      val results = messages.collect { case t: ProviderMessage.ToolResult => t }
      // Exactly one — the real ToolResult, not a fabricated empty one.
      results should have size 1
      results.head.toolCallId shouldBe respondCall.value
      results.head.content shouldBe """{"text":""}"""
    }
  }
}
