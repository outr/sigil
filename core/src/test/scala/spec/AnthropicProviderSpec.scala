package spec

import lightdb.id.Id
import rapid.Task
import sigil.db.Model
import sigil.provider.Provider
import sigil.provider.anthropic.AnthropicProvider

class AnthropicProviderSpec extends AbstractProviderSpec {
  override protected val provider: Task[Provider] =
    AnthropicProvider.create(TestSigil, AnthropicLiveSupport.apiKey.getOrElse("")).singleton

  override protected def modelId: Id[Model] =
    Model.id(sys.env.getOrElse("ANTHROPIC_TEST_MODEL", "anthropic/claude-haiku-4-5"))

  override def run(testName: Option[String], args: org.scalatest.Args): org.scalatest.Status =
    AnthropicLiveSupport.runGated(this, testName, args) {
      super.run(testName, args)
    }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
