package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.conversation.{Conversation, ContextFrame, FrameBuilder}
import sigil.event.{Message, MessageRole}
import sigil.signal.EventState
import sigil.tool.model.{ResponseContent, SelectOption}

/**
 * Regression: structured [[ResponseContent]] blocks (`ItemList`, `Options`,
 * `Heading`, `Link`, …) in an assistant Message must render to clean text in the
 * prompt frame, NOT a raw case-class `toString`. A partial match in
 * FrameBuilder's content renderer was leaking `ItemList(List(...),false)` and
 * `Options(...,List(SelectOption(...)))` into the assistant history — observed
 * poisoning the Sage wire log — which teaches the model to emit the same garbage.
 */
class StructuredContentFrameSpec extends AnyWordSpec with Matchers {

  private val convId = Conversation.id("structured-content")
  private val topicId = sigil.conversation.Topic.id("t")

  private def frameText(content: Vector[ResponseContent]): String =
    FrameBuilder.computeFrame(
      Message(
        participantId = TestAgent,
        conversationId = convId,
        topicId = topicId,
        role = MessageRole.Standard,
        content = content,
        state = EventState.Complete
      )
    ).collect { case t: ContextFrame.Text => t.content }
      .getOrElse(fail("expected a ContextFrame.Text"))

  "FrameBuilder content rendering" should {
    "render an ItemList as a clean list, not ItemList(List(...)) toString" in {
      val text = frameText(Vector(
        ResponseContent.Text("I can help with:"),
        ResponseContent.ItemList(List("Writing Scala", "Refactoring", "Testing"), ordered = false),
        ResponseContent.Text("What next?")
      ))
      withClue(s"frame text:\n$text\n") {
        text should not include "ItemList("
        text should not include ",false)"
        text should include("- Writing Scala")
        text should include("- Refactoring")
        text should include("What next?")
      }
    }

    "render an Options block as its prompt + choices, not Options(...) toString" in {
      val text = frameText(Vector(
        ResponseContent.Options(
          prompt = "Pick one:",
          options = List(SelectOption("Load state", "load_state"), SelectOption("Start Metals", "start_metals"))
        )
      ))
      withClue(s"frame text:\n$text\n") {
        text should not include "Options("
        text should not include "SelectOption("
        text should include("Pick one:")
        text should include("Load state")
        text should include("Start Metals")
      }
    }

    "render Heading and Link without toString leakage" in {
      val text = frameText(Vector(
        ResponseContent.Heading("Summary"),
        ResponseContent.Link(spice.net.URL.parse("https://example.com/x"), "the doc")
      ))
      withClue(s"frame text:\n$text\n") {
        text should not include "Heading("
        text should not include "Link("
        text should include("Summary")
        text should include("the doc")
      }
    }
  }
}
