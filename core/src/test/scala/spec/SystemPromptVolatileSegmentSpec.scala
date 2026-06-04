package spec

import fabric.*
import lightdb.id.Id
import lightdb.time.Timestamp
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.conversation.{Conversation, ContextFrame, ParticipantProjection, RecentToolInvocation, TurnInput}
import sigil.db.Model
import sigil.event.Event
import sigil.provider.{ConversationMode, ConversationRequest, GenerationSettings, Instructions, ProviderRequest}
import sigil.provider.anthropic.AnthropicProvider
import sigil.tool.ToolName
import sigil.tool.core.CoreTools

/**
 * Sigil #302 — verifies the system prompt is split into a stable
 * cacheable segment + a volatile per-turn segment, and that the
 * Anthropic provider emits them as two `system` array entries with
 * `cache_control` on the stable segment only. Pre-fix the volatile
 * content (Recently used / Suggested tools / Repeated tool calls /
 * etc.) was appended to the same string the `cache_control` marker
 * sat on, so every turn's churn invalidated the cached prefix and
 * Shopkeeper was paying the 1.25× cache-write multiplier on 85.8% of
 * input tokens.
 *
 * Two assertions:
 *
 *   1. When volatile content exists, the Anthropic wire shows TWO
 *      `system` segments — stable carries cache_control, volatile
 *      doesn't.
 *   2. The stable segment is BYTE-IDENTICAL across two turns of the
 *      same conversation where only the volatile-source state has
 *      changed (different `recentToolInvocations`,
 *      `suggestedTools`). This is the load-bearing property: a
 *      stable byte prefix is what the Anthropic prompt cache keys
 *      on.
 */
class SystemPromptVolatileSegmentSpec extends AnyWordSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val convId = Conversation.id("system-volatile-conv")
  private val modelId = Model.id("anthropic", "claude-haiku-4-5")

  /**
   * TurnInput seeded with a participant projection carrying volatile
   * sources. The values themselves are arbitrary — the test only
   * cares whether they leak into the cached prefix.
   */
  private def turnWith(suggested: List[String], recent: List[String]): TurnInput = {
    val proj = ParticipantProjection.empty(TestAgent, convId)
      .copy(
        suggestedTools = suggested.map(ToolName(_)),
        recentToolInvocations = recent.zipWithIndex.map { case (n, i) =>
          RecentToolInvocation(
            toolName = ToolName(n),
            argsHash = s"h-$n-$i",
            argsPreview = s"""{"x":$i}""",
            invokedAt = Timestamp(System.currentTimeMillis() - 1_000L * (i + 1))
          )
        }
      )
    TurnInput(
      conversationId = convId,
      frames = Vector(
        ContextFrame.Text("first user message", TestUser, Id[Event]("seed-1")),
        ContextFrame.Text("agent reply", TestAgent, Id[Event]("seed-2")),
        ContextFrame.Text("latest user message", TestUser, Id[Event]("seed-3"))
      ),
      participantProjections = Map(TestAgent -> proj)
    )
  }

  private def requestFor(t: TurnInput): ProviderRequest =
    ConversationRequest(
      conversationId = convId,
      model = TestSigil.testModel(modelId),
      instructions = Instructions(),
      turnInput = t,
      currentMode = ConversationMode,
      currentTopic = TestTopicEntry,
      generationSettings = GenerationSettings(maxOutputTokens = Some(50)),
      tools = CoreTools.all,
      chain = List(TestUser, TestAgent)
    )

  private def render(provider: AnthropicProvider, t: TurnInput): Json = {
    val httpReq = provider.requestConverter(requestFor(t)).sync()
    httpReq.content match {
      case Some(c: spice.http.content.StringContent) => fabric.io.JsonParser(c.value)
      case _ => obj()
    }
  }

  "AnthropicProvider system prompt segmentation (sigil #302)" should {
    val provider = AnthropicProvider(apiKey = "sk-ant-test", sigilRef = TestSigil)

    "split the system prompt into stable (cache_control) + volatile (no cache_control) segments" in {
      val t = turnWith(
        suggested = List("dispatch_workers", "grep"),
        recent = List("find_capability")
      )
      val body = render(provider, t)
      val systemArr = body("system").asVector
      // Two segments — stable + volatile.
      systemArr should have size 2
      val stable = systemArr(0)
      val volatile = systemArr(1)
      // Stable carries the 1-hour cache_control breakpoint.
      stable("cache_control") shouldBe obj("type" -> str("ephemeral"), "ttl" -> str("1h"))
      // Volatile has NO cache_control — its per-turn churn must not
      // invalidate the cached stable prefix.
      volatile.get("cache_control") shouldBe None
      // The volatile segment carries the projection-derived prompt
      // sections; the stable does not.
      volatile("text").asString should (
        include("Suggested tools") or
          include("Recently used tools")
      )
      stable("text").asString should not include "== Suggested tools =="
      stable("text").asString should not include "== Recently used tools =="
    }

    "preserve byte-identity of the stable segment across two turns whose volatile sources differ" in {
      val turnA = turnWith(
        suggested = List("dispatch_workers", "grep"),
        recent = List("find_capability")
      )
      val turnB = turnWith(
        suggested = List("lsp_find_references", "bsp_compile"), // DIFFERENT
        recent = List("respond_options", "find_capability") // DIFFERENT
      )
      val bodyA = render(provider, turnA)
      val bodyB = render(provider, turnB)
      val stableA = bodyA("system").asVector(0)("text").asString
      val stableB = bodyB("system").asVector(0)("text").asString
      // The cached prefix is the byte sequence Anthropic keys on for
      // its prompt cache. Differing volatile inputs MUST yield the
      // same stable text — that's the load-bearing claim of #302.
      stableA shouldBe stableB
      // And the volatile segments DO differ (sanity check — confirms
      // the test actually exercised the divergence).
      val volatileA = bodyA("system").asVector(1)("text").asString
      val volatileB = bodyB("system").asVector(1)("text").asString
      volatileA should not equal volatileB
    }
  }
}
