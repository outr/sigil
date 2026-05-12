package sigil.provider.digitalocean

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.AsyncTaskSpec
import sigil.conversation.{Conversation, TopicEntry, TurnInput}
import sigil.db.Model
import sigil.provider.{
  ConversationMode, ConversationRequest, GenerationSettings, Instructions,
  ReasoningMode
}
import sigil.tool.core.CoreTools
import spec.{TestAgent, TestSigil, TestUser}
import spice.net.url

/**
 * Regression for sigil bug #155 — DO's kimi-k2.5 / kimi-k2.6
 * models default to thinking-on, burning the output budget on
 * 10K-token reasoning chains before producing any content. The
 * framework had no way to suppress this short of forking the
 * provider.
 *
 * Fix: `GenerationSettings.reasoningMode` (Auto | On | Off).
 * `DigitalOceanProvider.buildBody` translates `Off → /no_think`
 * and `On → /think` for kimi models via the system-prompt
 * directive Moonshot documents. Other DO-hosted models without
 * a thinking mode ignore the marker (text passes through to a
 * model that doesn't read it — harmless).
 */
class DigitalOceanReasoningModeSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val provider = OpenAIProviderProxy
  private val doProvider = DigitalOceanProvider("sk-test-placeholder", TestSigil, url"https://inference.do-ai.run")
  private val topic = TopicEntry(sigil.conversation.Topic.id("t"), label = "t", summary = "t")
  private val convId = Conversation.id("reasoning-mode-spec")

  /** Build a `ConversationRequest` with the given reasoning mode
    * + model id; route through `requestConverter` to inspect the
    * wire body. */
  private def bodyOf(modelId: Id[Model], mode: ReasoningMode): rapid.Task[String] = {
    val req = ConversationRequest(
      conversationId     = convId,
      modelId            = modelId,
      instructions       = Instructions(),
      turnInput          = TurnInput(conversationId = convId),
      currentMode        = ConversationMode,
      currentTopic       = topic,
      generationSettings = GenerationSettings(maxOutputTokens = Some(50), reasoningMode = mode),
      tools              = CoreTools.all,
      chain              = List(TestUser, TestAgent)
    )
    doProvider.requestConverter(req).map(_.content match {
      case Some(c: spice.http.content.StringContent) => c.value
      case _                                          => ""
    })
  }

  // Avoid hardcoding to a specific kimi version — match on prefix.
  private val kimi25Id: Id[Model] = Model.id(DigitalOcean.Provider, "kimi-k2.5")
  private val kimi26Id: Id[Model] = Model.id(DigitalOcean.Provider, "kimi-k2.6")
  private val nonKimiId: Id[Model] = Model.id(DigitalOcean.Provider, "llama3.3-70b-instruct")

  "DigitalOceanProvider.buildBody (kimi reasoning toggle)" should {

    "append /no_think to the system prompt for kimi-k2.5 when ReasoningMode.Off" in {
      bodyOf(kimi25Id, ReasoningMode.Off).map { body =>
        body should include("/no_think")
        body shouldNot include("/think\"")  // not a bare /think directive
      }
    }

    "append /think for kimi-k2.5 when ReasoningMode.On" in {
      bodyOf(kimi25Id, ReasoningMode.On).map { body =>
        body should include("/think")
        body shouldNot include("/no_think")
      }
    }

    "leave the system prompt unchanged for kimi-k2.5 when ReasoningMode.Auto" in {
      bodyOf(kimi25Id, ReasoningMode.Auto).map { body =>
        body shouldNot include("/no_think")
        body shouldNot include("/think")
      }
    }

    "apply the same directive to kimi-k2.6" in {
      bodyOf(kimi26Id, ReasoningMode.Off).map { body =>
        body should include("/no_think")
      }
    }

    "leave non-kimi models unchanged regardless of mode" in {
      for {
        offBody <- bodyOf(nonKimiId, ReasoningMode.Off)
        onBody  <- bodyOf(nonKimiId, ReasoningMode.On)
      } yield {
        offBody shouldNot include("/no_think")
        offBody shouldNot include("/think")
        onBody shouldNot include("/no_think")
        onBody shouldNot include("/think")
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }

  // Placeholder — kept so the spec compiles alongside the real provider.
  private object OpenAIProviderProxy
}
