package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.conversation.{ContextFrame, ConversationView, TurnInput}
import sigil.db.Model
import sigil.event.Event
import sigil.provider.{
  ConversationMode,
  ConversationRequest,
  Effort,
  GenerationSettings,
  Instructions,
  Mode,
  Provider,
  ProviderRequest
}
import sigil.provider.anthropic.AnthropicProvider
import sigil.provider.deepseek.{DeepSeek, DeepSeekProvider}
import sigil.provider.google.{Google, GoogleProvider}
import sigil.provider.llamacpp.{LlamaCpp, LlamaCppProvider}
import sigil.provider.openai.OpenAIProvider
import spice.net.url

/**
 * Wire-format coverage for `GenerationSettings.effort`. Each provider
 * translates `Effort` to its own reasoning / thinking knob; this spec
 * asserts the translation lands on the wire so a future change to the
 * mapping can't silently drop thinking.
 */
class EffortWiringSpec extends AnyWordSpec with Matchers {

  TestSigil.initFor(getClass.getSimpleName)

  private val conversationId = sigil.conversation.Conversation.id("effort-wiring-conv")
  private val view = ConversationView(
    conversationId = conversationId,
    frames = Vector(ContextFrame.Text(
      content = "hello",
      participantId = TestUser,
      sourceEventId = Id[Event]("effort-seed")
    )),
    _id = ConversationView.idFor(conversationId)
  )

  private def requestWith(provider: Provider, modelId: Id[Model], gen: GenerationSettings): ProviderRequest =
    ConversationRequest(
      conversationId = conversationId,
      modelId = modelId,
      instructions = Instructions(),
      turnInput = TurnInput(view),
      currentMode = ConversationMode,
      currentTopic = TestTopicEntry,
      generationSettings = gen,
      tools = Vector.empty,
      chain = List(TestUser, TestAgent)
    )

  private def bodyOf(provider: Provider, modelId: Id[Model], gen: GenerationSettings): String =
    provider.requestConverter(requestWith(provider, modelId, gen)).sync().content match {
      case Some(c: spice.http.content.StringContent) => c.value
      case _ => ""
    }

  "GoogleProvider thinkingConfig" should {
    val provider = GoogleProvider(apiKey = "t", sigilRef = TestSigil)
    val modelId = Model.id("google", "gemini-2.5-flash")

    "emit thinkingBudget=0 when effort is None (default off)" in {
      val body = bodyOf(provider, modelId, GenerationSettings(maxOutputTokens = Some(100)))
      body should include("\"thinkingBudget\":0")
    }

    "emit positive thinkingBudget when effort=Medium" in {
      val body = bodyOf(provider, modelId, GenerationSettings(maxOutputTokens = Some(100), effort = Some(Effort.Medium)))
      body should include("\"thinkingBudget\":8192")
    }

    "emit dynamic (-1) when effort=Max" in {
      val body = bodyOf(provider, modelId, GenerationSettings(maxOutputTokens = Some(100), effort = Some(Effort.Max)))
      body should include("\"thinkingBudget\":-1")
    }

    "honor Custom token budget" in {
      val body = bodyOf(provider, modelId, GenerationSettings(maxOutputTokens = Some(100), effort = Some(Effort.Custom(2048))))
      body should include("\"thinkingBudget\":2048")
    }
  }

  "AnthropicProvider thinking" should {
    val provider = AnthropicProvider(apiKey = "k", sigilRef = TestSigil)
    val modelId = Model.id("anthropic", "claude-haiku-4-5")

    "omit the thinking field when effort is None" in {
      val body = bodyOf(provider, modelId, GenerationSettings(maxOutputTokens = Some(4096)))
      body should not include "\"thinking\""
    }

    "emit thinking.type=enabled with a budget_tokens when effort=High" in {
      val body = bodyOf(provider, modelId, GenerationSettings(maxOutputTokens = Some(20000), effort = Some(Effort.High)))
      body should include("\"thinking\":")
      body should include("\"type\":\"enabled\"")
      body should include("\"budget_tokens\":16384")
    }

    "clamp budget_tokens to at least 1024" in {
      val body = bodyOf(provider, modelId, GenerationSettings(maxOutputTokens = Some(4096), effort = Some(Effort.Custom(100))))
      body should include("\"budget_tokens\":1024")
    }

    "clamp budget_tokens below max_tokens to leave output room" in {
      val body = bodyOf(provider, modelId, GenerationSettings(maxOutputTokens = Some(3000), effort = Some(Effort.High)))
      // High asks for 16384 but max_tokens is 3000 → ceiling is 3000 - 512 = 2488, clamped to max(1024, 2488) = 2488
      body should include("\"budget_tokens\":2488")
    }

    "omit top_p when thinking is enabled" in {
      val body = bodyOf(
        provider,
        modelId,
        GenerationSettings(maxOutputTokens = Some(20000), effort = Some(Effort.Low), topP = Some(0.9))
      )
      body should not include "top_p"
    }

    "reject non-1.0 temperature when thinking is enabled" in {
      val ex = intercept[IllegalArgumentException] {
        bodyOf(
          provider,
          modelId,
          GenerationSettings(maxOutputTokens = Some(20000), temperature = Some(0.3), effort = Some(Effort.Low))
        )
      }
      ex.getMessage should include("temperature=1.0")
    }

    "accept temperature=1.0 with thinking enabled" in {
      val body = bodyOf(
        provider,
        modelId,
        GenerationSettings(maxOutputTokens = Some(20000), temperature = Some(1.0), effort = Some(Effort.Low))
      )
      body should include("\"thinking\"")
      body should include("\"temperature\":1")
    }
  }

  "LlamaCppProvider chat_template_kwargs" should {
    val provider = LlamaCppProvider(url = url"http://localhost:8080", models = Nil, sigilRef = TestSigil)
    val modelId = Model.id("qwen3.5-9b-q4_k_m")

    "emit enable_thinking=false when effort is None" in {
      val body = bodyOf(provider, modelId, GenerationSettings(maxOutputTokens = Some(100)))
      body should include("\"enable_thinking\":false")
    }

    "emit enable_thinking=true when effort is set" in {
      val body = bodyOf(provider, modelId, GenerationSettings(maxOutputTokens = Some(100), effort = Some(Effort.Low)))
      body should include("\"enable_thinking\":true")
    }
  }

  "DeepSeekProvider reasoning_effort" should {
    val provider = DeepSeekProvider(apiKey = "sk-test", sigilRef = TestSigil)
    val modelId = Model.id("deepseek", "deepseek-reasoner")

    "omit reasoning_effort when effort is None" in {
      val body = bodyOf(provider, modelId, GenerationSettings(maxOutputTokens = Some(100)))
      body should not include "reasoning_effort"
    }

    "emit reasoning_effort=medium when effort=Medium" in {
      val body = bodyOf(provider, modelId, GenerationSettings(maxOutputTokens = Some(100), effort = Some(Effort.Medium)))
      body should include("\"reasoning_effort\":\"medium\"")
    }
  }

  "OpenAIProvider reasoning" should {
    val provider = OpenAIProvider(apiKey = "sk-test", sigilRef = TestSigil)
    val reasoningModelId = Model.id("openai", "gpt-5")
    val plainModelId     = Model.id("openai", "gpt-4o-mini")

    // Bug #62 — for reasoning-family models, the request always opts
    // into `reasoning.summary = "auto"` (and `include =
    // ["reasoning.encrypted_content"]` via the body builder) so the
    // returned reasoning items carry replayable content. With effort
    // unset, only `summary` is emitted; with effort set, both
    // `effort` and `summary` are emitted together.
    "emit reasoning.summary='auto' on a reasoning-family model with no effort set" in {
      val body = bodyOf(provider, reasoningModelId, GenerationSettings(maxOutputTokens = Some(100)))
      body should include("\"reasoning\":")
      body should include("\"summary\":\"auto\"")
      body shouldNot include("\"effort\":")
    }

    "omit reasoning entirely on a non-reasoning model with no effort set" in {
      val body = bodyOf(provider, plainModelId, GenerationSettings(maxOutputTokens = Some(100)))
      body shouldNot include("\"reasoning\":")
    }

    "emit reasoning.effort=low + summary=auto when effort=Low on a reasoning-family model" in {
      val body = bodyOf(provider, reasoningModelId, GenerationSettings(maxOutputTokens = Some(100), effort = Some(Effort.Low)))
      body should include("\"reasoning\":")
      body should include("\"effort\":\"low\"")
      body should include("\"summary\":\"auto\"")
    }

    "emit reasoning.effort=low alone (no summary) on a non-reasoning model when effort is set" in {
      // Non-reasoning models accept `effort` but won't emit reasoning
      // items, so the `summary` opt-in is omitted to keep the request
      // body minimal.
      val body = bodyOf(provider, plainModelId, GenerationSettings(maxOutputTokens = Some(100), effort = Some(Effort.Low)))
      body should include("\"reasoning\":")
      body should include("\"effort\":\"low\"")
      body shouldNot include("\"summary\":\"auto\"")
    }

    "map Effort.Max to reasoning.effort=high" in {
      val body = bodyOf(provider, reasoningModelId, GenerationSettings(maxOutputTokens = Some(100), effort = Some(Effort.Max)))
      body should include("\"effort\":\"high\"")
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
