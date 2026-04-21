package spec

import fabric.rw.*
import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.conversation.{ActiveSkillSlot, Conversation, ConversationContext, ContextKey, ContextMemory, ContextSummary, MemorySource, ParticipantContext, SkillSource}
import sigil.db.Model
import sigil.event.{AgentState, Event, Message, ModeChange, TitleChange, ToolInvoke, ToolResults}
import sigil.information.{FullInformation, Information}
import sigil.participant.ParticipantId
import sigil.provider.{GenerationSettings, Instructions, Mode, ProviderRequest}
import sigil.provider.llamacpp.LlamaCppProvider
import sigil.signal.{AgentActivity, EventState}
import sigil.tool.core.{CoreTools, FindCapabilityInput}
import sigil.tool.model.{ChangeModeInput, ResponseContent}
import spice.net.URL

/** Synthetic FullInformation subtype for the catalog-rendering test. */
case class TestInformation(id: Id[Information]) extends FullInformation derives RW

/**
 * Regression guard: every Model-visible Event in a `ConversationContext`
 * MUST appear somewhere in the wire payload that
 * [[LlamaCppProvider.requestConverter]] produces. The original Phase 4 bug
 * — the provider only rendering Messages, all as user role — would have
 * been caught by this test.
 *
 * Strategy: build a request whose events each carry a unique marker
 * substring, then assert each marker is present in the rendered request
 * body. UI-only events (those without `EventVisibility.Model`) MUST NOT
 * leak into the payload.
 */
class LlamaCppRequestCoverageSpec extends AnyWordSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val provider = LlamaCppProvider(URL.parse("http://localhost:8081"), Nil)
  private val modelId: Id[Model] = Model.id("test", "model")
  private val conversationId = Conversation.id("coverage-conv")

  private def baseRequest(context: ConversationContext = ConversationContext()): ProviderRequest = ProviderRequest(
    conversationId = conversationId,
    modelId = modelId,
    instructions = Instructions(),
    context = context,
    currentMode = Mode.Conversation,
    generationSettings = GenerationSettings(maxOutputTokens = Some(50), temperature = Some(0.0)),
    tools = CoreTools(TestSigil).all,
    chain = List(TestUser, TestAgent)
  )

  private def bodyOf(events: Vector[Event] = Vector.empty,
                     context: ConversationContext = ConversationContext()): String = {
    val ctx = if (events.isEmpty) context else context.copy(events = events)
    provider.requestConverter(baseRequest(ctx)).content match {
      case Some(c: spice.http.content.StringContent) => c.value
      case _ => ""
    }
  }

  "LlamaCppProvider.requestConverter" should {
    "include user Message text in the wire payload" in {
      val marker = "USER_MSG_MARKER_42"
      val body = bodyOf(Vector(Message(
        participantId = TestUser,
        conversationId = conversationId,
        content = Vector(ResponseContent.Text(marker))
      )))
      body should include(marker)
    }

    "include agent Message text in the wire payload" in {
      val marker = "AGENT_MSG_MARKER_42"
      val body = bodyOf(Vector(Message(
        participantId = TestAgent,
        conversationId = conversationId,
        content = Vector(ResponseContent.Text(marker))
      )))
      body should include(marker)
    }

    "include a non-respond ToolInvoke (tool name + arguments) in the wire payload" in {
      val invoke = ToolInvoke(
        toolName = "change_mode",
        participantId = TestAgent,
        conversationId = conversationId,
        input = Some(ChangeModeInput(mode = Mode.Coding, reason = Some("REASON_MARKER_42")))
      )
      val body = bodyOf(Vector(invoke))
      body should include("change_mode")
      body should include("REASON_MARKER_42")
    }

    "include ModeChange after its triggering ToolInvoke (paired tool result)" in {
      val invoke = ToolInvoke(
        toolName = "change_mode",
        participantId = TestAgent,
        conversationId = conversationId,
        input = Some(ChangeModeInput(mode = Mode.Coding))
      )
      val mc = ModeChange(
        mode = Mode.Coding,
        participantId = TestAgent,
        conversationId = conversationId
      )
      val body = bodyOf(Vector(invoke, mc))
      body should include("Coding")
      // The tool_call_id pairing references the ToolInvoke's id
      body should include(invoke._id.value)
    }

    "include ToolResults schemas in the wire payload" in {
      val invoke = ToolInvoke(
        toolName = "find_capability",
        participantId = TestAgent,
        conversationId = conversationId,
        input = Some(FindCapabilityInput("anything"))
      )
      val results = ToolResults(
        schemas = CoreTools(TestSigil).all.map(_.schema).toList.take(1),
        participantId = TestAgent,
        conversationId = conversationId
      )
      val body = bodyOf(Vector(invoke, results))
      body should include(invoke._id.value)
      results.schemas.foreach(s => body should include(s.name))
    }

    "include TitleChange title in the wire payload (Model-visible by default)" in {
      val invoke = ToolInvoke(
        toolName = "set_title",
        participantId = TestAgent,
        conversationId = conversationId
      )
      val tc = TitleChange(
        title = "TITLE_MARKER_42",
        participantId = TestAgent,
        conversationId = conversationId
      )
      val body = bodyOf(Vector(invoke, tc))
      body should include("TITLE_MARKER_42")
    }

    "drop AgentState lifecycle markers from the wire payload (UI-only)" in {
      val agentStateMarker = "AGENTSTATE_MARKER_42"
      val agentState = AgentState(
        agentId = TestAgent,
        participantId = TestAgent,
        conversationId = conversationId,
        activity = AgentActivity.Thinking,
        state = EventState.Active
      )
      // Sanity: AgentState carries no marker text, but its presence in the
      // payload would still be detectable. We use the agentId as a marker;
      // for the AgentState NOT to leak, its agentId field shouldn't appear
      // when AgentState is the only event.
      val body = bodyOf(Vector(agentState))
      // System mode preamble + empty messages array — agentId shouldn't show
      body should not include agentStateMarker
      body should not include "AgentState"
    }

    "drop Events whose visibility excludes Model" in {
      val invisibleMarker = "INVISIBLE_MARKER_42"
      val invisibleMessage = Message(
        participantId = TestUser,
        conversationId = conversationId,
        content = Vector(ResponseContent.Text(invisibleMarker)),
        visibility = Set.empty  // explicitly NOT Model-visible
      )
      val body = bodyOf(Vector(invisibleMessage))
      body should not include invisibleMarker
    }

    "include criticalMemories in the wire payload" in {
      val ctx = ConversationContext(criticalMemories = Vector(
        ContextMemory(key = "CRITKEY_42", fact = "CRITFACT_42", source = MemorySource.Critical)
      ))
      val body = bodyOf(context = ctx)
      body should include("CRITKEY_42")
      body should include("CRITFACT_42")
    }

    "include memories in the wire payload" in {
      val ctx = ConversationContext(memories = Vector(
        ContextMemory(key = "MEMKEY_42", fact = "MEMFACT_42", source = MemorySource.Explicit)
      ))
      val body = bodyOf(context = ctx)
      body should include("MEMKEY_42")
      body should include("MEMFACT_42")
    }

    "include summaries in the wire payload" in {
      val ctx = ConversationContext(summaries = Vector(
        ContextSummary(text = "SUMMARY_TEXT_42", tokenEstimate = 7)
      ))
      val body = bodyOf(context = ctx)
      body should include("SUMMARY_TEXT_42")
    }

    "include information catalog entries in the wire payload" in {
      val infoId = Id[Information]("info-marker-42")
      val ctx = ConversationContext(information = Vector(
        Information(
          id = infoId,
          informationType = FullInformation.name.of[TestInformation],
          summary = "INFO_SUMMARY_42"
        )
      ))
      val body = bodyOf(context = ctx)
      body should include("info-marker-42")
      body should include("INFO_SUMMARY_42")
      body should include("TestInformation")
    }

    "include per-participant active skills in the wire payload" in {
      val ctx = ConversationContext().updateParticipant(TestAgent)(
        _.copy(activeSkills = Map(SkillSource.Mode -> ActiveSkillSlot(
          name = "SKILL_NAME_42",
          content = "SKILL_CONTENT_42"
        )))
      )
      val body = bodyOf(context = ctx)
      body should include("SKILL_NAME_42")
      body should include("SKILL_CONTENT_42")
    }

    "include per-participant recentTools in the wire payload" in {
      val ctx = ConversationContext().updateParticipant(TestAgent)(
        _.copy(recentTools = List("RECENT_TOOL_42"))
      )
      val body = bodyOf(context = ctx)
      body should include("RECENT_TOOL_42")
    }

    "include per-participant suggestedTools in the wire payload" in {
      val ctx = ConversationContext().updateParticipant(TestAgent)(
        _.copy(suggestedTools = List("SUGGESTED_TOOL_42"))
      )
      val body = bodyOf(context = ctx)
      body should include("SUGGESTED_TOOL_42")
    }

    "include conversation-wide extraContext in the wire payload" in {
      val ctx = ConversationContext(extraContext = Map(
        ContextKey("CONV_EXTRA_KEY_42") -> "CONV_EXTRA_VAL_42"
      ))
      val body = bodyOf(context = ctx)
      body should include("CONV_EXTRA_KEY_42")
      body should include("CONV_EXTRA_VAL_42")
    }

    "include per-participant extraContext in the wire payload" in {
      val ctx = ConversationContext().updateParticipant(TestAgent)(
        _.copy(extraContext = Map(ContextKey("PART_EXTRA_KEY_42") -> "PART_EXTRA_VAL_42"))
      )
      val body = bodyOf(context = ctx)
      body should include("PART_EXTRA_KEY_42")
      body should include("PART_EXTRA_VAL_42")
    }
  }
}
