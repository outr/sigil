package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.conversation.compression.StandardContextCurator
import sigil.conversation.{ContextFrame, ToolCallState}
import sigil.event.{Event, MessageVisibility}
import sigil.tool.ToolName
import spice.net.*

/**
 * Regression for sigil bug #289 — `ImageToolOutput`-bearing ToolCall
 * frames in the conversation history are superseded by the most-
 * recent `keepRecentImages`; older frames render with empty
 * `images` plus a textual stub.
 */
class ImageSupersessionSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val testUrl: URL = url"https://example.invalid/img.png"

  private def toolCallFrame(name: String, withImage: Boolean): ContextFrame.ToolCall = {
    val callId = Id[Event](rapid.Unique())
    val state = if (withImage)
      ToolCallState.Complete(content = "rendered", images = List(testUrl))
    else
      ToolCallState.Complete(content = "ok", images = Nil)
    ContextFrame.ToolCall(
      toolName       = ToolName(name),
      argsJson       = "{}",
      callId         = callId,
      participantId  = TestAgent,
      sourceEventId  = callId,
      visibility     = MessageVisibility.All,
      state          = state
    )
  }

  "StandardContextCurator.supersedeOlderImages (sigil #289)" should {

    "keep the most-recent N image-bearing frames and stub older ones" in Task {
      val frames: Vector[ContextFrame] = Vector(
        toolCallFrame("preview_theme", withImage = true),  // old — will stub
        toolCallFrame("preview_theme", withImage = true),  // old — will stub
        toolCallFrame("preview_theme", withImage = true)   // newest — kept
      )
      val result = StandardContextCurator.supersedeOlderImages(frames, keepRecent = 1)
      result.size shouldBe 3
      // Older two: images cleared, content stubbed.
      val first = result(0).asInstanceOf[ContextFrame.ToolCall]
      val second = result(1).asInstanceOf[ContextFrame.ToolCall]
      val third = result(2).asInstanceOf[ContextFrame.ToolCall]
      first.state.asInstanceOf[ToolCallState.Complete].images shouldBe Nil
      first.state.asInstanceOf[ToolCallState.Complete].content should include ("image suppressed")
      second.state.asInstanceOf[ToolCallState.Complete].images shouldBe Nil
      // Newest: original images + content preserved.
      third.state.asInstanceOf[ToolCallState.Complete].images shouldBe List(testUrl)
      third.state.asInstanceOf[ToolCallState.Complete].content shouldBe "rendered"
    }

    "keep the most-recent 2 when keepRecent = 2" in Task {
      val frames: Vector[ContextFrame] = (1 to 5).map(_ => toolCallFrame("preview_theme", withImage = true)).toVector
      val result = StandardContextCurator.supersedeOlderImages(frames, keepRecent = 2)
      // First 3 stubbed; last 2 preserved.
      result.take(3).foreach { f =>
        f.asInstanceOf[ContextFrame.ToolCall].state.asInstanceOf[ToolCallState.Complete].images shouldBe Nil
      }
      result.takeRight(2).foreach { f =>
        f.asInstanceOf[ContextFrame.ToolCall].state.asInstanceOf[ToolCallState.Complete].images shouldBe List(testUrl)
      }
      succeed
    }

    "no-op when there are fewer image-bearing frames than keepRecent" in Task {
      val frames: Vector[ContextFrame] = Vector(toolCallFrame("preview_theme", withImage = true))
      val result = StandardContextCurator.supersedeOlderImages(frames, keepRecent = 5)
      result shouldBe frames
    }

    "leave non-image ToolCall frames untouched" in Task {
      val frames: Vector[ContextFrame] = Vector(
        toolCallFrame("preview_theme", withImage = true),     // image — older, will stub
        toolCallFrame("read_theme_file", withImage = false),  // no image — untouched
        toolCallFrame("preview_theme", withImage = true)      // image — kept
      )
      val result = StandardContextCurator.supersedeOlderImages(frames, keepRecent = 1)
      val second = result(1).asInstanceOf[ContextFrame.ToolCall]
      // Non-image frame untouched.
      second.state.asInstanceOf[ToolCallState.Complete].images shouldBe Nil
      second.state.asInstanceOf[ToolCallState.Complete].content shouldBe "ok"
      // First (image) stubbed.
      result(0).asInstanceOf[ContextFrame.ToolCall].state.asInstanceOf[ToolCallState.Complete].images shouldBe Nil
      // Third (latest image) preserved.
      result(2).asInstanceOf[ContextFrame.ToolCall].state.asInstanceOf[ToolCallState.Complete].images shouldBe List(testUrl)
    }

    "keepRecent = Int.MaxValue disables supersession entirely" in Task {
      val frames: Vector[ContextFrame] = (1 to 10).map(_ => toolCallFrame("preview_theme", withImage = true)).toVector
      val result = StandardContextCurator.supersedeOlderImages(frames, keepRecent = Int.MaxValue)
      result shouldBe frames
    }

    "keepRecent = 0 stubs every image-bearing frame including the latest" in Task {
      val frames: Vector[ContextFrame] = Vector(
        toolCallFrame("preview_theme", withImage = true),
        toolCallFrame("preview_theme", withImage = true)
      )
      val result = StandardContextCurator.supersedeOlderImages(frames, keepRecent = 0)
      result.foreach { f =>
        f.asInstanceOf[ContextFrame.ToolCall].state.asInstanceOf[ToolCallState.Complete].images shouldBe Nil
      }
      succeed
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
