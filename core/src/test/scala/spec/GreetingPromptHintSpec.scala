package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.conversation.{Conversation, TopicEntry, TurnInput}
import sigil.db.Model
import sigil.event.Message
import sigil.provider.{
  ConversationMode, ConversationRequest, GenerationSettings, Instructions,
  Provider, ProviderCall, ProviderEvent, ProviderType
}
import sigil.provider.llamacpp.LlamaCppProvider
import sigil.tool.core.{CoreTools, RespondTool}
import spice.http.HttpRequest

/**
 * Coverage for sigil bug #63 — when the agent loop fires a
 * greeting turn (`greetsOnJoin = true`, iteration == 1), the
 * system prompt now carries an explicit "Greeting turn"
 * instruction so the model deterministically calls `respond`
 * with an introduction instead of picking between
 * respond / no_response / find_capability stochastically.
 */
class GreetingPromptHintSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val modelId: Id[Model] = Model.id("test", "model")

  private def buildRequest(isGreeting: Boolean): ConversationRequest =
    ConversationRequest(
      conversationId     = Conversation.id("greeting-test"),
      modelId            = modelId,
      instructions       = Instructions(),
      turnInput          = TurnInput(conversationId = Conversation.id("greeting-test")),
      currentMode        = ConversationMode,
      currentTopic       = TestTopicEntry,
      previousTopics     = Nil,
      generationSettings = GenerationSettings(maxOutputTokens = Some(50), temperature = Some(0.0)),
      tools              = CoreTools.all,
      chain              = List(TestUser, TestAgent),
      isGreeting         = isGreeting
    )

  /** Render the system prompt the provider would send by going
    * through the same `requestConverter` path as a real call. */
  private def renderSystemPromptVia(provider: Provider, request: ConversationRequest): Task[String] =
    provider.requestConverter(request).map(_.content match {
      case Some(c: spice.http.content.StringContent) => c.value
      case _                                          => ""
    })

  "ConversationRequest.isGreeting" should {

    "inject the greeting hint into the system prompt when set" in {
      val provider = LlamaCppProvider(TestSigil.llamaCppHost, Nil, TestSigil)
      renderSystemPromptVia(provider, buildRequest(isGreeting = true)).map { body =>
        body should include("Greeting turn")
        body should include("brief introduction")
        // And the prompt directs the model away from the
        // ambiguous fallback choices.
        body should include("Do NOT call `no_response`")
      }
    }

    "leave the system prompt unchanged when isGreeting is false" in {
      val provider = LlamaCppProvider(TestSigil.llamaCppHost, Nil, TestSigil)
      renderSystemPromptVia(provider, buildRequest(isGreeting = false)).map { body =>
        body should not include "Greeting turn"
        body should not include "brief introduction"
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
