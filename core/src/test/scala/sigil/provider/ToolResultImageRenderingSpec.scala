package sigil.provider

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.conversation.{ContextFrame, Conversation, FrameBuilder, Topic}
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

    "route a Tool-role Message's image content into ContextFrame.ToolResult.images, not toString" in {
      val callId = Id[Event]("tool-call-1")
      val toolMessage = Message(
        participantId  = TestAgent,
        conversationId = Conversation.id("conv-1"),
        topicId        = Topic.id("topic-1"),
        role           = MessageRole.Tool,
        content        = Vector(
          ResponseContent.Text("preview ready"),
          ResponseContent.Image(imageUrl, Some("storefront preview"))
        ),
        origin         = Some(callId),
        state          = EventState.Complete
      )
      FrameBuilder.computeFrame(toolMessage) match {
        case Some(tr: ContextFrame.ToolResult) =>
          tr.images shouldBe List(imageUrl)
          tr.content should include("[image: storefront preview]")
          tr.content should not include "Image("
        case other => fail(s"expected ContextFrame.ToolResult, got $other")
      }
    }

    "emit tool-result images as a follow-up user message in renderFrames" in {
      val callId = Id[Event]("tool-call-2")
      val toolCall = ContextFrame.ToolCall(
        toolName      = ToolName("preview_theme"),
        argsJson      = "{}",
        callId        = callId,
        participantId = TestAgent,
        sourceEventId = Id[Event]("tc-2")
      )
      val toolResult = ContextFrame.ToolResult(
        callId        = callId,
        content       = "preview ready",
        sourceEventId = Id[Event]("tr-2"),
        images        = List(imageUrl)
      )
      val rendered = FakeProvider.renderFrames(Vector(toolCall, toolResult), Some(TestAgent))
      val userImageUrls = rendered.collect {
        case ProviderMessage.User(content) =>
          content.collect { case i: MessageContent.Image => i.url }
      }.flatten
      userImageUrls shouldBe List(imageUrl)
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
