package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.AsyncTaskSpec
import sigil.conversation.{Conversation, TopicEntry, TurnInput}
import sigil.db.Model
import sigil.provider.{ConversationMode, ConversationRequest, GenerationSettings, Instructions, ReasoningMode}
import sigil.provider.cloudflare.{Cloudflare, CloudflareProvider}
import sigil.tool.core.CoreTools

/**
 * Cloudflare treats `ReasoningMode.Auto` as off at the wire — Kimi-K2.6
 * reasons unboundedly under Auto and never transitions to the tool call.
 * Explicit `On`/`Off` is still honored.
 */
class CloudflareReasoningModeSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val provider = CloudflareProvider("test-token", "test-account", TestSigil)
  private val topic    = TopicEntry(sigil.conversation.Topic.id("t"), label = "t", summary = "t")
  private val convId   = Conversation.id("cloudflare-reasoning-spec")
  private val modelId: Id[Model] = Model.id(Cloudflare.Provider, "@cf/moonshotai/kimi-k2.6")

  private def bodyOf(mode: ReasoningMode): rapid.Task[String] = {
    val req = ConversationRequest(
      conversationId     = convId,
      model              = TestSigil.testModel(modelId),
      instructions       = Instructions(),
      turnInput          = TurnInput(conversationId = convId),
      currentMode        = ConversationMode,
      currentTopic       = topic,
      generationSettings = GenerationSettings(maxOutputTokens = Some(50), reasoningMode = mode),
      tools              = CoreTools.all,
      chain              = List(TestUser, TestAgent)
    )
    provider.requestConverter(req).map(_.content match {
      case Some(c: spice.http.content.StringContent) => c.value
      case _                                          => ""
    })
  }

  "CloudflareProvider reasoning wiring" should {

    "disable thinking when ReasoningMode.Auto (treated as off)" in {
      bodyOf(ReasoningMode.Auto).map { body =>
        body should include ("\"enable_thinking\":false")
        body should not include "reasoning_effort"
      }
    }

    "disable thinking when ReasoningMode.Off" in {
      bodyOf(ReasoningMode.Off).map { body =>
        body should include ("\"enable_thinking\":false")
      }
    }

    "honor explicit ReasoningMode.On with reasoning_effort" in {
      bodyOf(ReasoningMode.On).map { body =>
        body should include ("reasoning_effort")
        body should not include "enable_thinking"
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
