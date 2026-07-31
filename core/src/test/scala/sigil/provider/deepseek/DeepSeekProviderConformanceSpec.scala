package sigil.provider.deepseek

import lightdb.id.Id
import sigil.db.Model
import sigil.provider.Provider
import sigil.provider.wire.OpenAIChatCompletions
import spec.{AbstractProviderConformanceSpec, ChatCompletionsConformanceSupport, TestSigil}

/**
 * [[AbstractProviderConformanceSpec]] pins for [[DeepSeekProvider]] —
 * OpenAI-strict dialect over the chat-completions wire, driven with
 * the provider's own wire config.
 */
class DeepSeekProviderConformanceSpec extends AbstractProviderConformanceSpec with ChatCompletionsConformanceSupport {

  private lazy val provider = DeepSeekProvider(apiKey = "test-key-placeholder", sigilRef = TestSigil)

  override protected def providerInstance: Provider = provider

  override protected def modelId: Id[Model] = Model.id("deepseek", "conformance-model")

  override protected def chatWireConfig: OpenAIChatCompletions.Config = provider.wireConfig

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed).sync()
  }
}
