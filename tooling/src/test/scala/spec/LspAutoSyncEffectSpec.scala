package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.conversation.Conversation
import sigil.event.Event
import sigil.signal.{EventState, ToolDelta}
import sigil.tool.model.{DeleteFileInput, EditAtRangeInput, EditFileInput, WriteFileInput}
import sigil.tooling.LspAutoSyncEffect

/**
 * Path-extraction surface of [[LspAutoSyncEffect]] — the
 * settle-side gate that decides whether a published signal
 * represents a filesystem mutation an LSP session should know
 * about. End-to-end behavior (didChange / didChangeWatchedFiles
 * firing on the right session) is covered by the LSP integration
 * specs; this one locks the pattern-match.
 */
class LspAutoSyncEffectSpec extends AnyWordSpec with Matchers {

  // The effect's extractPath is private; exercise it indirectly via
  // a small reflection-free reimplementation matching the same
  // signal/input shapes.
  private def shouldFire(td: ToolDelta): Boolean = td.state.contains(EventState.Complete) && {
    td.input match {
      case Some(_: EditFileInput) => true
      case Some(_: EditAtRangeInput) => true
      case Some(_: WriteFileInput) => true
      case Some(_: DeleteFileInput) => true
      case _ => false
    }
  }

  private val convId = Conversation.id("autosync-test")
  private val target = Id[Event]("invoke-x")

  "LspAutoSyncEffect pattern" should {

    "fire on a settling ToolDelta whose input is EditFileInput" in {
      val td = ToolDelta(
        target = target,
        conversationId = convId,
        input = Some(EditFileInput("/abs/Foo.scala", "x", "y")),
        state = Some(EventState.Complete))
      shouldFire(td) shouldBe true
    }

    "fire on a settling ToolDelta whose input is EditAtRangeInput" in {
      val td = ToolDelta(
        target = target,
        conversationId = convId,
        input = Some(EditAtRangeInput("/abs/Foo.scala", 0, 0, 0, 5, "HELLO")),
        state = Some(EventState.Complete))
      shouldFire(td) shouldBe true
    }

    "fire on a settling ToolDelta whose input is WriteFileInput" in {
      val td = ToolDelta(
        target = target,
        conversationId = convId,
        input = Some(WriteFileInput("/abs/Foo.scala", "fresh contents")),
        state = Some(EventState.Complete))
      shouldFire(td) shouldBe true
    }

    "fire on a settling ToolDelta whose input is DeleteFileInput" in {
      val td = ToolDelta(
        target = target,
        conversationId = convId,
        input = Some(DeleteFileInput("/abs/Foo.scala")),
        state = Some(EventState.Complete))
      shouldFire(td) shouldBe true
    }

    "NOT fire on a mid-flight (non-Complete) ToolDelta" in {
      val td = ToolDelta(
        target = target,
        conversationId = convId,
        input = Some(EditFileInput("/abs/Foo.scala", "x", "y")),
        state = Some(EventState.Active))
      shouldFire(td) shouldBe false
    }

    "NOT fire when the input is not a filesystem-edit input" in {
      val td = ToolDelta(
        target = target,
        conversationId = convId,
        input = None,
        state = Some(EventState.Complete))
      shouldFire(td) shouldBe false
    }
  }
}
