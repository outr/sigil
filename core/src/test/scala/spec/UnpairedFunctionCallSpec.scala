package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import rapid.Stream
import sigil.conversation.ContextFrame
import sigil.event.Event
import sigil.provider.{Provider, ProviderCall, ProviderEvent, ProviderMessage, ProviderType}
import sigil.tool.ToolName

/**
 * Coverage for sigil bug #167 — frame renderer must produce a
 * `function_call_output` for every `function_call` in the wire input,
 * even when multiple unpaired tool calls land back-to-back (the prior
 * `pendingToolCallId: Option[String]` overwrote on the second call,
 * silently shipping the first one unpaired and 400ing OpenAI's
 * Responses API).
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

  "Provider.renderFrames (Bug #167 — multi-pending fallback)" should {

    "synthesize fallback outputs for every unpaired tool call, not just the most recent" in {
      // Two non-atomic ToolCalls in a row with no intervening ToolResult.
      // Pre-fix: pendingToolCallId = Some(callB) overwrote Some(callA);
      // only callB got the fallback ToolResult; callA shipped unpaired.
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
      outputIds shouldBe Set(callA.value, callB.value)
    }

    "still clear the in-band paired call before falling back on the unpaired one" in {
      // callA paired (has matching ToolResult), callB unpaired.
      val frames = Vector[ContextFrame](
        ContextFrame.ToolCall(nonAtomicName, """{"q":"a"}""", callA, agent,
          sourceEventId = Id[Event]("frame-A")),
        ContextFrame.ToolResult(callA, "real-result-A",
          sourceEventId = Id[Event]("result-A")),
        ContextFrame.ToolCall(nonAtomicName, """{"q":"b"}""", callB, agent,
          sourceEventId = Id[Event]("frame-B"))
      )
      val messages = TestProvider.render(frames, agent)
      val resultsByCall = messages.collect {
        case t: ProviderMessage.ToolResult => t.toolCallId -> t.content
      }.toMap
      resultsByCall(callA.value) shouldBe "real-result-A"
      resultsByCall.keySet should contain (callB.value)
      // callB's content is the framework's brief failure marker.
      resultsByCall(callB.value) should include ("tool failed")
    }

    "tolerate a ToolResult arriving for a call that was never pending (no crash)" in {
      // Pathological: ToolResult with a call_id we never saw on the
      // assistant side. Previous logic silently no-op'd; new logic
      // simply removes it from the empty pending set — same effect,
      // but verifies we don't crash on the unknown id.
      val frames = Vector[ContextFrame](
        ContextFrame.ToolResult(callC, "orphan-result",
          sourceEventId = Id[Event]("result-C"))
      )
      noException should be thrownBy TestProvider.render(frames, agent)
    }

    "produce three fallbacks when three non-atomic calls arrive unpaired" in {
      val frames = Vector[ContextFrame](
        ContextFrame.ToolCall(nonAtomicName, "{}", callA, agent,
          sourceEventId = Id[Event]("f1")),
        ContextFrame.ToolCall(nonAtomicName, "{}", callB, agent,
          sourceEventId = Id[Event]("f2")),
        ContextFrame.ToolCall(nonAtomicName, "{}", callC, agent,
          sourceEventId = Id[Event]("f3"))
      )
      val messages = TestProvider.render(frames, agent)
      val outputIds = messages.collect {
        case t: ProviderMessage.ToolResult => t.toolCallId
      }
      // Order preserved (LinkedHashSet) — fallback outputs appear after
      // the assistant call entries.
      outputIds shouldBe Vector(callA.value, callB.value, callC.value)
    }
  }
}
