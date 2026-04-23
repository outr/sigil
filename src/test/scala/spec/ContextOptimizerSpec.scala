package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.conversation.ContextFrame
import sigil.conversation.compression.StandardContextOptimizer
import sigil.event.Event
import sigil.tool.ToolName

/**
 * Mechanical coverage of [[StandardContextOptimizer]]. No DB, no LLM,
 * no async — the optimizer is a pure function; assertions are direct.
 */
class ContextOptimizerSpec extends AnyWordSpec with Matchers {
  private val opt = new StandardContextOptimizer

  private def textFrame(s: String, pid: TestUser.type = TestUser): ContextFrame.Text =
    ContextFrame.Text(s, pid, Id[Event](s"evt-$s"))

  "StandardContextOptimizer" should {
    "drop empty and whitespace-only Text frames" in {
      val frames = Vector(
        textFrame("hello"),
        ContextFrame.Text("   ", TestUser, Id[Event]("evt-ws")),
        ContextFrame.Text("", TestUser, Id[Event]("evt-empty")),
        textFrame("world")
      )
      opt.optimize(frames).collect { case t: ContextFrame.Text => t.content } shouldBe Vector("hello", "world")
    }

    "dedup consecutive Text frames with identical content from the same participant" in {
      val frames = Vector(
        textFrame("hi"),
        textFrame("hi"),
        textFrame("hi"),
        textFrame("bye")
      )
      opt.optimize(frames).collect { case t: ContextFrame.Text => t.content } shouldBe Vector("hi", "bye")
    }

    "not dedup identical Text frames when a non-text frame sits between them" in {
      val callId = Id[Event]("call-1")
      val frames = Vector(
        textFrame("hi"),
        ContextFrame.ToolCall(ToolName("noop"), "{}", callId, TestUser, callId),
        textFrame("hi")
      )
      opt.optimize(frames).count {
        case _: ContextFrame.Text => true
        case _                    => false
      } shouldBe 2
    }

    "pass non-Text frames through unchanged" in {
      val sysId = Id[Event]("sys-1")
      val frames = Vector(ContextFrame.System("banner", sysId), textFrame("hi"))
      opt.optimize(frames) shouldBe frames
    }
  }
}
