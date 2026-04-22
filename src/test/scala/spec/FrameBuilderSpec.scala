package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.conversation.{ContextFrame, Conversation, FrameBuilder, ParticipantProjection}
import sigil.event.{AgentState, Event, Message, ModeChange, Stop, TitleChange, ToolInvoke, ToolResults}
import sigil.provider.Mode
import sigil.signal.{AgentActivity, EventState}
import sigil.tool.{ToolName, ToolSchema}
import sigil.tool.core.{ChangeModeTool, RespondTool}
import sigil.tool.model.{ChangeModeInput, ResponseContent}

/**
 * Unit coverage for [[FrameBuilder]]. Verifies the pure event → frame
 * translation without touching the DB or provider layers.
 */
class FrameBuilderSpec extends AnyWordSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val conversationId = Conversation.id("framebuilder-conv")

  private def completeMessage(text: String, from: sigil.participant.ParticipantId): Message =
    Message(
      participantId = from,
      conversationId = conversationId,
      content = Vector(ResponseContent.Text(text)),
      state = EventState.Complete
    )

  "FrameBuilder.appendFor" should {
    "produce a Text frame for a Complete Message" in {
      val msg = completeMessage("hello", TestUser)
      val frames = FrameBuilder.appendFor(Vector.empty, msg)
      frames should have size 1
      frames.head shouldBe a[ContextFrame.Text]
      val text = frames.head.asInstanceOf[ContextFrame.Text]
      text.content shouldBe "hello"
      text.participantId shouldBe TestUser
      text.sourceEventId shouldBe msg._id
    }

    "drop events still in EventState.Active" in {
      val msg = Message(
        participantId = TestUser,
        conversationId = conversationId,
        content = Vector(ResponseContent.Text("active")),
        state = EventState.Active
      )
      FrameBuilder.appendFor(Vector.empty, msg) shouldBe empty
    }

    "drop Stop events (control-plane, no frame)" in {
      val stop = Stop(
        participantId = TestUser,
        conversationId = conversationId,
        state = EventState.Complete
      )
      FrameBuilder.appendFor(Vector.empty, stop) shouldBe empty
    }

    "produce a ToolCall frame for a Complete ToolInvoke" in {
      val invoke = ToolInvoke(
        toolName = ChangeModeTool.schema.name,
        participantId = TestAgent,
        conversationId = conversationId,
        input = Some(ChangeModeInput(mode = Mode.Coding, reason = Some("need code mode"))),
        state = EventState.Complete
      )
      val frames = FrameBuilder.appendFor(Vector.empty, invoke)
      frames should have size 1
      val call = frames.head.asInstanceOf[ContextFrame.ToolCall]
      call.toolName shouldBe ToolName("change_mode")
      call.callId shouldBe invoke._id
      call.participantId shouldBe TestAgent
      call.argsJson should include("need code mode")
      // Poly discriminator stripped
      call.argsJson should not include "\"type\""
    }

    "pair a Complete ToolResults with the most-recent pending ToolCall" in {
      val invoke = ToolInvoke(
        toolName = ToolName("find_capability"),
        participantId = TestAgent,
        conversationId = conversationId,
        input = None,
        state = EventState.Complete
      )
      val results = ToolResults(
        schemas = List.empty[ToolSchema[? <: sigil.tool.ToolInput]],
        participantId = TestAgent,
        conversationId = conversationId,
        state = EventState.Complete
      )
      val folded = FrameBuilder.build(List(invoke, results))
      folded should have size 2
      folded(1) shouldBe a[ContextFrame.ToolResult]
      folded(1).asInstanceOf[ContextFrame.ToolResult].callId shouldBe invoke._id
    }

    "emit a System frame for a Complete ModeChange" in {
      val mc = ModeChange(
        mode = Mode.Coding,
        participantId = TestAgent,
        conversationId = conversationId,
        reason = Some("switching"),
        state = EventState.Complete
      )
      val frames = FrameBuilder.appendFor(Vector.empty, mc)
      frames.head shouldBe a[ContextFrame.System]
      frames.head.asInstanceOf[ContextFrame.System].content should (include("Coding") and include("switching"))
    }

    "emit a System frame for a Complete TitleChange" in {
      val tc = TitleChange(
        title = "New Title",
        participantId = TestAgent,
        conversationId = conversationId,
        state = EventState.Complete
      )
      val frames = FrameBuilder.appendFor(Vector.empty, tc)
      frames.head shouldBe a[ContextFrame.System]
      frames.head.asInstanceOf[ContextFrame.System].content should include("New Title")
    }

    "drop AgentState lifecycle markers (control-plane)" in {
      val agentState = AgentState(
        agentId = TestAgent,
        participantId = TestAgent,
        conversationId = conversationId,
        activity = AgentActivity.Thinking,
        state = EventState.Complete
      )
      FrameBuilder.appendFor(Vector.empty, agentState) shouldBe empty
    }
  }

  "FrameBuilder.updateProjections" should {
    "push a tool name onto recentTools when a ToolInvoke completes" in {
      val invoke = ToolInvoke(
        toolName = RespondTool.schema.name,
        participantId = TestAgent,
        conversationId = conversationId,
        state = EventState.Complete
      )
      val projections = FrameBuilder.updateProjections(Map.empty, invoke)
      projections(TestAgent).recentTools shouldBe List(RespondTool.schema.name)
    }

    "dedupe recentTools (head = most recent)" in {
      val first = ToolInvoke(toolName = ToolName("a"), participantId = TestAgent, conversationId = conversationId, state = EventState.Complete)
      val second = ToolInvoke(toolName = ToolName("b"), participantId = TestAgent, conversationId = conversationId, state = EventState.Complete)
      val third = ToolInvoke(toolName = ToolName("a"), participantId = TestAgent, conversationId = conversationId, state = EventState.Complete)
      val after = List(
        first,
        second,
        third).foldLeft(Map.empty[sigil.participant.ParticipantId, ParticipantProjection])(FrameBuilder.updateProjections)
      after(TestAgent).recentTools shouldBe List(ToolName("a"), ToolName("b"))
    }

    "replace suggestedTools when a ToolResults completes" in {
      val results = ToolResults(
        schemas = List.empty[ToolSchema[? <: sigil.tool.ToolInput]],
        participantId = TestAgent,
        conversationId = conversationId,
        state = EventState.Complete
      )
      val projections = FrameBuilder.updateProjections(Map.empty, results)
      projections(TestAgent).suggestedTools shouldBe Nil
    }

    "not update projections for events still Active" in {
      val invoke = ToolInvoke(
        toolName = ToolName("pending"),
        participantId = TestAgent,
        conversationId = conversationId,
        state = EventState.Active
      )
      FrameBuilder.updateProjections(Map.empty, invoke) shouldBe Map.empty
    }
  }
}
