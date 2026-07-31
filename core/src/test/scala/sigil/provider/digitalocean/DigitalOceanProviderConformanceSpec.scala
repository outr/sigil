package sigil.provider.digitalocean

import lightdb.id.Id
import sigil.db.Model
import sigil.provider.Provider
import sigil.provider.wire.OpenAIChatCompletions
import spec.{AbstractProviderConformanceSpec, ChatCompletionsConformanceSupport, TestSigil}

/**
 * [[AbstractProviderConformanceSpec]] pins for [[DigitalOceanProvider]]
 * — OpenAI-strict dialect over the chat-completions wire with the kimi
 * reasoning-directive preprocess.
 */
class DigitalOceanProviderConformanceSpec extends AbstractProviderConformanceSpec with ChatCompletionsConformanceSupport {

  private lazy val provider = DigitalOceanProvider(apiKey = "test-key-placeholder", sigilRef = TestSigil)

  override protected def providerInstance: Provider = provider

  override protected def modelId: Id[Model] = Model.id("digitalocean", "conformance-model")

  override protected def chatWireConfig: OpenAIChatCompletions.Config = provider.wireConfig

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed).sync()
  }
}
