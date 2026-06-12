package spec

import fabric.io.JsonParser
import lightdb.id.Id
import sigil.db.Model
import sigil.provider.Provider
import sigil.provider.google.{Google, GoogleProvider}

class GoogleRequestCoverageSpec extends AbstractRequestCoverageSpec {
  override protected def providerInstance: Provider =
    GoogleProvider(apiKey = "test-placeholder", sigilRef = TestSigil)
  override protected def modelId: Id[Model] = Model.id("google", "gemini-2.5-flash-lite")
    TestSigil.testModel(modelId)

  // Gemini's wire uses `contents[].role` (user/model), not `messages[].role`.
  override protected def lastWireRole(body: String): Option[String] =
    JsonParser(body).get("contents").flatMap(_.asVector.lastOption)
      .flatMap(_.get("role")).map(_.asString)

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
