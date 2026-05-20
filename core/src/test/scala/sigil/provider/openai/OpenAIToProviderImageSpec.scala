package sigil.provider.openai

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.provider.ProviderImage

/**
 * Classification coverage for [[OpenAIProvider.toProviderImage]] — the
 * helper every image-generation parser site uses to turn a raw image
 * reference into a typed [[ProviderImage]] without ever building a
 * `data:` URL.
 */
class OpenAIToProviderImageSpec extends AnyWordSpec with Matchers {
  "OpenAIProvider.toProviderImage" should {
    "classify an https value as a Hosted URL" in {
      OpenAIProvider.toProviderImage("https://example.com/generated.png") match {
        case Some(ProviderImage.Hosted(url)) =>
          url.toString should include("example.com")
          url.toString should include("generated.png")
        case other => fail(s"expected Hosted, got $other")
      }
    }

    "classify a bare base64 payload as Inline image bytes" in {
      OpenAIProvider.toProviderImage("iVBORw0KGgoAAAANSUhEUg==") match {
        case Some(ProviderImage.Inline(b64, contentType)) =>
          b64 shouldBe "iVBORw0KGgoAAAANSUhEUg=="
          contentType shouldBe "image/png"
        case other => fail(s"expected Inline, got $other")
      }
    }

    "decode a data URL into Inline bytes with its declared content type" in {
      OpenAIProvider.toProviderImage("data:image/jpeg;base64,QUJD") match {
        case Some(ProviderImage.Inline(b64, contentType)) =>
          b64 shouldBe "QUJD"
          contentType shouldBe "image/jpeg"
        case other => fail(s"expected Inline, got $other")
      }
    }

    "return None for an empty or blank reference" in {
      OpenAIProvider.toProviderImage("") shouldBe None
      OpenAIProvider.toProviderImage("   ") shouldBe None
    }
  }
}
