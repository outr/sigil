package spec

import lightdb.id.Id
import lightdb.time.Timestamp
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.GlobalSpace
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
        invokedAt = Timestamp(GoldenNow - 1000L * i)
      ))
    },
    extraContext = Map(ContextKey("focus") -> "the config sweep")
  )

  /** Fixed clock so the golden render's relative timestamps
    * ("2m ago") are deterministic. */
  private val GoldenNow: Long = 1_700_000_000_000L

  private val skills = (1 to 6).toVector.map(i =>
    ActiveSkillSlot(name = s"skill-$i", content = s"skill body $i"))

  private val turn = TurnInput(
    conversationId = convId,
    frames = Vector.empty,
    participantProjections = Map(TestAgent -> projection),
    extraContext = Map(ContextKey.BudgetWarning -> "pinned directives occupy 31% of the window"),
    alwaysOnSkills = skills
  )

  private val request = ConversationRequest(
    conversationId = convId,
    model = TestSigil.testModel(modelId),
    instructions = Instructions(),
    turnInput = turn,
    currentMode = ConversationMode,
    currentTopic = TestTopicEntry,
    previousTopics = (1 to 7).toList.map(i =>
      TopicEntry(id = Topic.id(s"topic-$i"), label = s"topic $i", summary = s"summary $i")),
    generationSettings = GenerationSettings(maxOutputTokens = Some(50)),
    tools = CoreTools.all,
    chain = List(TestUser, TestAgent)
  )

  private def memory(i: Int): ContextMemory = ContextMemory(
    fact = s"fact $i",
    label = s"memory $i",
    summary = s"memory summary $i",
    source = MemorySource.Compression,
    spaceId = GlobalSpace,
    _id = ContextMemory.id(s"mem-$i")
  )

  private def summary(i: Int): ContextSummary = ContextSummary(
    text = s"earlier turn summary $i",
    conversationId = convId,
    tokenEstimate = 12,
    coversEventIds = List(Id[sigil.event.Event](s"covered-$i")),
    _id = ContextSummary.id(s"sum-$i")
  )

  private val resolved = ResolvedReferences(
    criticalMemories = (1 to 3).toVector.map(i => memory(100 + i).copy(source = MemorySource.Explicit)),
    memories = (1 to 6).toVector.map(memory),
    summaries = (1 to 6).toVector.map(summary)
  )

  private def ctxFor(shape: PromptShape) =
    SectionContext(request, resolved, discoveredCapabilitiesPromptCap = 25, now = GoldenNow, promptShape = shape)

  private def render(shape: PromptShape, placement: Placement): String =
    ContextSections.render(ContextSections.all, placement, ctxFor(shape))

  private def fullRender: String =
    render(PromptShape.Full, Placement.StablePrefix) + render(PromptShape.Full, Placement.VolatileTail)

  "PromptShape.Full" should {
    "cap nothing" in {
      val ctx = ctxFor(PromptShape.Full)
      ctx.suggestedTools.size shouldBe suggested.count(n =>
        CoreTools.all.exists(_.schema.name.value == n))
      ctx.duplicateGroups.size shouldBe 7
      ctx.allSkills.size shouldBe skills.size
      ctx.recentTools.size shouldBe 7
    }

    "render byte-for-byte what the committed golden file records" in {
      // The default rendering is a wire contract: any byte drift in a
      // section's text, order, or spacing changes every prompt every
      // consumer sends. When a change to the rendering is intentional,
      // re-record with SIGIL_REGEN_GOLDEN=1 and review the diff — never
      // edit the resource to make this pass.
      val rendered = fullRender
      if (sys.env.get("SIGIL_REGEN_GOLDEN").contains("1")) {
        val target = List(
          java.nio.file.Paths.get("core/src/test/resources/golden/full-prompt-render.txt"),
          java.nio.file.Paths.get("src/test/resources/golden/full-prompt-render.txt")
        ).find(p => java.nio.file.Files.exists(p.getParent)).getOrElse(
          fail("no golden resource directory found relative to the test's working directory"))
        java.nio.file.Files.writeString(target, rendered)
        info(s"re-recorded golden render at ${target.toAbsolutePath}")
        succeed
      } else {
        val stream = getClass.getResourceAsStream("/golden/full-prompt-render.txt")
        withClue("missing test resource /golden/full-prompt-render.txt: ") { stream should not be null }
        val expected = try new String(stream.readAllBytes(), "UTF-8") finally stream.close()
        rendered shouldBe expected
      }
    }
  }

  "PromptShape.Compact" should {
    "cap suggested tools and repeated-call groups to the shape's entry cap" in {
      val ctx = ctxFor(PromptShape.Compact)
      val cap = PromptShape.Compact.entryCap.get
      ctx.suggestedTools.size should be <= cap
      ctx.duplicateGroups.size shouldBe cap
    }

    "cap the sections that actually dominate a long turn" in {
      val ctx = ctxFor(PromptShape.Compact)
      ctx.allSkills.size shouldBe PromptShape.Compact.skillCap.get
      ctx.recentTools.size shouldBe PromptShape.Compact.entryCap.get
      val tail = render(PromptShape.Compact, Placement.VolatileTail)
      tail.linesIterator.count(_.startsWith("- memory summary")) shouldBe PromptShape.Compact.memoryCap.get
      tail.linesIterator.count(_.startsWith("earlier turn summary")) shouldBe PromptShape.Compact.summaryCap.get
    }

    "cap previous topics in the stable prefix" in {
      val prefix = render(PromptShape.Compact, Placement.StablePrefix)
      prefix.linesIterator.count(_.trim.startsWith("- \"topic ")) shouldBe PromptShape.Compact.entryCap.get
    }

    "never drop a pinned directive" in {
      val prefix = render(PromptShape.Compact, Placement.StablePrefix)
      resolved.criticalMemories.foreach(m => prefix should include (m.summary))
      succeed
    }

    "render fewer bytes than Full across the whole prompt" in {
      val compact = render(PromptShape.Compact, Placement.StablePrefix) +
        render(PromptShape.Compact, Placement.VolatileTail)
      compact.length should be < fullRender.length
    }

    "keep the same section headers it caps entries within" in {
      val compact = render(PromptShape.Compact, Placement.VolatileTail)
      compact should include ("== Suggested tools ==")
      compact should include ("== Repeated tool calls ==")
      compact should include ("== Memories ==")
      compact should include ("== Earlier in this conversation (summarized) ==")
    }

    "keep directive framing prefixes identical" in {
      val compact = render(PromptShape.Compact, Placement.VolatileTail)
      val full = render(PromptShape.Full, Placement.VolatileTail)
      val framing = "Identical inputs yield identical results"
      compact.contains(framing) shouldBe full.contains(framing)
    }
  }
}
