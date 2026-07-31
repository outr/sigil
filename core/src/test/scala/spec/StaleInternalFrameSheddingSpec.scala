package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.conversation.compression.StandardContextCurator
import sigil.conversation.{ContextFrame, ToolCallState}
import sigil.event.Event
import sigil.tool.ToolName

/**
 * Sigil #385 — consumed framework-internal diagnostic frames
 * (`_stall_detected`, `_refusal_challenge`, …) are transient nudges; only
 * the most recent should reach the prompt. Older ones, re-sent every turn,
 * compound the loop they warn against (observed live: 200+ accumulated
 * `_stall_detected` copies in a single turn's context).
 */
class StaleInternalFrameSheddingSpec extends AnyWordSpec with Matchers {

  private def tc(name: String, internal: Boolean, content: String): ContextFrame.ToolCall = {
    val id = Id[Event](rapid.Unique())
    ContextFrame.ToolCall(
      toolName      = ToolName.parse(name).fold(sys.error, identity),
      argsJson      = "{}",
      callId        = id,
      participantId = TestAgent,
      sourceEventId = id,
      internal      = internal,
      state         = ToolCallState.Complete(content, Nil)
    )
  }

  private def internalContents(frames: Vector[ContextFrame]): List[String] =
    frames.collect { case t: ContextFrame.ToolCall if t.internal =>
      t.state.asInstanceOf[ToolCallState.Complete].content
    }.toList

  "StandardContextCurator.dropStaleInternalFrames (sigil #385)" should {

    "keep only the most-recent internal diagnostic frame" in {
      val frames = Vector[ContextFrame](
        tc("read_file", internal = false, "a"),
        tc("_stall_detected", internal = true, "nudge-1"),
        tc("read_file", internal = false, "b"),
        tc("_stall_detected", internal = true, "nudge-2"),
        tc("_stall_detected", internal = true, "nudge-3")
      )
      val result = StandardContextCurator.dropStaleInternalFrames(frames)
      // Only the latest internal frame survives.
      internalContents(result) shouldBe List("nudge-3")
      // Non-internal frames are all preserved, in order.
      result.collect { case t: ContextFrame.ToolCall if !t.internal => t.toolName.value } shouldBe
        List("read_file", "read_file")
    }

    "preserve relative order of the surviving frames" in {
      val frames = Vector[ContextFrame](
        tc("_refusal_challenge", internal = true, "old"),
        tc("grep", internal = false, "g1"),
        tc("write_file", internal = false, "w1"),
        tc("_stall_detected", internal = true, "latest")
      )
      val result = StandardContextCurator.dropStaleInternalFrames(frames)
      result.map { case t: ContextFrame.ToolCall => t.state.asInstanceOf[ToolCallState.Complete].content } shouldBe
        List("g1", "w1", "latest")
    }

    "no-op when there is at most one internal frame" in {
      val none = Vector[ContextFrame](tc("read_file", internal = false, "a"), tc("grep", internal = false, "b"))
      StandardContextCurator.dropStaleInternalFrames(none) shouldBe none
      val one = none :+ tc("_stall_detected", internal = true, "only")
      StandardContextCurator.dropStaleInternalFrames(one) shouldBe one
    }
  }
}
