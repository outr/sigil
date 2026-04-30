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

    "drop find_capability tool-call/result pairs when the curator marks it ephemeral" in {
      val callId = Id[Event]("fc-1")
      val frames = Vector[ContextFrame](
        textFrame("looking up tools"),
        ContextFrame.ToolCall(ToolName("find_capability"), "{\"keywords\":[\"slack\"]}", callId, TestUser, callId),
        ContextFrame.ToolResult(callId, "- send_slack_message: send", Id[Event]("fc-1-r")),
        textFrame("got it")
      )
      // The curator resolves Tool.resultTtl=Some(0) tools to this set
      // and passes it to the optimizer. find_capability declares
      // resultTtl=Some(0), so it's elided in the standard pipeline.
      val out = opt.optimize(frames, Set("find_capability"))
      out.count {
        case _: ContextFrame.ToolCall   => true
        case _: ContextFrame.ToolResult => true
        case _                          => false
      } shouldBe 0
      out.collect { case t: ContextFrame.Text => t.content } shouldBe Vector("looking up tools", "got it")
    }

    "drop change_mode tool-call/result pairs when ephemeral (System frame still conveys the transition)" in {
      val callId = Id[Event]("cm-1")
      val sysId = Id[Event]("cm-sys")
      val frames = Vector[ContextFrame](
        ContextFrame.ToolCall(ToolName("change_mode"), "{\"mode\":\"Coding\"}", callId, TestUser, callId),
        ContextFrame.ToolResult(callId, "Mode changed to Coding.", Id[Event]("cm-1-r")),
        ContextFrame.System("Mode: Coding", sysId)
      )
      val out = opt.optimize(frames, Set("change_mode"))
      out.collect { case tc: ContextFrame.ToolCall => tc } shouldBe empty
      out.collect { case tr: ContextFrame.ToolResult => tr } shouldBe empty
      out.collect { case s: ContextFrame.System => s.content } shouldBe Vector("Mode: Coding")
    }

    "leave tool pairs alone when no elide-set is passed (default behavior is no stripping)" in {
      val callId = Id[Event]("noop-1")
      val frames = Vector[ContextFrame](
        ContextFrame.ToolCall(ToolName("find_capability"), "{}", callId, TestUser, callId),
        ContextFrame.ToolResult(callId, "results", Id[Event]("noop-1-r"))
      )
      val out = opt.optimize(frames)
      out.collect { case tc: ContextFrame.ToolCall => tc } should have size 1
      out.collect { case tr: ContextFrame.ToolResult => tr } should have size 1
    }

    "collapse pairs for additional tool names via stripStaleTools" in {
      val optExtra = StandardContextOptimizer(stripStaleTools = Set("my_app_tool"))
      val callId = Id[Event]("app-1")
      val frames = Vector[ContextFrame](
        ContextFrame.ToolCall(ToolName("my_app_tool"), "{}", callId, TestUser, callId),
        ContextFrame.ToolResult(callId, "ok", Id[Event]("app-1-r"))
      )
      optExtra.optimize(frames) shouldBe empty
    }
  }
}
