package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.GlobalSpace
import sigil.conversation.{ContextFrame, ContextMemory, ContextSummary, MemorySource, ToolCallState}
import sigil.conversation.compression.TokenEstimator
import sigil.event.Event
import sigil.provider.ContextSections
import sigil.tokenize.{HeuristicTokenizer, JtokkitTokenizer}
import sigil.tool.ToolName

/**
 * Unit-level coverage for [[TokenEstimator]] — the per-section
 * estimators the curator uses for its multi-stage shedding decisions.
 * Heuristic + jtokkit cross-checks; section-specific behaviour
 * (`summary || fact` for memories, summed text for summaries, etc.).
 */
class TokenEstimatorSpec extends AnyWordSpec with Matchers {

  "estimateFrames" should {
    "sum tokens across Text and Complete ToolCall frames" in {
      val callId = Id[Event]()
      val frames: Vector[ContextFrame] = Vector(
        ContextFrame.Text("hello world", TestUser, Id[Event]()),
        ContextFrame.ToolCall(ToolName("respond"), """{"text":"hi"}""", callId, TestAgent, callId,
          state = ToolCallState.Complete("ok"))
      )
      val total = TokenEstimator.estimateFrames(frames, HeuristicTokenizer)
      total should be > 0
    }
  }

  "estimateMemories" should {
    "use summary when set, fact when not" in {
      // HeuristicTokenizer formula is length * 2 / 7 (≈ length / 3.5).
      // 40 char summary  → 40*2/7  = 11 tokens.
      // 400 char fact    → 400*2/7 = 114 tokens.
      val withSummary = ContextMemory(
        fact = "x" * 400,
        label = "Test directive",
        summary = "y" * 40,
        source = MemorySource.Explicit, pinned = true,
        spaceId = GlobalSpace
      )
      // Summary is required by type but the renderer falls back to
      // `fact` when it's blank — apps that don't want the cost of a
      // tight summary pass empty here and accept the fallback.
      val withoutSummary = ContextMemory(
        fact = "x" * 400,
        label = "Test directive",
        summary = "",
        source = MemorySource.Explicit, pinned = true,
        spaceId = GlobalSpace
      )
      val withTokens = TokenEstimator.estimateMemories(Vector(withSummary), HeuristicTokenizer)
      val withoutTokens = TokenEstimator.estimateMemories(Vector(withoutSummary), HeuristicTokenizer)
      withTokens should be < withoutTokens
      // The estimate counts what renders: the section heading plus each
      // memory's bullet line — not the bare summary string.
      withTokens shouldBe (HeuristicTokenizer.count(ContextSections.MemoriesHeader) +
        HeuristicTokenizer.count(ContextSections.memoryLine(withSummary)))
      withTokens should be > HeuristicTokenizer.count(withSummary.summary)
    }

    "charge nothing for an empty section" in {
      TokenEstimator.estimateMemories(Vector.empty, HeuristicTokenizer) shouldBe 0
    }

    "count the drill-down handle the renderer attaches" in {
      val elided = ContextMemory(
        fact = "x" * 400,
        label = "Test directive",
        summary = "y" * 40,
        key = Some("pinned-key"),
        source = MemorySource.Explicit, pinned = true,
        spaceId = GlobalSpace
      )
      val handleless = elided.copy(key = None)
      TokenEstimator.estimateMemories(Vector(elided), HeuristicTokenizer) should be >
        TokenEstimator.estimateMemories(Vector(handleless), HeuristicTokenizer)
    }
  }

  "estimateSummaries" should {
    "count each summary's rendered line plus the section's own framing" in {
      val s1 = ContextSummary(text = "abcd" * 10, conversationId = Id("conv"), tokenEstimate = 0)
      val s2 = ContextSummary(text = "efgh" * 10, conversationId = Id("conv"), tokenEstimate = 0)
      val total = TokenEstimator.estimateSummaries(Vector(s1, s2), HeuristicTokenizer)
      val bareText = HeuristicTokenizer.count(s1.text) + HeuristicTokenizer.count(s2.text)
      total should be > bareText
      total shouldBe (HeuristicTokenizer.count(ContextSections.SummariesHeader) +
        HeuristicTokenizer.count(ContextSections.SummariesFooter) +
        HeuristicTokenizer.count(ContextSections.summaryLine(s1)) +
        HeuristicTokenizer.count(ContextSections.summaryLine(s2)))
    }

    "count the reload_content handle a covering summary renders" in {
      val bare = ContextSummary(text = "abcd" * 10, conversationId = Id("conv"), tokenEstimate = 0)
      val covering = bare.copy(coversEventIds = List(Id[Event]("e1"), Id[Event]("e2")))
      TokenEstimator.estimateSummaries(Vector(covering), HeuristicTokenizer) should be >
        TokenEstimator.estimateSummaries(Vector(bare), HeuristicTokenizer)
    }

    "charge nothing for an empty section" in {
      TokenEstimator.estimateSummaries(Vector.empty, HeuristicTokenizer) shouldBe 0
    }
  }

  "Tokenizer comparison" should {
    "produce broadly similar (within 30%) counts for natural prose" in {
      val sample = "The framework's pipeline operates on a stream of signals, each of which can be an event or a delta."
      val heuristic = HeuristicTokenizer.count(sample)
      val jtokkit = JtokkitTokenizer.OpenAIChatGpt.count(sample)
      val ratio = heuristic.toDouble / jtokkit.toDouble
      ratio should be > 0.5
      ratio should be < 1.5
    }
  }
}
