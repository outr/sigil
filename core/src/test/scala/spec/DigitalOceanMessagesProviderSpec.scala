package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.AsyncTaskSpec
import sigil.conversation.{Conversation, TopicEntry, TurnInput}
import sigil.db.Model
import sigil.provider.{
  ConversationMode, ConversationRequest, GenerationSettings, Instructions
}
import sigil.provider.anthropic.{AnthropicAuthMode, AnthropicProvider}
import sigil.provider.digitalocean.DigitalOceanMessagesProvider
import sigil.tool.core.CoreTools
import spice.net.url

/**
 * Coverage for [[DigitalOceanMessagesProvider]] and the Bearer-auth
 * knob on [[AnthropicProvider]]. Deterministic — no live calls. The
 * live entitlement to specific Claude models on DO varies by account
 * tier; end users with access can drive the full conversation suite
 * through their own subclass once they've confirmed model
 * availability.
 */
class DigitalOceanMessagesProviderSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val convId = Conversation.id("do-messages-spec")
  private val topic  = TopicEntry(sigil.conversation.Topic.id("t"), label = "t", summary = "t")
  private val modelId: Id[Model] = Model.id("anthropic", "anthropic-claude-opus-4")

  private def baseRequest(model: Id[Model]): ConversationRequest =
    ConversationRequest(
      conversationId     = convId,
      modelId            = model,
      instructions       = Instructions(),
      turnInput          = TurnInput(conversationId = convId),
      currentMode        = ConversationMode,
      currentTopic       = topic,
      generationSettings = GenerationSettings(maxOutputTokens = Some(50), temperature = Some(0.0)),
      tools              = CoreTools.all,
      chain              = List(TestUser, TestAgent)
    )

  "DigitalOceanMessagesProvider.create" should {

    "produce an AnthropicProvider configured with Bearer auth at the DO base URL" in {
      DigitalOceanMessagesProvider.create(TestSigil, apiKey = "do-test-key").map { provider =>
        provider.authMode.shouldBe(AnthropicAuthMode.Bearer)
        provider.baseUrl.toString should include("inference.do-ai.run")
        provider.apiKey.shouldBe("do-test-key")
      }
    }

    "send Authorization: Bearer instead of x-api-key + anthropic-version on the wire" in {
      DigitalOceanMessagesProvider.create(TestSigil, apiKey = "do-test-key").flatMap { provider =>
        provider.requestConverter(baseRequest(modelId)).map { req =>
          val headerNames = req.headers.map.keySet.map(_.toLowerCase)
          headerNames should contain("authorization")
          headerNames shouldNot contain("x-api-key")
          headerNames shouldNot contain("anthropic-version")
          val authValues = req.headers.first(spice.http.StringHeaderKey("Authorization")).toList
          authValues.exists(_.startsWith("Bearer ")).shouldBe(true)
        }
      }
    }
  }

  "AnthropicProvider default" should {

    "preserve x-api-key + anthropic-version headers (no Bearer) when authMode is unset" in {
      val direct = AnthropicProvider(apiKey = "direct-test-key", sigilRef = TestSigil)
      direct.authMode.shouldBe(AnthropicAuthMode.XApiKey)
      direct.requestConverter(baseRequest(modelId)).map { req =>
        val headerNames = req.headers.map.keySet.map(_.toLowerCase)
        headerNames should contain("x-api-key")
        headerNames should contain("anthropic-version")
        headerNames shouldNot contain("authorization")
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
