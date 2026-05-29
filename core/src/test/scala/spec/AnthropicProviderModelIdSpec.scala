package spec

import fabric.*
import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.conversation.{ContextFrame, TurnInput}
import sigil.db.Model
import sigil.event.Event
import sigil.provider.{ConversationMode, ConversationRequest, GenerationSettings, Instructions, ProviderRequest}
import sigil.provider.anthropic.AnthropicProvider
import sigil.tool.core.CoreTools

/**
 * Sigil #308 — the outbound `model` field on the Anthropic Messages
 * wire must use hyphenated version segments (`claude-haiku-4-5`).
 * OpenRouter's catalog publishes the dotted form
 * (`anthropic/claude-haiku-4.5`); without translation, every direct
 * Anthropic call against an OpenRouter-discovered model 404s with
 * Anthropic's own error message hinting at the hyphenated id.
 */
class AnthropicProviderModelIdSpec extends AnyWordSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val convId = sigil.conversation.Conversation.id("model-id-spec-conv")

  private def turnInput: TurnInput = TurnInput(
    conversationId = convId,
    frames = Vector(
      ContextFrame.Text("user", TestUser, Id[Event]("seed-1"))
    )
  )

  private def requestFor(modelId: Id[Model]): ProviderRequest =
    ConversationRequest(
      conversationId = convId,
      model = TestSigil.testModel(modelId),
      instructions = Instructions(),
      turnInput = turnInput,
      currentMode = ConversationMode,
      currentTopic = TestTopicEntry,
      generationSettings = GenerationSettings(maxOutputTokens = Some(50)),
      tools = CoreTools.all,
      chain = List(TestUser, TestAgent)
    )

  private def modelFieldFor(modelId: Id[Model]): String = {
    val provider = AnthropicProvider(apiKey = "sk-ant-test", sigilRef = TestSigil)
    val httpReq = provider.requestConverter(requestFor(modelId)).sync()
    val body = httpReq.content match {
      case Some(c: spice.http.content.StringContent) => fabric.io.JsonParser(c.value)
      case _                                          => obj()
    }
    body("model").asString
  }

  "AnthropicProvider model field (sigil #308)" should {

    "translate dotted version segments to hyphens so the Anthropic API accepts the id" in {
      // OpenRouter catalog id shape — what `sigil.cache` carries when
      // the catalog is sourced from OpenRouter (the documented
      // discovery path). Anthropic's API hard-rejects the dotted form.
      val modelId = Model.id("anthropic", "claude-haiku-4.5")
      modelFieldFor(modelId) shouldBe "claude-haiku-4-5"
    }

    "leave already-hyphenated ids unchanged (no over-translation)" in {
      val modelId = Model.id("anthropic", "claude-haiku-4-5-20251001")
      modelFieldFor(modelId) shouldBe "claude-haiku-4-5-20251001"
    }

    "translate every dot in a multi-dot id (not just the first)" in {
      // Defensive coverage in case Anthropic ever ships an id whose
      // OpenRouter form has multiple dotted segments. Pins the
      // `.replace('.', '-')` semantics over single-replace
      // alternatives.
      val modelId = Model.id("anthropic", "claude-x.y.z-20260101")
      modelFieldFor(modelId) shouldBe "claude-x-y-z-20260101"
    }
  }
}
