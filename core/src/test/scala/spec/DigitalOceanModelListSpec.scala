package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.AsyncTaskSpec
import sigil.provider.digitalocean.DigitalOcean

/**
 * Live coverage for [[DigitalOcean.refreshModels]] — exercises
 * `GET /v1/models` against DigitalOcean Inference. Gated on
 * `SIGIL_LIVE=1` + `DO_ACCESS_KEY`; skipped cleanly otherwise.
 */
class DigitalOceanModelListSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  override def run(testName: Option[String], args: org.scalatest.Args): org.scalatest.Status =
    DigitalOceanLiveSupport.runGated(this, testName, args) {
      super.run(testName, args)
    }

  "DigitalOcean.refreshModels" should {
    "fetch /v1/models and populate the registry under the digitalocean/ namespace" in {
      DigitalOcean.refreshModels(TestSigil, DigitalOceanLiveSupport.apiKey.getOrElse("")).map { models =>
        models should not be empty
        // Every model is namespaced under digitalocean/.
        models.foreach { m =>
          m._id.value should startWith("digitalocean/")
          m.canonicalSlug should startWith("digitalocean/")
        }
        // Chat-capable hosted models carry real context-length metadata
        // (DO returns it on the list endpoint when applicable).
        val withContext = models.filter(_.contextLength > 0)
        withContext should not be empty
        // The registry should now contain at least one of the chat
        // models we know DO hosts.
        val ids = models.map(_._id.value).toSet
        ids should contain(s"${DigitalOcean.Provider}/kimi-k2.5")
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
