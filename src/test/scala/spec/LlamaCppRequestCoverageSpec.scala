package spec

import fabric.rw.*
import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.conversation.{
  ActiveSkillSlot,
  ContextFrame,
  ContextKey,
  ContextMemory,
  ContextSummary,
  Conversation,
  ConversationView,
  MemorySource,
  MemorySpaceId,
  ParticipantProjection,
  SkillSource,
  TurnInput
}
import sigil.db.Model
import sigil.event.Event
import sigil.information.{Information, InformationSummary}
import sigil.provider.{GenerationSettings, Instructions, Mode, ProviderRequest}
import sigil.provider.llamacpp.LlamaCppProvider
import sigil.tool.ToolName
import sigil.tool.core.CoreTools
import spice.net.URL

/**
 * Synthetic Information subtype for the catalog-rendering test.
 */
case class TestInformation(id: Id[Information]) extends Information derives RW

/**
 * Synthetic MemorySpaceId for the memory-coverage tests.
 */
case object TestSpace extends MemorySpaceId {
  override val value: String = "test-space"
}

/**
 * Regression guard: every populated field on `TurnInput` and
 * `ConversationView` (frames, projections, memory/summary/info ids) MUST
 * appear somewhere in the wire payload that
 * [[LlamaCppProvider.requestConverter]] produces.
 *
 * Strategy: build a request whose view frames / turn-input entries each
 * carry a unique marker substring, then assert each marker is present in
 * the rendered request body. Frame mapping from events is covered by
 * `FrameBuilderSpec`; this spec exercises only the view-and-turn-input →
 * wire rendering.
 */
class LlamaCppRequestCoverageSpec extends AnyWordSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val provider = LlamaCppProvider(TestSigil.llamaCppHost, Nil, TestSigil)
  private val modelId: Id[Model] = Model.id("test", "model")
  private val conversationId = Conversation.id("coverage-conv")

  private def emptyView: ConversationView = ConversationView(
    conversationId = conversationId,
    _id = ConversationView.idFor(conversationId)
  )

  private def baseRequest(input: TurnInput,
                          generationSettings: GenerationSettings =
                            GenerationSettings(maxOutputTokens = Some(50), temperature = Some(0.0))): ProviderRequest = ProviderRequest(
    conversationId = conversationId,
    modelId = modelId,
    instructions = Instructions(),
    turnInput = input,
    currentMode = Mode.Conversation,
    currentTopic = TestTopicEntry,
    generationSettings = generationSettings,
    tools = CoreTools.all,
    chain = List(TestUser, TestAgent)
  )

  private def bodyOf(input: TurnInput,
                     generationSettings: GenerationSettings =
                       GenerationSettings(maxOutputTokens = Some(50), temperature = Some(0.0))): String =
    provider.requestConverter(baseRequest(input, generationSettings)).sync().content match {
      case Some(c: spice.http.content.StringContent) => c.value
      case _ => ""
    }

  /**
   * Upsert a memory into `db.memories` and return its id.
   */
  private def upsertMemory(fact: String, source: MemorySource): Id[ContextMemory] = {
    val memory = ContextMemory(fact = fact, source = source, spaceId = TestSpace)
    TestSigil.withDB(_.memories.transaction(_.upsert(memory))).sync()
    memory._id
  }

  /**
   * Upsert a summary into `db.summaries` and return its id.
   */
  private def upsertSummary(text: String): Id[ContextSummary] = {
    val summary = ContextSummary(text = text, conversationId = conversationId, tokenEstimate = 7)
    TestSigil.withDB(_.summaries.transaction(_.upsert(summary))).sync()
    summary._id
  }

  private def syntheticEventId: Id[Event] = Id(rapid.Unique())

  "LlamaCppProvider.requestConverter" should {
    "render a Text frame from a user as a user-role wire message" in {
      val marker = "USER_TEXT_MARKER_42"
      val view = emptyView.copy(frames = Vector(
        ContextFrame.Text(content = marker, participantId = TestUser, sourceEventId = syntheticEventId)
      ))
      val body = bodyOf(TurnInput(view))
      body should include(marker)
    }

    "render a Text frame from the agent as an assistant-role wire message" in {
      val marker = "AGENT_TEXT_MARKER_42"
      val view = emptyView.copy(frames = Vector(
        ContextFrame.Text(content = marker, participantId = TestAgent, sourceEventId = syntheticEventId)
      ))
      val body = bodyOf(TurnInput(view))
      body should include(marker)
    }

    "render a non-respond ToolCall frame (tool name + arguments) on the wire" in {
      val callId = syntheticEventId
      val view = emptyView.copy(frames = Vector(
        ContextFrame.ToolCall(
          toolName = ToolName("change_mode"),
          argsJson = "{\"reason\":\"REASON_MARKER_42\"}",
          callId = callId,
          participantId = TestAgent,
          sourceEventId = callId
        )
      ))
      val body = bodyOf(TurnInput(view))
      body should include("change_mode")
      body should include("REASON_MARKER_42")
    }

    "pair a ToolResult frame back to its ToolCall via tool_call_id" in {
      val callId = syntheticEventId
      val view = emptyView.copy(frames = Vector(
        ContextFrame.ToolCall(
          toolName = ToolName("change_mode"),
          argsJson = "{}",
          callId = callId,
          participantId = TestAgent,
          sourceEventId = callId
        ),
        ContextFrame.ToolResult(
          callId = callId,
          content = "Mode changed to Coding.",
          sourceEventId = syntheticEventId
        )
      ))
      val body = bodyOf(TurnInput(view))
      body should include("Coding")
      body should include(callId.value)
    }

    "render a System frame (e.g. title change) on the wire" in {
      val marker = "TITLE_MARKER_42"
      val view = emptyView.copy(frames = Vector(
        ContextFrame.System(content = s"Title changed to: $marker", sourceEventId = syntheticEventId)
      ))
      val body = bodyOf(TurnInput(view))
      body should include(marker)
    }

    "include criticalMemories in the wire payload (resolved from db.memories)" in {
      val memId = upsertMemory("CRITFACT_42", MemorySource.Critical)
      val input = TurnInput(emptyView, criticalMemories = Vector(memId))
      val body = bodyOf(input)
      body should include("CRITFACT_42")
    }

    "include memories in the wire payload (resolved from db.memories)" in {
      val memId = upsertMemory("MEMFACT_42", MemorySource.Explicit)
      val input = TurnInput(emptyView, memories = Vector(memId))
      val body = bodyOf(input)
      body should include("MEMFACT_42")
    }

    "include summaries in the wire payload (resolved from db.summaries)" in {
      val summaryId = upsertSummary("SUMMARY_TEXT_42")
      val input = TurnInput(emptyView, summaries = Vector(summaryId))
      val body = bodyOf(input)
      body should include("SUMMARY_TEXT_42")
    }

    "include information catalog entries in the wire payload" in {
      val infoId = Id[Information]("info-marker-42")
      val input = TurnInput(
        emptyView,
        information = Vector(
          InformationSummary(
            id = infoId,
            informationType = Information.name.of[TestInformation],
            summary = "INFO_SUMMARY_42"
          )
        ))
      val body = bodyOf(input)
      body should include("info-marker-42")
      body should include("INFO_SUMMARY_42")
      body should include("TestInformation")
    }

    "include per-participant active skills (from view projections) in the wire payload" in {
      val view = emptyView.updateParticipant(TestAgent)(_.copy(
        activeSkills = Map(SkillSource.Mode -> ActiveSkillSlot(
          name = "SKILL_NAME_42",
          content =
            "SKILL_CONTENT_42"
        ))
      ))
      val body = bodyOf(TurnInput(view))
      body should include("SKILL_NAME_42")
      body should include("SKILL_CONTENT_42")
    }

    "include per-participant recentTools (from view projections) in the wire payload" in {
      val view = emptyView.updateParticipant(TestAgent)(_.copy(recentTools = List(ToolName("RECENT_TOOL_42"))))
      val body = bodyOf(TurnInput(view))
      body should include("RECENT_TOOL_42")
    }

    "include per-participant suggestedTools (from view projections) in the wire payload" in {
      val view = emptyView.updateParticipant(TestAgent)(_.copy(suggestedTools = List(ToolName("SUGGESTED_TOOL_42"))))
      val body = bodyOf(TurnInput(view))
      body should include("SUGGESTED_TOOL_42")
    }

    "include conversation-wide extraContext (from turn input) in the wire payload" in {
      val input = TurnInput(
        emptyView,
        extraContext = Map(
          ContextKey("CONV_EXTRA_KEY_42") -> "CONV_EXTRA_VAL_42"
        ))
      val body = bodyOf(input)
      body should include("CONV_EXTRA_KEY_42")
      body should include("CONV_EXTRA_VAL_42")
    }

    "include per-participant extraContext (from view projections) in the wire payload" in {
      val view = emptyView.updateParticipant(TestAgent)(_.copy(
        extraContext = Map(ContextKey("PART_EXTRA_KEY_42") -> "PART_EXTRA_VAL_42")
      ))
      val body = bodyOf(TurnInput(view))
      body should include("PART_EXTRA_KEY_42")
      body should include("PART_EXTRA_VAL_42")
    }

    "forward topP from GenerationSettings as top_p on the wire" in {
      val body = bodyOf(
        TurnInput(emptyView),
        GenerationSettings(maxOutputTokens = Some(50), temperature = Some(0.0), topP = Some(0.42))
      )
      body should include("\"top_p\":0.42")
    }

    "forward stopSequences from GenerationSettings as stop on the wire" in {
      val body = bodyOf(
        TurnInput(emptyView),
        GenerationSettings(
          maxOutputTokens = Some(50),
          temperature = Some(0.0),
          stopSequences = Vector("STOP_MARKER_A", "STOP_MARKER_B")
        )
      )
      body should include("STOP_MARKER_A")
      body should include("STOP_MARKER_B")
    }
  }
}
