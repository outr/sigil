package spec

import lightdb.id.Id
import lightdb.time.Timestamp
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.conversation.*
import sigil.db.Model
import sigil.provider.*
import sigil.tool.ToolName
import sigil.tool.core.CoreTools

/**
 * `PromptShape.Compact` renders the SAME section list as `Full` with
 * per-section entry caps — a small model's window is better spent on
 * the task than on a long digest of its own history. `Full` must stay
 * byte-for-byte what the framework rendered before profiles existed.
 */
class CompactPromptShapeSpec extends AnyWordSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val convId = Conversation.id("compact-shape-conv")
  private val modelId = Model.id("anthropic", "claude-haiku-4-5")

  private val suggested = List("grep", "respond", "no_response", "stop", "find_capability",
    "respond_options", "change_mode")

  private val projection = ParticipantProjection.empty(TestAgent, convId).copy(
    suggestedTools = suggested.map(s => ToolName.parse(s).fold(sys.error, identity)),
    recentToolInvocations = (1 to 7).toList.flatMap { i =>
      // Each name invoked twice with identical args → a duplicate group.
      List.fill(2)(RecentToolInvocation(
        toolName = ToolName.parse(s"dup_tool_$i").fold(sys.error, identity),
        argsHash = s"hash-$i",
        argsPreview = s"""{"i":$i}""",
        invokedAt = Timestamp(System.currentTimeMillis() - 1000L * i)
      ))
    }
  )

  private val turn = TurnInput(
    conversationId = convId,
    frames = Vector.empty,
    participantProjections = Map(TestAgent -> projection)
  )

  private val request = ConversationRequest(
    conversationId = convId,
    model = TestSigil.testModel(modelId),
    instructions = Instructions(),
    turnInput = turn,
    currentMode = ConversationMode,
    currentTopic = TestTopicEntry,
    generationSettings = GenerationSettings(maxOutputTokens = Some(50)),
    tools = CoreTools.all,
    chain = List(TestUser, TestAgent)
  )

  private val resolved = ResolvedReferences(Vector.empty, Vector.empty, Vector.empty)

  private def ctxFor(shape: PromptShape) =
    SectionContext(request, resolved, discoveredCapabilitiesPromptCap = 25, promptShape = shape)

  private def render(shape: PromptShape, placement: Placement): String =
    ContextSections.render(ContextSections.all, placement, ctxFor(shape))

  "PromptShape.Full" should {
    "cap nothing" in {
      val ctx = ctxFor(PromptShape.Full)
      ctx.suggestedTools.size shouldBe suggested.count(n =>
        CoreTools.all.exists(_.schema.name.value == n))
      ctx.duplicateGroups.size shouldBe 7
    }
  }

  "PromptShape.Compact" should {
    "cap suggested tools and repeated-call groups to the shape's entry cap" in {
      val ctx = ctxFor(PromptShape.Compact)
      val cap = PromptShape.Compact.entryCap.get
      ctx.suggestedTools.size should be <= cap
      ctx.duplicateGroups.size shouldBe cap
    }

    "render fewer bytes than Full while keeping the same section headers" in {
      val full = render(PromptShape.Full, Placement.VolatileTail)
      val compact = render(PromptShape.Compact, Placement.VolatileTail)
      compact.length should be < full.length
      compact should include ("== Suggested tools ==")
      compact should include ("== Repeated tool calls ==")
    }

    "leave the stable prefix untouched" in {
      render(PromptShape.Compact, Placement.StablePrefix) shouldBe
        render(PromptShape.Full, Placement.StablePrefix)
    }

    "keep directive framing prefixes identical" in {
      val compact = render(PromptShape.Compact, Placement.VolatileTail)
      val full = render(PromptShape.Full, Placement.VolatileTail)
      val framing = "Identical inputs yield identical results"
      compact.contains(framing) shouldBe full.contains(framing)
    }
  }
}
