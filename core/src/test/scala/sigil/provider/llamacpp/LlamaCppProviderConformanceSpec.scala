package sigil.provider.llamacpp

import lightdb.id.Id
import sigil.db.Model
import sigil.provider.Provider
import sigil.provider.wire.OpenAIChatCompletions
import spec.{AbstractProviderConformanceSpec, ChatCompletionsConformanceSupport, TestSigil}
import spice.net.url

/**
 * [[AbstractProviderConformanceSpec]] pins for [[LlamaCppProvider]] —
 * the [[sigil.provider.SchemaDialect.Identity]] dialect (the full
 * canonical schema ships; llama.cpp's GBNF translator consumes
 * `pattern` / numeric bounds directly) over the chat-completions wire.
 * The case-class constructor is network-free (server discovery lives
 * on the companion `apply`), so the pins run without a live backend.
 */
class LlamaCppProviderConformanceSpec extends AbstractProviderConformanceSpec with ChatCompletionsConformanceSupport {

  private lazy val provider = LlamaCppProvider(url = url"http://localhost:8081", models = Nil, sigilRef = TestSigil)

  override protected def providerInstance: Provider = provider

  override protected def modelId: Id[Model] = Model.id("llamacpp", "conformance-model")

  override protected def chatWireConfig: OpenAIChatCompletions.Config = provider.wireConfig

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed).sync()
  }
}
