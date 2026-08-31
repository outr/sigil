package sigil.conversation

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.event.{Event, Message, MessageDisposition, MessageRole, MessageVisibility, ToolInvoke, ToolOutcome}
import sigil.signal.EventState
import sigil.tool.model.ResponseContent
import sigil.tool.{TextToolOutput, ToolName}
import spec.{ProbeReadInput, TestAgent, TestSigil}

/**
 * Re-projecting a tool call's frame is monotonic on the result
 * channel: a payload the pairing path delivered is never rewritten
 * back into the "result hasn't landed" placeholder by a later write
 * that says nothing about the result, and the placeholder still
 * appears while a result genuinely has not landed.
 */
class ToolFrameReprojectionSpec extends AnyWordSpec with Matchers {
  // Rendering a tool input goes through the polymorphic RW roster; no
  // store is opened, so this is registration only.
  TestSigil.polymorphicRegistrations.sync()

  private val convId = Conversation.id("reprojection")
  private val topicId = Id[Topic]("reprojection-topic")
  private val invokeId = Id[Event]("invoke-1")

  private def invoke(outcome: ToolOutcome = ToolOutcome.Pending,
                     output: sigil.tool.ToolOutput = sigil.tool.ToolOutput.Pending): ToolInvoke =
    ToolInvoke(
      toolName = ToolName("live_probe_read"),
      participantId = TestAgent,
      conversationId = convId,
      topicId = topicId,
      _id = invokeId,
      state = EventState.Complete,
      input = Some(ProbeReadInput(probe = "alpha")),
      outcome = outcome,
      output = output
    )

  private def pairedResult(text: String): Message =
    Message(
      participantId = TestAgent,
      conversationId = convId,
      topicId = topicId,
      role = MessageRole.Tool,
      content = Vector(ResponseContent.Text(text)),
      state = EventState.Complete,
      disposition = MessageDisposition.Failure(recoverable = true),
      visibility = MessageVisibility.Agents,
      origin = Some(invokeId)
    )

  private def toolCall(frame: Option[ContextFrame]): ContextFrame.ToolCall =
    frame.collect { case tc: ContextFrame.ToolCall => tc }
      .getOrElse(fail(s"expected a ToolCall frame, got $frame"))

  "Frame re-projection" should {
    "render the race placeholder while the result genuinely has not landed" in {
      val frame = toolCall(FrameBuilder.reprojected(invoke()))
      frame.resultPending shouldBe true
      frame.state match {
        case ToolCallState.Complete(content, _) => content should include("result did not reach this turn")
        case other => fail(s"expected a settled placeholder, got $other")
      }
    }

    "keep a payload the pairing path delivered when a later write re-projects the row" in {
      // The refuse paths settle the invoke outcome-Pending on purpose and
      // deliver their note as a paired Tool-role event. A usage fold
      // landing on that same row afterwards re-projects it — from the
      // invoke alone, which knows nothing about the note.
      val settled = invoke().withContextFrame(
        Some(FrameBuilder.settledPairedFrame(toolCall(FrameBuilder.reprojected(invoke())), pairedResult("Refused -- already called."))))
      val frame = toolCall(FrameBuilder.reprojected(settled))
      frame.resultPending shouldBe false
      frame.state shouldBe ToolCallState.Complete("Refused -- already called.", Nil)
    }

    "adopt a real result that arrives after the placeholder was rendered" in {
      val pending = invoke().withContextFrame(FrameBuilder.reprojected(invoke()))
      val late = invoke(outcome = ToolOutcome.Success, output = TextToolOutput("live-probe-result:alpha"))
        .withContextFrame(pending.contextFrame)
      val frame = toolCall(FrameBuilder.reprojected(late))
      frame.resultPending shouldBe false
      frame.state shouldBe ToolCallState.Complete("live-probe-result:alpha", Nil)
    }
  }
}
