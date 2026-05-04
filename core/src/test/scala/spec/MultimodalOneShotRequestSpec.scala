package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.db.Model
import sigil.provider.OneShotRequest
import sigil.provider.openai.OpenAIProvider
import sigil.tool.model.ResponseContent
import spice.net.*

/**
 * Regression for bug #43 — `OneShotRequest.userContent` (multimodal
 * input vector) must reach the wire so vision-capable models like
 * GPT-4o receive image content alongside text. Without it, callers
 * have to bypass the provider abstraction or build a heavyweight
 * `ConversationRequest`; widge-server's PdfVectorExtractor needs
 * this for page-image rewrite.
 *
 * Asserts the OpenAI Responses API payload (the most common
 * multimodal target) contains both `input_text` and `input_image`
 * blocks for the user role. Other providers wire image content
 * through the same `ProviderMessage.User(blocks)` path; their own
 * coverage specs assert the same shape if they support multimodal.
 */
class MultimodalOneShotRequestSpec extends AnyWordSpec with Matchers {

  TestSigil.initFor(getClass.getSimpleName)

  private val provider = OpenAIProvider(apiKey = "sk-test-placeholder", sigilRef = TestSigil)
  private val modelId: Id[Model] = Model.id("openai", "gpt-4o")

  private def bodyOf(req: OneShotRequest): String =
    provider.requestConverter(req).sync().content match {
      case Some(c: spice.http.content.StringContent) => c.value
      case _ => ""
    }

  // Tiny base64 so the URL stays a unique substring marker but the
  // bytes are valid for `data:`-URL parsers.
  private val pngDataUrl = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkAAIAAAoAAv/lxKUAAAAASUVORK5CYII="

  "OneShotRequest.userContent" should {

    "carry an image alongside text into the OpenAI Responses payload" in {
      val req = OneShotRequest(
        modelId      = modelId,
        systemPrompt = "You describe images.",
        userPrompt   = "",
        userContent  = Vector(
          ResponseContent.Text("What's in this image?"),
          ResponseContent.Image(url = URL.get(pngDataUrl, tldValidation = TLDValidation.Off).toOption.get)
        )
      )
      val body = bodyOf(req)
      body should include("What's in this image?")
      body should include("input_text")
      body should include("input_image")
      // The data-URL content survives onto the wire; spice's URL
      // parser mangles the `data:`-scheme prefix at toString time
      // (it's not a real https URL), but the base64 payload — the
      // load-bearing part — is preserved intact for the model.
      body should include("iVBORw0KGgo")
    }

    "fall back to userPrompt when userContent is empty (text-only legacy path)" in {
      val req = OneShotRequest(
        modelId      = modelId,
        systemPrompt = "Concise.",
        userPrompt   = "What is two plus two?"
      )
      val body = bodyOf(req)
      body should include("What is two plus two?")
      body should include("input_text")
      body shouldNot include("input_image")
    }

    "render structured ResponseContent (Code / Markdown) as text content blocks" in {
      val req = OneShotRequest(
        modelId      = modelId,
        systemPrompt = "Echo my code.",
        userPrompt   = "",
        userContent  = Vector(
          ResponseContent.Code("println(42)", Some("scala")),
          ResponseContent.Markdown("Some _emphasized_ text.")
        )
      )
      val body = bodyOf(req)
      // Code and Markdown both flow as input_text — no image blocks.
      body should include("input_text")
      body shouldNot include("input_image")
      body should include("println(42)")
      body should include("emphasized")
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
