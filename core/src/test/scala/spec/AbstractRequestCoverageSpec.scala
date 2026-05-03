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
  SkillSource,
  TurnInput
}
import sigil.db.Model
import sigil.event.Event
import sigil.information.{Information, InformationSummary}
import sigil.provider.{ConversationRequest, GenerationSettings, Instructions, Mode, ConversationMode, Provider, ProviderRequest}
import sigil.tool.ToolName
import sigil.tool.core.CoreTools

/**
 * Synthetic Information subtype for the catalog-rendering test. Shared
 * across per-provider coverage specs so the poly name stays identical.
 */
case class TestInformation(id: Id[Information]) extends Information derives RW

/**
 * Regression guard shared across every [[Provider]] implementation:
 * every populated field on `TurnInput` and `ConversationView` (frames,
 * projections, memory/summary/info ids) MUST appear somewhere in the
 * wire payload that `requestConverter` produces.
 *
 * Strategy: build a request whose view frames / turn-input entries
 * each carry a unique marker substring, then assert each marker is
 * present in the rendered request body. Marker-based assertions are
 * provider-agnostic — any wire format that includes the field value
 * will contain the marker as a substring, regardless of JSON key
 * names.
 *
 * Concrete per-provider specs override [[providerInstance]] and
 * [[modelId]] to supply the provider + its target model id.
 * Provider-specific wire details (exact key names, encoding quirks)
 * live in that provider's own coverage spec alongside this abstract
 * suite.
 */
trait AbstractRequestCoverageSpec extends AnyWordSpec with Matchers {

  TestSigil.initFor(getClass.getSimpleName)

  /** The provider under test. */
  protected def providerInstance: Provider

  /** The model id to pass on the request. Providers don't care much —
    * the important thing is that it matches a registered model in
    * TestSigil's cache if the provider looks it up. */
  protected def modelId: Id[Model]

  protected val conversationId: Id[Conversation] = Conversation.id("coverage-conv")

  /** A baseline user frame — carried by [[emptyView]] so generated
    * wire bodies always have at least one input/message entry (real
    * provider APIs reject fully-empty message arrays). Tests that
    * override frames via `.copy(frames = ...)` replace this baseline. */
  protected val baselineFrame: ContextFrame.Text = ContextFrame.Text(
    content = "baseline user message",
    participantId = TestUser,
    sourceEventId = Id[Event]("baseline-seed")
  )

  protected def emptyView: ConversationView = ConversationView(
    conversationId = conversationId,
    frames = Vector(baselineFrame),
    _id = ConversationView.idFor(conversationId)
  )

  protected def defaultGenerationSettings: GenerationSettings =
    GenerationSettings(maxOutputTokens = Some(50), temperature = Some(0.0))

  protected def baseRequest(input: TurnInput,
                            generationSettings: GenerationSettings = defaultGenerationSettings): ProviderRequest =
    ConversationRequest(
      conversationId = conversationId,
      modelId = modelId,
      instructions = Instructions(),
      turnInput = input,
      currentMode = ConversationMode,
      currentTopic = TestTopicEntry,
      generationSettings = generationSettings,
      tools = CoreTools.all,
      chain = List(TestUser, TestAgent)
    )

  protected def bodyOf(input: TurnInput,
                       generationSettings: GenerationSettings = defaultGenerationSettings): String =
    providerInstance.requestConverter(baseRequest(input, generationSettings)).sync().content match {
      case Some(c: spice.http.content.StringContent) => c.value
      case _ => ""
    }

  private def upsertMemory(fact: String,
                           source: MemorySource,
                           pinned: Boolean = false): Id[ContextMemory] = {
    val memory = ContextMemory(fact = fact, source = source, pinned = pinned, spaceId = TestSpace)
    TestSigil.withDB(_.memories.transaction(_.upsert(memory))).sync()
    memory._id
  }

  private def upsertSummary(text: String): Id[ContextSummary] = {
    val summary = ContextSummary(text = text, conversationId = conversationId, tokenEstimate = 7)
    TestSigil.withDB(_.summaries.transaction(_.upsert(summary))).sync()
    summary._id
  }

  private def syntheticEventId: Id[Event] = Id(rapid.Unique())

  s"${getClass.getSimpleName}.requestConverter" should {
    "render a Text frame from a user in the wire body" in {
      val marker = "USER_TEXT_MARKER_42"
      val view = emptyView.copy(frames = Vector(
        ContextFrame.Text(content = marker, participantId = TestUser, sourceEventId = syntheticEventId)
      ))
      bodyOf(TurnInput(view)) should include(marker)
    }

    "render a Text frame from the agent in the wire body" in {
      val marker = "AGENT_TEXT_MARKER_42"
      val view = emptyView.copy(frames = Vector(
        ContextFrame.Text(content = marker, participantId = TestAgent, sourceEventId = syntheticEventId)
      ))
      bodyOf(TurnInput(view)) should include(marker)
    }

    "render a non-respond ToolCall frame (tool name + arguments) in the wire body" in {
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

    "pair a ToolResult frame back to its ToolCall via call id" in {
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

    "render a System frame (e.g. title change) in the wire body" in {
      val marker = "TITLE_MARKER_42"
      val view = emptyView.copy(frames = Vector(
        ContextFrame.System(content = s"Title changed to: $marker", sourceEventId = syntheticEventId)
      ))
      bodyOf(TurnInput(view)) should include(marker)
    }

    "include criticalMemories in the wire body (resolved from db.memories)" in {
      val memId = upsertMemory("CRITFACT_42", MemorySource.Explicit, pinned = true)
      bodyOf(TurnInput(emptyView, criticalMemories = Vector(memId))) should include("CRITFACT_42")
    }

    "include memories in the wire body (resolved from db.memories)" in {
      val memId = upsertMemory("MEMFACT_42", MemorySource.Explicit)
      bodyOf(TurnInput(emptyView, memories = Vector(memId))) should include("MEMFACT_42")
    }

    "include summaries in the wire body (resolved from db.summaries)" in {
      val summaryId = upsertSummary("SUMMARY_TEXT_42")
      bodyOf(TurnInput(emptyView, summaries = Vector(summaryId))) should include("SUMMARY_TEXT_42")
    }

    "include information catalog entries in the wire body" in {
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

    "include per-participant active skills (from view projections) in the wire body" in {
      val view = emptyView.updateParticipant(TestAgent)(_.copy(
        activeSkills = Map(SkillSource.Mode -> ActiveSkillSlot(
          name = "SKILL_NAME_42",
          content = "SKILL_CONTENT_42"
        ))
      ))
      val body = bodyOf(TurnInput(view))
      body should include("SKILL_NAME_42")
      body should include("SKILL_CONTENT_42")
    }

    "include per-participant recentTools (from view projections) in the wire body" in {
      val view = emptyView.updateParticipant(TestAgent)(_.copy(recentTools = List(ToolName("RECENT_TOOL_42"))))
      bodyOf(TurnInput(view)) should include("RECENT_TOOL_42")
    }

    "include per-participant suggestedTools (from view projections) in the wire body" in {
      val view = emptyView.updateParticipant(TestAgent)(_.copy(suggestedTools = List(ToolName("SUGGESTED_TOOL_42"))))
      bodyOf(TurnInput(view)) should include("SUGGESTED_TOOL_42")
    }

    "include conversation-wide extraContext (from turn input) in the wire body" in {
      val input = TurnInput(
        emptyView,
        extraContext = Map(ContextKey("CONV_EXTRA_KEY_42") -> "CONV_EXTRA_VAL_42")
      )
      val body = bodyOf(input)
      body should include("CONV_EXTRA_KEY_42")
      body should include("CONV_EXTRA_VAL_42")
    }

    "include per-participant extraContext (from view projections) in the wire body" in {
      val view = emptyView.updateParticipant(TestAgent)(_.copy(
        extraContext = Map(ContextKey("PART_EXTRA_KEY_42") -> "PART_EXTRA_VAL_42")
      ))
      val body = bodyOf(TurnInput(view))
      body should include("PART_EXTRA_KEY_42")
      body should include("PART_EXTRA_VAL_42")
    }

    // Bug #61 — Reasoning frames carry provider-internal state from a
    // prior turn (currently only OpenAI's Responses API uses this).
    // Non-originating providers MUST drop the entry. The OpenAI
    // coverage spec overrides this expectation (see
    // [[OpenAIRequestCoverageSpec.expectsReasoningSerialized]]).
    "drop Reasoning frames from the wire body by default (non-originating provider)" in {
      val view = emptyView.copy(frames = Vector(
        baselineFrame,
        ContextFrame.Reasoning(
          providerItemId = "rs_REASONING_ID_42",
          summary = List("REASONING_SUMMARY_42"),
          encryptedContent = Some("REASONING_ENCRYPTED_42"),
          participantId = TestAgent,
          sourceEventId = syntheticEventId,
          visibility = sigil.event.MessageVisibility.All
        )
      ))
      val body = bodyOf(TurnInput(view))
      if (expectsReasoningSerialized) {
        body should include("rs_REASONING_ID_42")
        body should include("REASONING_SUMMARY_42")
        body should include("REASONING_ENCRYPTED_42")
        body should include("\"type\":\"reasoning\"")
      } else {
        body shouldNot include("rs_REASONING_ID_42")
        body shouldNot include("REASONING_SUMMARY_42")
        body shouldNot include("REASONING_ENCRYPTED_42")
      }
    }
  }

  /** Override-hook for providers that DO serialize Reasoning frames.
    * Default `false` (every framework provider except OpenAI). */
  protected def expectsReasoningSerialized: Boolean = false
}
