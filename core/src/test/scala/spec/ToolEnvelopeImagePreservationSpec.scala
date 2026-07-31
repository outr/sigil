package spec

import fabric.rw.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.TurnContext
import sigil.conversation.{Conversation, ConversationView, ContextFrame, ToolCallState, TopicEntry, TurnInput}
import sigil.event.{Event, ToolInvoke}
import sigil.signal.{EventState, ToolDelta}
import sigil.tool.{DiscoverySpec, Effect, ImageToolOutput, MutationTargeting, Resolution, Tool, ToolContext, ToolIO, ToolInput, ToolName, ToolProfile, ToolSpec}

/**
 * Envelope image preservation — an [[ImageToolOutput]] whose rendered
 * form overflows `inlineContentThreshold` (an oversized caption) keeps
 * its typed output on the invoke, so the image still reaches the
 * agent's visual channel: the settled frame carries the image URL in
 * its `images` list while the prose channel renders the bounded
 * summary. Before the envelope, bounding type-laundered the output to
 * `TextToolOutput` and the image was silently dropped.
 */
class ToolEnvelopeImagePreservationSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val convId = Conversation.id(s"img-envelope-${rapid.Unique()}")
  private val ctx: TurnContext = TurnContext(
    sigil        = TestSigil,
    chain        = List(TestUser),
    conversation = Conversation(topics = List(TopicEntry(TestTopicId, "t", "t")), _id = convId),
    turnInput    = TurnInput(ConversationView(conversationId = convId)),
    model        = TestSigil.defaultTestModel
  )

  private val imageUrl = spice.net.URL.parse("https://example.com/preview.png")
  private val hugeCaption = "caption " * 8000

  private case object BigCaptionImageTool extends Tool {
    type Input  = ImageProbeInput
    type Output = ImageToolOutput
    val io: ToolIO[ImageProbeInput, ImageToolOutput] = ToolIO.derived[ImageProbeInput, ImageToolOutput]
    val spec: ToolSpec = ToolSpec(
      name = ToolName("big_caption_image"),
      description = "Returns an image whose caption far exceeds the inline threshold.",
      profile = ToolProfile(effect = Effect.Mutating(MutationTargeting.none)),
      discovery = DiscoverySpec(keywords = Set("test", "image", "overflow"))
    )
    protected def resolve: Resolution[Input, Output] =
      Resolution.Simple((_, _) => Task.pure(ImageToolOutput(url = imageUrl, alt = "preview", text = Some(hugeCaption))))
  }

  "An ImageToolOutput with an oversized caption" should {
    "keep its image in the agent's visual channel while the prose channel is bounded" in {
      val invokeId = Event.id()
      BigCaptionImageTool.execute(ImageProbeInput(), ctx, invokeId).toList.map { signals =>
        val delta = signals.collect { case d: ToolDelta if d.state.contains(EventState.Complete) => d }.head
        // Typed output preserved — still an ImageToolOutput.
        val img = delta.output.collect { case i: ImageToolOutput => i }
        img.map(_.url) shouldBe Some(imageUrl)
        delta.overflow shouldBe defined
        // The settled frame delivers the image AND a bounded prose channel.
        val invoke = ToolInvoke(
          toolName       = BigCaptionImageTool.name,
          participantId  = TestUser,
          conversationId = convId,
          topicId        = ctx.conversation.currentTopicId,
          _id            = invokeId
        )
        val settled = delta.apply(invoke)
        val frame = sigil.conversation.FrameBuilder.computeFrame(settled)
        val state = frame.collect { case tc: ContextFrame.ToolCall => tc.state }
        state.collect { case ToolCallState.Complete(content, images) => (content, images) } match {
          case Some((content, images)) =>
            images should not be empty
            images.head.toString should include("example.com/preview.png")
            content.length should be < hugeCaption.length
          case None => fail(s"expected a Complete ToolCall frame, got $frame")
        }
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}

case class ImageProbeInput() extends ToolInput derives RW
