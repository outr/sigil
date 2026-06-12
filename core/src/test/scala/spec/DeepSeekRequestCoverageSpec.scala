package spec

import lightdb.id.Id
import sigil.db.Model
import sigil.provider.Provider
import sigil.provider.deepseek.{DeepSeek, DeepSeekProvider}

class DeepSeekRequestCoverageSpec extends AbstractRequestCoverageSpec {
  override protected def providerInstance: Provider =
    DeepSeekProvider(apiKey = "sk-test-placeholder", sigilRef = TestSigil)
  override protected def modelId: Id[Model] = Model.id("deepseek", "deepseek-chat")
    TestSigil.testModel(modelId)

  /** DeepSeek's OpenAI-compatible API accepts a trailing assistant message
    * (it even has explicit prefix-completion support), so the #396
    * user-anchor guard is not applied here. */
  override protected def appliesUserAnchor: Boolean = false

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
