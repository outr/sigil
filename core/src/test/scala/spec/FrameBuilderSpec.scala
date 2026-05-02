package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.conversation.{ContextFrame, Conversation, FrameBuilder, ParticipantProjection}
import sigil.event.{AgentState, Event, Message, ModeChange, Stop, TopicChange, TopicChangeKind, ToolInvoke, ToolResults}
import sigil.provider.{ConversationMode, Mode}
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
      topicId = TestTopicId,
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
        topicId = TestTopicId,
        content = Vector(ResponseContent.Text("active")),
        state = EventState.Active
      )
      FrameBuilder.appendFor(Vector.empty, msg) shouldBe empty
    }

    "drop Stop events (control-plane, no frame)" in {
      val stop = Stop(
        participantId = TestUser,
        conversationId = conversationId,
        topicId = TestTopicId,
        state = EventState.Complete
      )
      FrameBuilder.appendFor(Vector.empty, stop) shouldBe empty
    }

    "produce a ToolCall frame for a Complete ToolInvoke" in {
      val invoke = ToolInvoke(
        toolName = ChangeModeTool.schema.name,
        participantId = TestAgent,
        conversationId = conversationId,
        topicId = TestTopicId,
        input = Some(ChangeModeInput(mode = "coding", reason = Some("need code mode"))),
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

    "pair a Complete ToolResults with its `origin` ToolCall (bug #69)" in {
      // Bug #69 — pairing is now via the explicit `origin` parent
      // pointer the orchestrator stamps at publish time, not via
      // FrameBuilder's old "most-recent unresolved" scan. The test
      // sets origin = invoke._id to mirror what the orchestrator
      // would do for a real tool emission.
      val invoke = ToolInvoke(
        toolName = ToolName("find_capability"),
        participantId = TestAgent,
        conversationId = conversationId,
        topicId = TestTopicId,
        input = None,
        state = EventState.Complete
      )
      val results = ToolResults(
        schemas = List.empty[ToolSchema],
        participantId = TestAgent,
        conversationId = conversationId,
        topicId = TestTopicId,
        state = EventState.Complete,
        origin = Some(invoke._id)
      )
      val folded = FrameBuilder.build(List(invoke, results))
      folded should have size 2
      folded(1) shouldBe a[ContextFrame.ToolResult]
      folded(1).asInstanceOf[ContextFrame.ToolResult].callId shouldBe invoke._id
    }

    "emit a System frame for a Complete ModeChange" in {
      val mc = ModeChange(
        mode = TestCodingMode,
        participantId = TestAgent,
        conversationId = conversationId,
        topicId = TestTopicId,
        reason = Some("switching"),
        state = EventState.Complete
      )
      val frames = FrameBuilder.appendFor(Vector.empty, mc)
      frames.head shouldBe a[ContextFrame.System]
      frames.head.asInstanceOf[ContextFrame.System].content should (include("Coding") and include("switching"))
    }

    "emit a System frame for a Complete TopicChange (Switch)" in {
      val prev = sigil.conversation.Topic.id("prev-topic")
      val tc = TopicChange(
        kind = TopicChangeKind.Switch(previousTopicId = prev),
        newLabel = "Database Migration",
        newSummary = "Migrating between database systems.",
        participantId = TestAgent,
        conversationId = conversationId,
        topicId = TestTopicId,
        state = EventState.Complete
      )
      val frames = FrameBuilder.appendFor(Vector.empty, tc)
      frames.head shouldBe a[ContextFrame.System]
      frames.head.asInstanceOf[ContextFrame.System].content should include("Database Migration")
    }

    "emit a System frame for a Complete TopicChange (Rename)" in {
      val tc = TopicChange(
        kind = TopicChangeKind.Rename(previousLabel = "General"),
        newLabel = "Scala Coding Setup",
        newSummary = "Setting up a Scala project from scratch.",
        participantId = TestAgent,
        conversationId = conversationId,
        topicId = TestTopicId,
        state = EventState.Complete
      )
      val frames = FrameBuilder.appendFor(Vector.empty, tc)
      frames.head shouldBe a[ContextFrame.System]
      val content = frames.head.asInstanceOf[ContextFrame.System].content
      content should include("General")
      content should include("Scala Coding Setup")
    }

    "drop AgentState lifecycle markers (control-plane)" in {
      val agentState = AgentState(
        agentId = TestAgent,
        participantId = TestAgent,
        conversationId = conversationId,
        topicId = TestTopicId,
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
        topicId = TestTopicId,
        state = EventState.Complete
      )
      val projections = FrameBuilder.updateProjections(Map.empty, invoke)
      projections(TestAgent).recentTools shouldBe List(RespondTool.schema.name)
    }

    "dedupe recentTools (head = most recent)" in {
      val first = ToolInvoke(toolName = ToolName("a"), participantId = TestAgent, conversationId = conversationId, topicId = TestTopicId, state = EventState.Complete)
      val second = ToolInvoke(toolName = ToolName("b"), participantId = TestAgent, conversationId = conversationId, topicId = TestTopicId, state = EventState.Complete)
      val third = ToolInvoke(toolName = ToolName("a"), participantId = TestAgent, conversationId = conversationId, topicId = TestTopicId, state = EventState.Complete)
      val after = List(
        first,
        second,
        third).foldLeft(Map.empty[sigil.participant.ParticipantId, ParticipantProjection])(FrameBuilder.updateProjections)
      after(TestAgent).recentTools shouldBe List(ToolName("a"), ToolName("b"))
    }

    "replace suggestedTools when a ToolResults completes" in {
      val results = ToolResults(
        schemas = List.empty[ToolSchema],
        participantId = TestAgent,
        conversationId = conversationId,
        topicId = TestTopicId,
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
        topicId = TestTopicId,
        state = EventState.Active
      )
      FrameBuilder.updateProjections(Map.empty, invoke) shouldBe Map.empty
    }
  }

  "FrameBuilder Message tool-result extraction (bug #68 Concern B)" should {
    // Pre-fix the tool-result content extraction for `Message` events
    // only collected `ResponseContent.Text` blocks. Tools that emit
    // `Markdown(...)` (e.g. `list_script_tools`) rendered to the
    // empty string — the agent saw no content. Post-fix every
    // text-bearing block contributes.
    "extract Markdown content from a tool-role Message into the paired ToolResult frame" in {
      val invoke = ToolInvoke(
        toolName = ToolName("list_script_tools"),
        participantId = TestAgent,
        conversationId = conversationId,
        topicId = TestTopicId,
        state = EventState.Complete
      )
      val markdownReply = Message(
        participantId = TestAgent,
        conversationId = conversationId,
        topicId = TestTopicId,
        content = Vector(ResponseContent.Markdown("- **tool-a** — does thing A\n- **tool-b** — does thing B")),
        state = EventState.Complete,
        role = sigil.event.MessageRole.Tool,
        origin = Some(invoke._id)
      )
      val frames = FrameBuilder.appendFor(FrameBuilder.appendFor(Vector.empty, invoke), markdownReply)
      // Two frames: the ToolCall + the paired ToolResult carrying
      // the markdown body.
      frames should have size 2
      frames.last shouldBe a[ContextFrame.ToolResult]
      val tr = frames.last.asInstanceOf[ContextFrame.ToolResult]
      tr.callId shouldBe invoke._id
      // The markdown string itself reaches the agent, not the empty
      // string the bug used to deliver.
      tr.content should include ("**tool-a**")
      tr.content should include ("does thing B")
    }

    "extract Code content from a tool-role Message" in {
      val invoke = ToolInvoke(
        toolName = ToolName("show_snippet"),
        participantId = TestAgent,
        conversationId = conversationId,
        topicId = TestTopicId,
        state = EventState.Complete
      )
      val reply = Message(
        participantId = TestAgent,
        conversationId = conversationId,
        topicId = TestTopicId,
        content = Vector(ResponseContent.Code("println(42)", Some("scala"))),
        state = EventState.Complete,
        role = sigil.event.MessageRole.Tool,
        origin = Some(invoke._id)
      )
      val frames = FrameBuilder.appendFor(FrameBuilder.appendFor(Vector.empty, invoke), reply)
      val tr = frames.last.asInstanceOf[ContextFrame.ToolResult]
      tr.content should include ("println(42)")
      tr.content should include ("```scala")
    }

    "still extract Text content (the existing happy path)" in {
      val invoke = ToolInvoke(
        toolName = ToolName("plain_tool"),
        participantId = TestAgent,
        conversationId = conversationId,
        topicId = TestTopicId,
        state = EventState.Complete
      )
      val reply = Message(
        participantId = TestAgent,
        conversationId = conversationId,
        topicId = TestTopicId,
        content = Vector(ResponseContent.Text("plain text")),
        state = EventState.Complete,
        role = sigil.event.MessageRole.Tool,
        origin = Some(invoke._id)
      )
      val frames = FrameBuilder.appendFor(FrameBuilder.appendFor(Vector.empty, invoke), reply)
      frames.last.asInstanceOf[ContextFrame.ToolResult].content shouldBe "plain text"
    }
  }
}
