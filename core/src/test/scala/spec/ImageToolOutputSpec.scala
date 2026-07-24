package spec

import lightdb.time.Timestamp
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.conversation.{ContextFrame, FrameBuilder, ToolCallState}
import sigil.event.{ToolInvoke, ToolOutcome}
import sigil.signal.EventState
import sigil.tool.{ImageQuality, ImageToolOutput, ToolName}
import spice.net.URL

/**
 * Sigil bug #280 — framework-shipped [[ImageToolOutput]] lifts the image
 * URL into the rendered frame's `images` list so providers' image-content
 * rendering path (Anthropic / OpenAI / Google) carries the pixels into
 * the agent's next-turn visual context.
 *
 * Before this support, tools producing images had no way to put them
 * into the agent's prompt — the agent received the URL stringified as
 * plain text inside the tool result's content. With the lift, the
 * agent's next turn actually sees the image.
 */
class ImageToolOutputSpec extends AnyWordSpec with Matchers {

  private val convId = sigil.conversation.Conversation.id("img-test-conv")
  private val topicId = sigil.conversation.Topic.id("img-test-topic")
  private val sampleUrl: URL =
    URL.get("sigil://storage/abc123", tldValidation = spice.net.TLDValidation.Off).toOption.get

  private def settledInvoke(out: ImageToolOutput, summary: String = ""): ToolInvoke = ToolInvoke(
    toolName = ToolName("preview_theme"),
    participantId = TestAgent,
    conversationId = convId,
    topicId = topicId,
    state = EventState.Complete,
    outcome = ToolOutcome.Success,
    output = out,
    summary = summary,
    timestamp = Timestamp()
  )

  "FrameBuilder.computeFrame for an ImageToolOutput-settled ToolInvoke" should {

    "lift the image URL into ContextFrame.ToolCall.state.images" in {
      val out = ImageToolOutput(sampleUrl, alt = "preview", text = Some("Preview of /pages/x"))
      val invoke = settledInvoke(out)
      val frame = FrameBuilder.computeFrame(invoke)
      frame shouldBe defined
      val toolFrame = frame.get.asInstanceOf[ContextFrame.ToolCall]
      toolFrame.state match {
        case ToolCallState.Complete(content, images) =>
          content shouldBe "Preview of /pages/x"
          // #382 — FrameBuilder stamps the quality tier onto the URL as the
          // `_q` carrier; the underlying storage URL round-trips via strip.
          images.map(ImageQuality.strip) shouldBe List(sampleUrl)
          images.map(ImageQuality.fromUrl) shouldBe List(ImageQuality.Low)
        case other => fail(s"expected Complete state, got $other")
      }
    }

    "prefer `text` over `alt` over `summary` for the content channel" in {
      def content(out: ImageToolOutput, summary: String = ""): String =
        FrameBuilder.computeFrame(settledInvoke(out, summary)).get
          .asInstanceOf[ContextFrame.ToolCall].state
          .asInstanceOf[ToolCallState.Complete].content

      content(ImageToolOutput(sampleUrl, alt = "alt-fallback", text = Some("explicit-caption"))) shouldBe
        "explicit-caption"
      content(ImageToolOutput(sampleUrl, alt = "alt-text")) shouldBe "alt-text"
      content(ImageToolOutput(sampleUrl), summary = "summary-fallback") shouldBe "summary-fallback"
      content(ImageToolOutput(sampleUrl)) shouldBe "(image)"
    }
  }
}
