package spec

import lightdb.id.Id
import sigil.db.Model
import sigil.provider.Provider
import sigil.provider.google.{Google, GoogleProvider}

class GoogleRequestCoverageSpec extends AbstractRequestCoverageSpec {
  override protected def providerInstance: Provider =
    GoogleProvider(apiKey = "test-placeholder", models = Google.models, sigilRef = TestSigil)
  override protected def modelId: Id[Model] = Model.id("google", "gemini-2.5-flash-lite")
}
