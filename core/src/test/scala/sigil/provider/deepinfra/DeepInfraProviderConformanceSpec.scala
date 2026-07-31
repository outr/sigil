package sigil.provider.deepinfra

import lightdb.id.Id
import sigil.db.Model
import sigil.provider.Provider
import sigil.provider.wire.OpenAIChatCompletions
import spec.{AbstractProviderConformanceSpec, ChatCompletionsConformanceSupport, TestSigil}

/**
 * [[AbstractProviderConformanceSpec]] pins for [[DeepInfraProvider]] —
 * OpenAI-strict schema shaping with `honorsStrict = false` (the flag
 * never ships) and forced calls expressed via
 * `response_format: json_schema`. The open-JSON pin covers the
 * response_format builders' containsJson opt-out.
 */
class DeepInfraProviderConformanceSpec extends AbstractProviderConformanceSpec with ChatCompletionsConformanceSupport {

  private lazy val provider = DeepInfraProvider(apiKey = "test-key-placeholder", sigilRef = TestSigil)

  override protected def providerInstance: Provider = provider

  override protected def modelId: Id[Model] = Model.id("deepinfra", "conformance-model")

  override protected def chatWireConfig: OpenAIChatCompletions.Config = provider.wireConfig

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed).sync()
  }
}
