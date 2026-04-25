package spec

import lightdb.id.Id
import sigil.db.Model
import sigil.provider.Provider
import sigil.provider.anthropic.AnthropicProvider

class AnthropicRequestCoverageSpec extends AbstractRequestCoverageSpec {
  override protected def providerInstance: Provider =
    AnthropicProvider(apiKey = "sk-ant-test-placeholder", sigilRef = TestSigil)
  override protected def modelId: Id[Model] = Model.id("anthropic", "claude-haiku-4-5")
}
