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
 * Sigil #382 — knowledge-preserving image eviction. Under budget
 * pressure the curator drops the pixels of the OLDEST image-bearing
 * frames while keeping each frame's caption (content), so the agent
 * reads what the image showed rather than re-fetching it. Replaces the
 * #289 recency-stubbing that manufactured a re-view loop.
 */
class ImageEvictionSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val testUrl: URL = url"https://example.invalid/img.png"

  private def toolCallFrame(name: String, withImage: Boolean): ContextFrame.ToolCall = {
    val callId = Id[Event](rapid.Unique())
    val state = if (withImage)
      ToolCallState.Complete(content = "rendered caption", images = List(testUrl))
    else
      ToolCallState.Complete(content = "ok", images = Nil)
    ContextFrame.ToolCall(
      toolName       = ToolName.parse(name).fold(sys.error, identity),
      argsJson       = "{}",
      callId         = callId,
      participantId  = TestAgent,
      sourceEventId  = callId,
      visibility     = MessageVisibility.All,
      state          = state
    )
  }

  private def complete(f: ContextFrame): ToolCallState.Complete =
    f.asInstanceOf[ContextFrame.ToolCall].state.asInstanceOf[ToolCallState.Complete]

  "StandardContextCurator.evictOldestImages (sigil #382)" should {

    "drop the oldest N image pixels while preserving their captions" in Task {
      val frames: Vector[ContextFrame] = Vector(
        toolCallFrame("preview_theme", withImage = true),  // oldest — evicted
        toolCallFrame("preview_theme", withImage = true),  // kept
        toolCallFrame("preview_theme", withImage = true)   // kept
      )
      val result = StandardContextCurator.evictOldestImages(frames, evictCount = 1)
      result.size shouldBe 3
      // Oldest: pixels gone, caption preserved (no re-fetch needed).
      complete(result(0)).images shouldBe Nil
      complete(result(0)).content shouldBe "rendered caption"
      // Newer two: untouched.
      complete(result(1)).images shouldBe List(testUrl)
      complete(result(2)).images shouldBe List(testUrl)
    }

    "evict oldest-first when evictCount = 2 of 5" in Task {
      val frames: Vector[ContextFrame] = (1 to 5).map(_ => toolCallFrame("preview_theme", withImage = true)).toVector
      val result = StandardContextCurator.evictOldestImages(frames, evictCount = 2)
      result.take(2).foreach(f => complete(f).images shouldBe Nil)
      result.takeRight(3).foreach(f => complete(f).images shouldBe List(testUrl))
      succeed
    }

    "no-op when evictCount <= 0" in Task {
      val frames: Vector[ContextFrame] = (1 to 3).map(_ => toolCallFrame("preview_theme", withImage = true)).toVector
      StandardContextCurator.evictOldestImages(frames, evictCount = 0) shouldBe frames
    }

    "leave non-image ToolCall frames untouched" in Task {
      val frames: Vector[ContextFrame] = Vector(
        toolCallFrame("preview_theme", withImage = true),     // oldest image — evicted
        toolCallFrame("read_theme_file", withImage = false),  // no image — untouched
        toolCallFrame("preview_theme", withImage = true)      // kept
      )
      val result = StandardContextCurator.evictOldestImages(frames, evictCount = 1)
      // Non-image frame untouched.
      complete(result(1)).images shouldBe Nil
      complete(result(1)).content shouldBe "ok"
      // First (oldest image) evicted, caption kept.
      complete(result(0)).images shouldBe Nil
      complete(result(0)).content shouldBe "rendered caption"
      // Third (newer image) preserved.
      complete(result(2)).images shouldBe List(testUrl)
    }

    "imageFrameCount counts only image-bearing Complete frames" in Task {
      val frames: Vector[ContextFrame] = Vector(
        toolCallFrame("preview_theme", withImage = true),
        toolCallFrame("read_theme_file", withImage = false),
        toolCallFrame("preview_theme", withImage = true)
      )
      StandardContextCurator.imageFrameCount(frames) shouldBe 2
      StandardContextCurator.imageFrameCount(StandardContextCurator.evictOldestImages(frames, 2)) shouldBe 0
    }
  }

  "StandardContextCurator.evictImagesToFit (sigil #382)" should {

    "evict the fewest oldest images needed to satisfy `fits`" in Task {
      val frames: Vector[ContextFrame] = (1 to 4).map(_ => toolCallFrame("preview_theme", withImage = true)).toVector
      // Fits once at most 2 images remain (i.e. >= 2 evicted).
      val result = StandardContextCurator.evictImagesToFit(
        frames,
        candidate => StandardContextCurator.imageFrameCount(candidate) <= 2
      )
      StandardContextCurator.imageFrameCount(result) shouldBe 2
      // Stops at the minimum — the two newest images stay.
      complete(result(2)).images shouldBe List(testUrl)
      complete(result(3)).images shouldBe List(testUrl)
    }

    "no-op when already fitting" in Task {
      val frames: Vector[ContextFrame] = (1 to 3).map(_ => toolCallFrame("preview_theme", withImage = true)).toVector
      StandardContextCurator.evictImagesToFit(frames, _ => true) shouldBe frames
    }

    "evicts every image when nothing fits" in Task {
      val frames: Vector[ContextFrame] = (1 to 3).map(_ => toolCallFrame("preview_theme", withImage = true)).toVector
      val result = StandardContextCurator.evictImagesToFit(frames, _ => false)
      StandardContextCurator.imageFrameCount(result) shouldBe 0
      // Captions survive even in the fully-evicted result.
      result.foreach(f => complete(f).content shouldBe "rendered caption")
      succeed
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
