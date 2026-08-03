package spec

import lightdb.time.Timestamp
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.GlobalSpace
import sigil.conversation.*
import sigil.db.Model
import sigil.diagnostics.ProfileSection
import sigil.provider.*
import sigil.tokenize.HeuristicTokenizer
import sigil.tool.core.CoreTools

/**
 * Per-section token budgets. A budget is section shaping, not shedding:
 * it bounds what one section may spend regardless of how much room the
 * turn has, trimming entries in priority order until the RENDERED
 * section fits. `None` — the default for every framework section and
 * both prompt shapes — must leave the render byte-for-byte unchanged.
 */
class ContextSectionBudgetSpec extends AnyWordSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val convId = Conversation.id("section-budget-conv")
  private val modelId = Model.id("anthropic", "claude-haiku-4-5")

  private def memory(i: Int): ContextMemory = ContextMemory(
    fact = s"fact $i",
    label = s"memory $i",
    summary = s"memory summary number $i, long enough to cost a measurable number of tokens",
    source = MemorySource.Compression,
    spaceId = GlobalSpace,
    _id = ContextMemory.id(s"budget-mem-$i")
  )

  private val resolved = ResolvedReferences(
    criticalMemories = Vector.empty,
    memories = (1 to 8).toVector.map(memory),
    summaries = Vector.empty
  )

  private val request = ConversationRequest(
    conversationId = convId,
    model = TestSigil.testModel(modelId),
    instructions = Instructions(),
    turnInput = TurnInput(conversationId = convId),
    currentMode = ConversationMode,
    currentTopic = TestTopicEntry,
    generationSettings = GenerationSettings(maxOutputTokens = Some(50)),
    tools = CoreTools.all,
    chain = List(TestUser, TestAgent)
  )

  private val ctx = SectionContext(request, resolved, discoveredCapabilitiesPromptCap = 25,
    now = Timestamp().value, promptShape = PromptShape.Full)

  private def memoriesSection: ContextSection =
    ContextSections.all.find(_.id == ProfileSection.Memories).getOrElse(fail("no memories section"))

  "A section with no budget" should {
    "render exactly what it rendered before budgets existed" in {
      val section = memoriesSection
      section.budget shouldBe None
      section.effectiveBudget(PromptShape.Full) shouldBe None
      section.effectiveBudget(PromptShape.Compact) shouldBe None
      section.rendered(ctx) shouldBe Some(
        ContextSections.MemoriesHeader + resolved.memories.map(ContextSections.memoryLine).mkString)
    }
  }

  "A budgeted entry section" should {
    "trim entries in priority order until the rendered section fits" in {
      val full = memoriesSection.rendered(ctx).getOrElse(fail("nothing rendered"))
      val budget = HeuristicTokenizer.count(full) / 2
      val budgeted = memoriesSection.copy(budget = Some(budget))
      val trimmed = budgeted.rendered(ctx).getOrElse(fail("nothing rendered"))

      HeuristicTokenizer.count(trimmed) should be <= budget
      // A prefix of the entries survived — highest-priority first, header intact.
      trimmed should startWith(ContextSections.MemoriesHeader)
      trimmed should include(ContextSections.memoryLine(resolved.memories.head))
      trimmed should not include ContextSections.memoryLine(resolved.memories.last)
      full should startWith(trimmed.stripSuffix("\n"))
      // Keeping one more entry would have blown the budget.
      val kept = trimmed.linesIterator.count(_.startsWith("- "))
      kept should be > 0
      HeuristicTokenizer.count(
        ContextSections.MemoriesHeader +
          resolved.memories.take(kept + 1).map(ContextSections.memoryLine).mkString) should be > budget
    }

    "drop the section entirely when not even one entry fits" in {
      memoriesSection.copy(budget = Some(1)).rendered(ctx) shouldBe None
    }

    "take the tighter of its own budget and the prompt shape's" in {
      memoriesSection.copy(budget = Some(40)).effectiveBudget(PromptShape.Compact) shouldBe Some(40)
      PromptShape.Full.budgetFor(ProfileSection.Memories) shouldBe None
      PromptShape.Compact.budgetFor(ProfileSection.Memories) shouldBe None
    }

    "compose with the prompt shape's entry cap — both apply" in {
      val compact = ctx.copy(promptShape = PromptShape.Compact)
      val capped = memoriesSection.rendered(compact).getOrElse(fail("nothing rendered"))
      capped.linesIterator.count(_.startsWith("- ")) shouldBe PromptShape.Compact.memoryCap.get
      val budget = HeuristicTokenizer.count(capped) / 2
      val both = memoriesSection.copy(budget = Some(budget)).rendered(compact).getOrElse(fail("nothing rendered"))
      HeuristicTokenizer.count(both) should be <= budget
      both.linesIterator.count(_.startsWith("- ")) should be < PromptShape.Compact.memoryCap.get
    }
  }

  "A blob section" should {
    "ignore its budget — truncating prose corrupts what it exists to say" in {
      val instructions = ContextSections.all
        .find(_.id == ProfileSection.ModeBlock).getOrElse(fail("no mode block section"))
      val unbudgeted = instructions.rendered(ctx)
      unbudgeted shouldBe defined
      instructions.copy(budget = Some(1)).rendered(ctx) shouldBe unbudgeted
    }
  }
}
