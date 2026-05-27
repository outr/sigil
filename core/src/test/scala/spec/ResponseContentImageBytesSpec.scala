package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.provider.{MessageContent, ProviderCall, ProviderMessage}
import sigil.render.{HtmlRenderer, MarkdownRenderer, PlainTextRenderer, SlackMrkdwnRenderer}
import sigil.tool.model.ResponseContent

/**
 * Regression for sigil #296 — `ResponseContent.ImageBytes` carries
 * inline image bytes (PDF page renders, screen captures, tool-produced
 * diagnostics) without forcing the consumer through spice's
 * `data:`-URL parser (which mangles those URIs into garbage). The new
 * case translates directly to the existing wire-layer
 * `MessageContent.ImageBytes`, which every multimodal provider
 * already handles.
 */
class ResponseContentImageBytesSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  // 1x1 transparent PNG — small enough to inline in source, real
  // enough to look like a believable image payload.
  private val tinyPng: String =
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII="

  "ResponseContent.ImageBytes (sigil #296)" should {

    "constructable with mediaType + base64 + optional alt" in Task {
      val withAlt    = ResponseContent.ImageBytes("image/png", tinyPng, Some("tiny test png"))
      val withoutAlt = ResponseContent.ImageBytes("image/jpeg", tinyPng)
      withAlt match {
        case ResponseContent.ImageBytes(mt, b64, alt) =>
          mt shouldBe "image/png"
          b64 shouldBe tinyPng
          alt shouldBe Some("tiny test png")
        case other => fail(s"unexpected case: $other")
      }
      withoutAlt match {
        case ResponseContent.ImageBytes(_, _, alt) => alt shouldBe None
        case other => fail(s"unexpected case: $other")
      }
    }
  }

  "Renderers" should {

    "MarkdownRenderer emits a data: URL `![alt](data:mt;base64,b64)`" in Task {
      val block = ResponseContent.ImageBytes("image/png", tinyPng, Some("alpha"))
      val rendered = MarkdownRenderer.renderBlock(block)
      rendered shouldBe s"![alpha](data:image/png;base64,$tinyPng)"
    }

    "HtmlRenderer emits an inline `<img src=\"data:...;base64,...\">`" in Task {
      val block = ResponseContent.ImageBytes("image/png", tinyPng, Some("alpha"))
      val rendered = HtmlRenderer.renderBlock(block)
      rendered should include (s"src=\"data:image/png;base64,$tinyPng\"")
      rendered should include ("alt=\"alpha\"")
    }

    "PlainTextRenderer falls back to alt text or media-type marker" in Task {
      PlainTextRenderer.renderBlock(
        ResponseContent.ImageBytes("image/png", tinyPng, Some("alpha"))
      ) shouldBe "alpha"
      PlainTextRenderer.renderBlock(
        ResponseContent.ImageBytes("image/png", tinyPng, None)
      ) shouldBe "[image image/png]"
    }

    "SlackMrkdwnRenderer surfaces alt + media-type placeholder (no data: link support)" in Task {
      SlackMrkdwnRenderer.renderBlock(
        ResponseContent.ImageBytes("image/png", tinyPng, Some("page render"))
      ) shouldBe "[page render]"
      SlackMrkdwnRenderer.renderBlock(
        ResponseContent.ImageBytes("image/png", tinyPng, None)
      ) shouldBe "[image (image/png)]"
    }
  }

  "Provider.toMessageContent (sigil #296)" should {

    /** Reflective bridge — `toMessageContent` is private. Tests it
      * through a synthetic Provider via reflection to confirm the
      * wire-layer translation lands on the correct
      * MessageContent.ImageBytes variant (NOT MessageContent.Image
      * with a mangled URL). */
    "translate ResponseContent.ImageBytes → MessageContent.ImageBytes verbatim" in Task {
      val block = ResponseContent.ImageBytes("image/png", tinyPng, Some("alpha"))
      val m = classOf[sigil.provider.Provider]
        .getDeclaredMethod("toMessageContent", classOf[Vector[?]])
      m.setAccessible(true)
      val provider = new _root_.sigil.provider.Provider {
        override def `type`: _root_.sigil.provider.ProviderType = _root_.sigil.provider.ProviderType.OpenAI
        override protected def sigil: _root_.sigil.Sigil = TestSigil
        override def httpRequestFor(input: ProviderCall): rapid.Task[spice.http.HttpRequest] =
          rapid.Task.error(new UnsupportedOperationException)
        override def call(input: ProviderCall): rapid.Stream[_root_.sigil.provider.ProviderEvent] =
          rapid.Stream.empty
      }
      val result = m.invoke(provider, Vector(block)).asInstanceOf[Vector[MessageContent]]
      result should have size 1
      result.head shouldBe a [MessageContent.ImageBytes]
      val ib = result.head.asInstanceOf[MessageContent.ImageBytes]
      ib.mediaType shouldBe "image/png"
      ib.base64 shouldBe tinyPng
      ib.altText shouldBe Some("alpha")
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
