package sigil.provider

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.conversation.{ContextFrame, Conversation, FrameBuilder, ToolCallState, Topic}
import sigil.db.Model
import sigil.event.{Event, Message, MessageRole}
import sigil.signal.EventState
import sigil.tool.ToolName
import sigil.tool.model.ResponseContent
import spec.{TestAgent, TestSigil}
import spice.http.HttpRequest

/**
 * A Tool-role Message's image content must reach the model as a real
 * image, not the case-class `toString` of `ResponseContent.Image`.
 * FrameBuilder routes the image URLs into `ContextFrame.ToolResult.images`,
 * and renderFrames emits them as a follow-up user message.
 */
class ToolResultImageRenderingSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val imageUrl = spice.net.URL.get("sigil://storage/tool-result-img",
    tldValidation = spice.net.TLDValidation.Off).toOption.get

  private object FakeProvider extends Provider {
    override def `type`: ProviderType = ProviderType.OpenAI
    override def models: List[Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] =
      Stream.emits(List(ProviderEvent.Done(StopReason.Complete)))
  }

  "tool-result image rendering" should {

    "route a Tool-role Message's image content into ToolCallState.Complete.images via appendFor" in {
      // Sigil #261 — Tool-role events fold into the parent ToolCall's
      // state; the result content + image URLs are carried on
      // `ToolCallState.Complete(content, images)`. FrameBuilder
      // computes this via `toolResultPayload` when folding the result.
      val invoke = sigil.event.ToolInvoke(
        toolName       = ToolName("preview_theme"),
        participantId  = TestAgent,
        conversationId = Conversation.id("conv-1"),
        topicId        = Topic.id("topic-1"),
        state          = EventState.Active
      )
      val toolMessage = Message(
        participantId  = TestAgent,
        conversationId = Conversation.id("conv-1"),
        topicId        = Topic.id("topic-1"),
        role           = MessageRole.Tool,
        content        = Vector(
          ResponseContent.Text("preview ready"),
          ResponseContent.Image(imageUrl, Some("storefront preview"))
        ),
        origin         = Some(invoke._id),
        state          = EventState.Complete
      )
      // FrameBuilder.appendFor skips Active events, so seed the starter
      // vector with a ToolCall(Active) frame the Tool-role Message can
      // fold into. Mirrors the Vector-projection's actual shape at the
      // moment the result lands.
      val starter = Vector[ContextFrame](ContextFrame.ToolCall(
        toolName = invoke.toolName,
        argsJson = "{}",
        callId = invoke._id,
        participantId = invoke.participantId,
        sourceEventId = invoke._id,
        state = ToolCallState.Active
      ))
      val frames = FrameBuilder.appendFor(starter, toolMessage)
      frames should have size 1
      val tc = frames.head.asInstanceOf[ContextFrame.ToolCall]
      tc.state match {
        case ToolCallState.Complete(content, images) =>
          images shouldBe List(imageUrl)
          content should include ("[image: storefront preview]")
          content should not include "Image("
        case other => fail(s"expected ToolCallState.Complete, got $other")
      }
    }

    "emit tool-result images as a follow-up user message in renderFrames" in {
      val callId = Id[Event]("tool-call-2")
      val toolCall = ContextFrame.ToolCall(
        toolName      = ToolName("preview_theme"),
        argsJson      = "{}",
        callId        = callId,
        participantId = TestAgent,
        sourceEventId = Id[Event]("tc-2"),
        state         = ToolCallState.Complete("preview ready", List(imageUrl))
      )
      val rendered = FakeProvider.renderFrames(Vector(toolCall), Some(TestAgent))
      val userImageUrls = rendered.collect {
        case ProviderMessage.User(content) =>
          content.collect { case i: MessageContent.Image => i.url }
      }.flatten
      userImageUrls shouldBe List(imageUrl)
    }

    "anchor the image to its caption — caption text adjacent, before the image (sigil #391)" in {
      val callId = Id[Event]("tool-call-3")
      val caption = "Store file gid://shopify/MediaImage/123 — Huron Body collection hero"
      val toolCall = ContextFrame.ToolCall(
        toolName      = ToolName("view_file"),
        argsJson      = "{}",
        callId        = callId,
        participantId = TestAgent,
        sourceEventId = Id[Event]("tc-3"),
        state         = ToolCallState.Complete(caption, List(imageUrl))
      )
      val rendered = FakeProvider.renderFrames(Vector(toolCall), Some(TestAgent))
      // The image-bearing user message must NOT be an anonymous bare image —
      // it carries the caption text immediately before the image so the model
      // can map image→gid/label in a multi-image context.
      val imageUserMsg = rendered.collectFirst {
        case ProviderMessage.User(content) if content.exists(_.isInstanceOf[MessageContent.Image]) => content
      }.getOrElse(fail("no image-bearing user message"))
      imageUserMsg.head shouldBe MessageContent.Text(caption)
      imageUserMsg(1) shouldBe a[MessageContent.Image]
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
