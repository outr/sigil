package sigil.provider.openrouter

import lightdb.id.Id
import sigil.db.Model
import sigil.provider.Provider
import sigil.provider.wire.OpenAIChatCompletions
import spec.{AbstractProviderConformanceSpec, ChatCompletionsConformanceSupport, TestSigil}

/**
 * [[AbstractProviderConformanceSpec]] pins for [[OpenRouterProvider]]
 * — OpenAI-strict dialect over the gateway chat-completions wire with
 * the provider-routing extra body.
 */
class OpenRouterProviderConformanceSpec extends AbstractProviderConformanceSpec with ChatCompletionsConformanceSupport {

  private lazy val provider = OpenRouterProvider(apiKey = "test-key-placeholder", sigilRef = TestSigil)

  override protected def providerInstance: Provider = provider

  override protected def modelId: Id[Model] = Model.id("openrouter", "conformance-model")

  override protected def chatWireConfig: OpenAIChatCompletions.Config = provider.wireConfig

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed).sync()
  }
}
