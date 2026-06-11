package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.conversation.Conversation
import sigil.event.Message
import sigil.provider.TokenUsage
import sigil.signal.MessageDelta

/**
 * Sigil #381 — an agent turn makes one provider call per loop iteration;
 * the tool-calling iterations' usage all lands on the same
 * `lastUserVisibleMessageId`, so `MessageDelta.apply` must ACCUMULATE it,
 * not replace. Replacing billed the whole multi-call turn at only its
 * last call's tokens (conversation cost undercounted ~40×). Mid-stream
 * ESTIMATE usage (`isEstimated`, emitted by the OpenAI-compat wire for
 * live UI tickers) must NOT touch the persisted total.
 */
class MessageDeltaUsageAccumulationSpec extends AnyWordSpec with Matchers {

  private val convId = Conversation.id("usage-accum")

  private def msg(usage: TokenUsage = TokenUsage.zero): Message =
    Message(participantId = TestAgent, conversationId = convId, topicId = TestTopicId, usage = usage)

  private def applyUsage(m: Message, u: TokenUsage): Message =
    MessageDelta(target = m._id, conversationId = convId, usage = Some(u)).apply(m).asInstanceOf[Message]

  "MessageDelta usage (sigil #381)" should {

    "accumulate authoritative per-call usage instead of replacing it" in {
      var m = msg()
      m = applyUsage(m, TokenUsage(100, 10, 110, cacheCreationTokens = 90))
      m = applyUsage(m, TokenUsage(200, 20, 220, cacheCreationTokens = 180))
      m = applyUsage(m, TokenUsage(50, 5, 55, cacheReadTokens = 40))
      m.usage.promptTokens shouldBe 350
      m.usage.completionTokens shouldBe 35
      m.usage.totalTokens shouldBe 385
      m.usage.cacheCreationTokens shouldBe 270
      m.usage.cacheReadTokens shouldBe 40
    }

    "ignore mid-stream estimate usage (wire-only, never persisted)" in {
      var m = msg()
      m = applyUsage(m, TokenUsage(999, 999, 999, isEstimated = true)) // estimate → ignored
      m.usage shouldBe TokenUsage.zero
      m = applyUsage(m, TokenUsage(100, 10, 110))                      // authoritative → accumulates
      m.usage.promptTokens shouldBe 100
    }
  }

  "TokenUsage.+ (sigil #381)" should {
    "sum every field and yield an authoritative result" in {
      val sum = TokenUsage(1, 2, 3, isEstimated = true, 4, 5) + TokenUsage(10, 20, 30, isEstimated = true, 40, 50)
      sum shouldBe TokenUsage(11, 22, 33, isEstimated = false, 44, 55)
    }
  }
}
