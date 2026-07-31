package sigil.provider.cloudflare

import lightdb.id.Id
import sigil.db.Model
import sigil.provider.Provider
import sigil.provider.wire.OpenAIChatCompletions
import spec.{AbstractProviderConformanceSpec, ChatCompletionsConformanceSupport, TestSigil}

/**
 * [[AbstractProviderConformanceSpec]] pins for [[CloudflareProvider]]
 * — OpenAI-strict dialect over the accounts-scoped chat-completions
 * path, non-streaming transport default.
 */
class CloudflareProviderConformanceSpec extends AbstractProviderConformanceSpec with ChatCompletionsConformanceSupport {

  private lazy val provider =
    CloudflareProvider(apiToken = "test-token-placeholder", accountId = "test-account", sigilRef = TestSigil)

  override protected def providerInstance: Provider = provider

  override protected def modelId: Id[Model] = Model.id("cloudflare", "conformance-model")

  override protected def chatWireConfig: OpenAIChatCompletions.Config = provider.wireConfig

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed).sync()
  }
}
