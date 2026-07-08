package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.conversation.{ContextFrame, Conversation, FrameBuilder, Topic}
import sigil.event.{Event, Message, MessageVisibility}
import sigil.provider.{MessageContent, Provider, ProviderMessage}
import sigil.signal.EventState
import sigil.tool.model.ResponseContent
import spice.net.url

/**
 * Sigil #405 — a user-uploaded image attached to a conversation `Message`
 * (referenced as `ResponseContent.Image(storageUrl)`) must reach the vision
 * channel first-class, the same way a tool's `ImageToolOutput` already does.
 * Before the fix the message became a text-only `ContextFrame.Text` and its
 * image collapsed to a `[image: …]` placeholder, so the model never saw the
 * pixels.
 */
class UserMessageImageVisionSpec extends AnyWordSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val imgUrl = url"sigil://storage/abc123"

  private object Probe extends Provider {
    override def `type` = _root_.sigil.provider.ProviderType.LlamaCpp
    override def models = Nil
    override protected def sigil = TestSigil
    override def httpRequestFor(input: _root_.sigil.provider.ProviderCall) =
      rapid.Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: _root_.sigil.provider.ProviderCall) = rapid.Stream.empty
    def renderFor(frames: Vector[ContextFrame], agentId: _root_.sigil.participant.ParticipantId): Vector[ProviderMessage] =
      renderFrames(frames, Some(agentId))
  }

  "FrameBuilder (#405)" should {
    "carry a user Message's ResponseContent.Image URLs onto the Text frame" in {
      val m = Message(
        participantId  = TestUser,
        conversationId = Conversation.id("img-conv"),
        topicId        = Id[Topic]("t"),
        content        = Vector(ResponseContent.Text("describe this image"), ResponseContent.Image(imgUrl)),
        state          = EventState.Complete
      )
      FrameBuilder.computeFrame(m) match {
        case Some(t: ContextFrame.Text) =>
          t.images shouldBe List(imgUrl)
          // Text still reduces the image to a placeholder — no base64 in frames.
          t.content should include ("describe this image")
        case other => fail(s"expected a Text frame, got $other")
      }
    }
  }

  "Provider.renderFrames (#405)" should {

    "lift a user Text frame's images into MessageContent.Image blocks on the user turn" in {
      val frame = ContextFrame.Text(
        content = "describe this image",
        participantId = TestUser,
        sourceEventId = Id[Event]("e1"),
        visibility = MessageVisibility.All,
        images = List(imgUrl)
      )
      val msgs = Probe.renderFor(Vector(frame), TestAgent)
      val user = msgs.collectFirst { case u: ProviderMessage.User => u }
        .getOrElse(fail(s"expected a User message, got ${msgs.map(_.getClass.getSimpleName)}"))
      withClue(s"user content = ${user.content}\n") {
        val images = user.content.collect { case i: MessageContent.Image => i }
        images should have size 1
        images.head.url shouldBe imgUrl
        // Caption stays adjacent to the image (#391).
        user.content.collectFirst { case t: MessageContent.Text => t.text }.get should include ("describe")
      }
    }

    "leave an agent-authored Text frame's images alone (agents don't upload)" in {
      val frame = ContextFrame.Text(
        content = "here you go",
        participantId = TestAgent,
        sourceEventId = Id[Event]("e2"),
        visibility = MessageVisibility.All,
        images = List(imgUrl)
      )
      val msgs = Probe.renderFor(Vector(frame), TestAgent)
      msgs.collectFirst { case a: ProviderMessage.Assistant => a }.isDefined shouldBe true
      msgs.flatMap {
        case u: ProviderMessage.User => u.content.collect { case i: MessageContent.Image => i }
        case _                       => Nil
      } shouldBe empty
    }

    "render a user Text frame with no images as a plain user message (unchanged)" in {
      val frame = ContextFrame.Text(
        content = "just text",
        participantId = TestUser,
        sourceEventId = Id[Event]("e3"),
        visibility = MessageVisibility.All
      )
      val msgs = Probe.renderFor(Vector(frame), TestAgent)
      val user = msgs.collectFirst { case u: ProviderMessage.User => u }.getOrElse(fail("expected a User message"))
      user.content.collect { case i: MessageContent.Image => i } shouldBe empty
      user.content.collectFirst { case t: MessageContent.Text => t.text }.get shouldBe "just text"
    }
  }
}
