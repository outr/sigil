package spec

import fabric.*
import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.conversation.{ContextFrame, TurnInput}
import sigil.db.Model
import sigil.event.Event
import sigil.provider.{CacheKeys, ConversationMode, ConversationRequest, GenerationSettings, Instructions, ProviderRequest, TokenUsage}
import sigil.provider.anthropic.{Anthropic, AnthropicProvider}
import sigil.tool.core.CoreTools

/**
 * Coverage for the Anthropic prompt-caching wiring: `cache_control`
 * breakpoints land on the stable request prefix (tool roster, system
 * prompt, the stable span of conversation history) when caching is
 * enabled, and disappear entirely when the provider toggle is off.
 *
 * Also exercises the shared [[TokenUsage.fromJson]] cache-token
 * parsing across the three provider usage layouts.
 */
class AnthropicPromptCachingSpec extends AnyWordSpec with Matchers {

  TestSigil.initFor(getClass.getSimpleName)

  private val conversationId = sigil.conversation.Conversation.id("cache-control-conv")

  /**
   * A multi-turn history so the third (history) breakpoint has a
   * second-to-last message to anchor on: user, agent, user.
   */
  private def multiTurnInput: TurnInput = TurnInput(
    conversationId = conversationId,
    frames = Vector(
      ContextFrame.Text("first user message", TestUser, Id[Event]("seed-1")),
      ContextFrame.Text("agent reply", TestAgent, Id[Event]("seed-2")),
      ContextFrame.Text("latest user message", TestUser, Id[Event]("seed-3"))
    )
  )

  private def requestFor(input: TurnInput): ProviderRequest =
    ConversationRequest(
      conversationId = conversationId,
      model = TestSigil.testModel(Model.id("anthropic", "claude-haiku-4-5")),
      instructions = Instructions(),
      turnInput = input,
      currentMode = ConversationMode,
      currentTopic = TestTopicEntry,
      generationSettings = GenerationSettings(maxOutputTokens = Some(50)),
      tools = CoreTools.all,
      chain = List(TestUser, TestAgent)
    )

  private def render(provider: AnthropicProvider, input: TurnInput): (Json, Map[String, String]) = {
    val httpReq = provider.requestConverter(requestFor(input)).sync()
    val body = httpReq.content match {
      case Some(c: spice.http.content.StringContent) => fabric.io.JsonParser(c.value)
      case _ => obj()
    }
    val headers = httpReq.headers.map.map { case (k, v) => k.toLowerCase -> v.mkString(",") }
    (body, headers)
  }

  "AnthropicProvider prompt caching (enabled)" should {
    val provider = AnthropicProvider(apiKey = "sk-ant-test", sigilRef = TestSigil)

    "place a cache_control breakpoint on the final tool of the roster" in {
      val (body, _) = render(provider, multiTurnInput)
      val tools = body("tools").asVector
      tools should not be empty
      val lastTool = tools.last
      lastTool.get("cache_control") shouldBe Some(obj("type" -> str("ephemeral"), "ttl" -> str("1h")))
      // Only the final tool carries the breakpoint.
      tools.init.foreach(_.get("cache_control") shouldBe None)
    }

    "place a cache_control breakpoint on the system prompt block" in {
      val (body, _) = render(provider, multiTurnInput)
      val systemArr = body("system").asVector
      systemArr should not be empty
      systemArr.last.get("cache_control") shouldBe Some(obj("type" -> str("ephemeral"), "ttl" -> str("1h")))
    }

    "place a cache_control breakpoint near the end of the stable history span" in {
      val (body, _) = render(provider, multiTurnInput)
      val messages = body("messages").asVector
      messages.size shouldBe 3
      // Second-to-last message carries the (default-TTL) breakpoint;
      // the final (volatile) message does not.
      val breakpointBlocks = messages(1)("content").asVector
      breakpointBlocks.last.get("cache_control") shouldBe Some(obj("type" -> str("ephemeral")))
      messages.last("content").asVector.foreach(_.get("cache_control") shouldBe None)
    }

    "send the extended-cache-ttl beta header" in {
      val (_, headers) = render(provider, multiTurnInput)
      headers.get("anthropic-beta") shouldBe Some(Anthropic.ExtendedCacheTtlBeta)
    }
  }

  "AnthropicProvider prompt caching (disabled)" should {
    val provider = AnthropicProvider(apiKey = "sk-ant-test", sigilRef = TestSigil, promptCaching = false)

    "emit no cache_control on the tool roster" in {
      val (body, _) = render(provider, multiTurnInput)
      body("tools").asVector.foreach(_.get("cache_control") shouldBe None)
    }

    "render the system prompt as a plain string with no cache_control" in {
      val (body, _) = render(provider, multiTurnInput)
      body("system") shouldBe a[Str]
    }

    "emit no cache_control on any message" in {
      val (body, _) = render(provider, multiTurnInput)
      body("messages").asVector.foreach { msg =>
        msg("content").asVector.foreach(_.get("cache_control") shouldBe None)
      }
    }

    "omit the extended-cache-ttl beta header" in {
      val (_, headers) = render(provider, multiTurnInput)
      headers.get("anthropic-beta") shouldBe None
    }
  }

  "TokenUsage cache-token parsing" should {
    "parse Anthropic flat cache keys" in {
      val usage = obj(
        "input_tokens" -> num(120),
        "output_tokens" -> num(30),
        "cache_read_input_tokens" -> num(900),
        "cache_creation_input_tokens" -> num(45)
      )
      val parsed = TokenUsage.fromJson(usage, "input_tokens", "output_tokens", cacheKeys = CacheKeys.Anthropic)
      parsed.promptTokens shouldBe 120
      parsed.completionTokens shouldBe 30
      parsed.cacheReadTokens shouldBe 900
      parsed.cacheCreationTokens shouldBe 45
    }

    "parse OpenAI nested cached_tokens" in {
      val usage = obj(
        "prompt_tokens" -> num(500),
        "completion_tokens" -> num(80),
        "total_tokens" -> num(580),
        "prompt_tokens_details" -> obj("cached_tokens" -> num(384))
      )
      val parsed = TokenUsage.fromJson(
        usage,
        "prompt_tokens",
        "completion_tokens",
        Some("total_tokens"),
        CacheKeys.OpenAIChat)
      parsed.promptTokens shouldBe 500
      parsed.cacheReadTokens shouldBe 384
      parsed.cacheCreationTokens shouldBe 0
    }

    "parse DeepSeek hit/miss cache keys" in {
      val usage = obj(
        "prompt_tokens" -> num(700),
        "completion_tokens" -> num(40),
        "total_tokens" -> num(740),
        "prompt_cache_hit_tokens" -> num(640),
        "prompt_cache_miss_tokens" -> num(60)
      )
      val parsed = TokenUsage.fromJson(
        usage,
        "prompt_tokens",
        "completion_tokens",
        Some("total_tokens"),
        CacheKeys.DeepSeek)
      parsed.cacheReadTokens shouldBe 640
      parsed.cacheCreationTokens shouldBe 60
    }

    "default both cache fields to 0 when no cache keys are supplied (Google)" in {
      val usage = obj(
        "promptTokenCount" -> num(200),
        "candidatesTokenCount" -> num(50),
        "totalTokenCount" -> num(250)
      )
      val parsed = TokenUsage.fromJson(usage, "promptTokenCount", "candidatesTokenCount", Some("totalTokenCount"))
      parsed.cacheReadTokens shouldBe 0
      parsed.cacheCreationTokens shouldBe 0
    }

    "round-trip the cache fields through fabric RW" in {
      import fabric.rw.*
      val usage = TokenUsage(
        promptTokens = 10,
        completionTokens = 5,
        totalTokens = 15,
        cacheReadTokens = 8,
        cacheCreationTokens = 2)
      val restored = usage.json.as[TokenUsage]
      restored shouldBe usage
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
