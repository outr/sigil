package spec

import lightdb.id.Id
import sigil.db.Model
import sigil.provider.Provider
import sigil.provider.digitalocean.{DigitalOcean, DigitalOceanProvider}

class DigitalOceanRequestCoverageSpec extends AbstractRequestCoverageSpec {
  override protected def providerInstance: Provider =
    DigitalOceanProvider(apiKey = "sk-test-placeholder", sigilRef = TestSigil)
  override protected def modelId: Id[Model] = Model.id(DigitalOcean.Provider, "kimi-k2.5")

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
