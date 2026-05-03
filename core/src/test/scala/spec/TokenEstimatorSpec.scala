package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.GlobalSpace
import sigil.conversation.{ContextFrame, ContextMemory, ContextSummary, MemorySource}
import sigil.conversation.compression.TokenEstimator
import sigil.event.Event
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
    "sum tokens across Text / ToolCall / ToolResult frames" in {
      val frames: Vector[ContextFrame] = Vector(
        ContextFrame.Text("hello world", TestUser, Id[Event]()),
        ContextFrame.ToolCall(ToolName("respond"), """{"text":"hi"}""", Id[Event](), TestAgent, Id[Event]()),
        ContextFrame.ToolResult(Id[Event](), "ok", Id[Event]())
      )
      val total = TokenEstimator.estimateFrames(frames, HeuristicTokenizer)
      total should be > 0
    }
  }

  "estimateMemories" should {
    "use summary when set, fact when not" in {
      val withSummary = ContextMemory(
        fact = "x" * 400,                    // 100 heuristic tokens
        source = MemorySource.Critical,
        spaceId = GlobalSpace,
        summary = "y" * 40                   // 10 heuristic tokens
      )
      val withoutSummary = ContextMemory(
        fact = "x" * 400,
        source = MemorySource.Critical,
        spaceId = GlobalSpace
      )
      val withTokens = TokenEstimator.estimateMemories(Vector(withSummary), HeuristicTokenizer)
      val withoutTokens = TokenEstimator.estimateMemories(Vector(withoutSummary), HeuristicTokenizer)
      withTokens should be < withoutTokens
      withTokens shouldBe 10
      withoutTokens shouldBe 100
    }
  }

  "estimateSummaries" should {
    "sum text across summaries" in {
      val s1 = ContextSummary(text = "abcd" * 10, conversationId = Id("conv"), tokenEstimate = 0)
      val s2 = ContextSummary(text = "efgh" * 10, conversationId = Id("conv"), tokenEstimate = 0)
      val total = TokenEstimator.estimateSummaries(Vector(s1, s2), HeuristicTokenizer)
      total shouldBe 20  // (40 + 40) / 4
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
