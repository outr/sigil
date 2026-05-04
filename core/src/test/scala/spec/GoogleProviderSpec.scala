package spec

import lightdb.id.Id
import rapid.Task
import sigil.db.Model
import sigil.provider.Provider
import sigil.provider.google.GoogleProvider

class GoogleProviderSpec extends AbstractProviderSpec {
  override protected val provider: Task[Provider] =
    GoogleProvider.create(TestSigil, GoogleLiveSupport.apiKey.getOrElse("")).singleton

  override protected def modelId: Id[Model] =
    Model.id(sys.env.getOrElse("GOOGLE_TEST_MODEL", "google/gemini-2.5-flash"))

  override def run(testName: Option[String], args: org.scalatest.Args): org.scalatest.Status =
    GoogleLiveSupport.runGated(this, testName, args) {
      super.run(testName, args)
    }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
