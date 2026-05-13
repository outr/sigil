package sigil.provider.deepinfra

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.AsyncTaskSpec
import sigil.conversation.{Conversation, TopicEntry, TurnInput}
import sigil.db.Model
import sigil.provider.{ConversationMode, ConversationRequest, Effort, GenerationSettings, Instructions, ReasoningMode}
import sigil.tool.core.CoreTools
import spec.{TestAgent, TestSigil, TestUser}
import spice.net.url

/**
 * Coverage for the DeepInfra provider's translation of
 * [[sigil.provider.GenerationSettings.reasoningMode]] into the wire's
 * `reasoning_effort` field. DeepInfra's `/v1/openai/chat/completions`
 * endpoint exposes the canonical OpenAI enum (`none | low | medium |
 * high`) — empirically verified against kimi-k2.5: `none` zeroes
 * `reasoning_content` and converges on a direct tool call.
 *
 * The shared [[sigil.provider.wire.OpenAIChatCompletions]] wire reads
 * `reasoningMode` (not the older `effort.isDefined` heuristic) so apps
 * express intent via the user-facing abstraction and the provider
 * translates.
 */
class DeepInfraReasoningModeSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val provider = DeepInfraProvider("test-key", TestSigil, url"https://api.deepinfra.com")
  private val topic    = TopicEntry(sigil.conversation.Topic.id("t"), label = "t", summary = "t")
  private val convId   = Conversation.id("deepinfra-reasoning-spec")
  private val modelId: Id[Model] = Model.id(DeepInfra.Provider, "moonshotai/Kimi-K2.5")

  private def bodyOf(mode: ReasoningMode, effort: Option[Effort] = None): rapid.Task[String] = {
    val req = ConversationRequest(
      conversationId     = convId,
      modelId            = modelId,
      instructions       = Instructions(),
      turnInput          = TurnInput(conversationId = convId),
      currentMode        = ConversationMode,
      currentTopic       = topic,
      generationSettings = GenerationSettings(maxOutputTokens = Some(50), reasoningMode = mode, effort = effort),
      tools              = CoreTools.all,
      chain              = List(TestUser, TestAgent)
    )
    provider.requestConverter(req).map(_.content match {
      case Some(c: spice.http.content.StringContent) => c.value
      case _                                          => ""
    })
  }

  "DeepInfraProvider reasoning_effort wiring" should {

    "send reasoning_effort=none when ReasoningMode.Off" in {
      bodyOf(ReasoningMode.Off).map { body =>
        body should include("\"reasoning_effort\":\"none\"")
      }
    }

    "send reasoning_effort=high when ReasoningMode.On (default level)" in {
      bodyOf(ReasoningMode.On).map { body =>
        body should include("\"reasoning_effort\":\"high\"")
      }
    }

    "respect explicit Effort level when ReasoningMode.On is set" in {
      bodyOf(ReasoningMode.On, effort = Some(Effort.Low)).map { body =>
        body should include("\"reasoning_effort\":\"low\"")
      }
    }

    "omit reasoning_effort entirely when ReasoningMode.Auto with no effort" in {
      bodyOf(ReasoningMode.Auto).map { body =>
        body shouldNot include("reasoning_effort")
      }
    }

    "send reasoning_effort from explicit Effort when ReasoningMode.Auto + Effort.Medium" in {
      bodyOf(ReasoningMode.Auto, effort = Some(Effort.Medium)).map { body =>
        body should include("\"reasoning_effort\":\"medium\"")
      }
    }

    "target /v1/openai/chat/completions (DeepInfra's OpenAI-compat path)" in {
      // Inspect the constructed HttpRequest's URL path rather than the
      // body to verify routing.
      val req = ConversationRequest(
        conversationId     = convId,
        modelId            = modelId,
        instructions       = Instructions(),
        turnInput          = TurnInput(conversationId = convId),
        currentMode        = ConversationMode,
        currentTopic       = topic,
        generationSettings = GenerationSettings(maxOutputTokens = Some(50)),
        tools              = CoreTools.all,
        chain              = List(TestUser, TestAgent)
      )
      provider.requestConverter(req).map { httpReq =>
        httpReq.url.toString should include("/v1/openai/chat/completions")
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
