package spec

import lightdb.id.Id
import rapid.Task
import sigil.db.Model
import sigil.provider.Provider
import sigil.provider.deepseek.DeepSeekProvider

class DeepSeekProviderSpec extends AbstractProviderSpec {
  override protected val provider: Task[Provider] =
    DeepSeekProvider.create(TestSigil, DeepSeekLiveSupport.apiKey.getOrElse("")).singleton

  override protected def modelId: Id[Model] =
    Model.id(sys.env.getOrElse("DEEPSEEK_TEST_MODEL", "deepseek/deepseek-chat"))

  override def run(testName: Option[String], args: org.scalatest.Args): org.scalatest.Status =
    DeepSeekLiveSupport.runGated(this, testName, args) {
      super.run(testName, args)
    }
}
