package spec

import lightdb.id.Id
import rapid.Task
import sigil.db.Model
import sigil.provider.Provider
import sigil.provider.digitalocean.DigitalOceanResponsesProvider

/**
 * Live coverage for [[DigitalOceanResponsesProvider]] — DO's
 * `/v1/responses` surface routed through a reconfigured
 * [[sigil.provider.openai.OpenAIProvider]]. Gated on `SIGIL_LIVE=1`
 * + `DO_ACCESS_KEY`.
 */
class DigitalOceanResponsesProviderSpec extends AbstractProviderSpec {
  override protected val provider: Task[Provider] =
    DigitalOceanResponsesProvider.create(TestSigil, DigitalOceanLiveSupport.apiKey.getOrElse("")).singleton

  override protected def modelId: Id[Model] =
    Model.id(sys.env.getOrElse("DIGITALOCEAN_RESPONSES_TEST_MODEL", "digitalocean/kimi-k2.5"))

  override def run(testName: Option[String], args: org.scalatest.Args): org.scalatest.Status =
    DigitalOceanLiveSupport.runGated(this, testName, args) {
      super.run(testName, args)
    }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
