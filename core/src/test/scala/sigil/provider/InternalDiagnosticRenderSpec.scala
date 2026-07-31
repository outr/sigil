package sigil.provider

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.conversation.{Conversation, ContextFrame, FrameBuilder, ToolCallState, Topic}
import sigil.db.Model
import sigil.event.Event
import sigil.orchestrator.SyntheticDiagnostic
import sigil.tool.ToolName
import spec.{TestAgent, TestSigil}
import spice.http.HttpRequest

/**
 * Sigil #385 — a framework-internal synthetic diagnostic
 * (`_stall_detected`, `_refusal_challenge`, `_cap_reached`, …) must NOT
 * render as an assistant `tool_use` block. When it did, the model read it
 * as a tool IT had called and mimicked it, emitting a real `_stall_detected`
 * call that failed with "Unknown tool" and looped (observed 9× in one live
 * turn). The directive is now surfaced as an out-of-band `System` note.
 */
class InternalDiagnosticRenderSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private object FakeProvider extends Provider {
    override def `type`: ProviderType = ProviderType.OpenAI
    override def models: List[Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] =
      Stream.emits(List(ProviderEvent.Done(StopReason.Complete)))
  }

  private val directive = "You appear blocked — stop gathering and call `respond` now."

  private def internalFrame: ContextFrame.ToolCall = ContextFrame.ToolCall(
    toolName      = ToolName.internal("_stall_detected"),
    argsJson      = "{}",
    callId        = Id[Event]("synth-1"),
    participantId = TestAgent,
    sourceEventId = Id[Event]("synth-1"),
    internal      = true,
    state         = ToolCallState.Complete(directive, Nil)
  )

  private def assistantToolNames(rendered: Vector[ProviderMessage]): List[String] =
    rendered.collect { case ProviderMessage.Assistant(_, calls) => calls.map(_.name) }.flatten.toList

  "renderFrames (sigil #385)" should {

    "render an internal diagnostic frame as a System note, never an assistant tool_use" in Task {
      val rendered = FakeProvider.renderFrames(Vector(internalFrame), Some(TestAgent))
      // The directive is surfaced as a System note...
      rendered.collect { case ProviderMessage.System(c) => c } should contain(directive)
      // ...and NEVER as an assistant tool_use the model could mimic.
      assistantToolNames(rendered) should not contain "_stall_detected"
      rendered.collect { case ProviderMessage.ToolResult(_, _) => () } shouldBe empty
    }

    "still render a normal agent tool call as an assistant tool_use + tool_result" in Task {
      val normal = ContextFrame.ToolCall(
        toolName      = ToolName("read_file"),
        argsJson      = """{"path":"x"}""",
        callId        = Id[Event]("call-2"),
        participantId = TestAgent,
        sourceEventId = Id[Event]("call-2"),
        internal      = false,
        state         = ToolCallState.Complete("file contents", Nil)
      )
      val rendered = FakeProvider.renderFrames(Vector(normal), Some(TestAgent))
      assistantToolNames(rendered) should contain("read_file")
      rendered.collect { case ProviderMessage.ToolResult(_, c) => c } should contain("file contents")
    }

    "drop an internal frame with empty content rather than emit a blank System note" in Task {
      val blank = internalFrame.copy(state = ToolCallState.Complete("   ", Nil))
      val rendered = FakeProvider.renderFrames(Vector(blank), Some(TestAgent))
      rendered shouldBe empty
    }
  }

  "FrameBuilder (sigil #385)" should {
    "thread `internal` from a synthetic ToolInvoke onto its ToolCall frame" in Task {
      val invoke = SyntheticDiagnostic.invoke(
        "_stall_detected", TestAgent, Conversation.id("c"), Topic.id("t"))
      val frame = FrameBuilder.computeFrame(invoke).getOrElse(fail("no frame"))
      frame.asInstanceOf[ContextFrame.ToolCall].internal shouldBe true
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
