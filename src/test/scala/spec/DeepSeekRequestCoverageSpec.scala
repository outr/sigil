package spec

import lightdb.id.Id
import sigil.db.Model
import sigil.provider.Provider
import sigil.provider.deepseek.{DeepSeek, DeepSeekProvider}

class DeepSeekRequestCoverageSpec extends AbstractRequestCoverageSpec {
  override protected def providerInstance: Provider =
    DeepSeekProvider(apiKey = "sk-test-placeholder", models = DeepSeek.models, sigilRef = TestSigil)
  override protected def modelId: Id[Model] = Model.id("deepseek", "deepseek-chat")
}
