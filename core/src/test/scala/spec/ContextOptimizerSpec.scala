package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.conversation.{ContextFrame, ToolCallState}
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
        case _ => false
      } shouldBe 2
    }

    "preserve a SINGLE find_capability call frame (the agent's one-turn window)" in {
      val callId = Id[Event]("fc-1")
      val frames = Vector[ContextFrame](
        textFrame("looking up tools"),
        ContextFrame.ToolCall(
          ToolName("find_capability"),
          "{\"keywords\":[\"slack\"]}",
          callId,
          TestUser,
          callId,
          state = ToolCallState.Complete("- send_slack_message: send")),
        textFrame("got it")
      )
      // Volatile-read tools follow "valid for ONE next turn"
      // semantics — the LATEST call must survive a curate so the
      // agent can act on freshly-discovered tools. Bug #44 used to
      // strip even the latest pair, trapping the agent in a
      // discovery loop.
      val out = opt.optimize(frames, Set("find_capability"))
      out.count { case _: ContextFrame.ToolCall => true; case _ => false } shouldBe 1
      out.collect { case t: ContextFrame.Text => t.content } shouldBe Vector("looking up tools", "got it")
    }

    "elide earlier find_capability pairs but keep the most-recent" in {
      // Three find_capability calls in chronological order — the
      // agent searched, then searched again, then searched again —
      // and then a fresh user message opened the next turn, putting
      // all three on the prior-turn side of the boundary. Without the
      // preserve-latest rule the optimizer strips all three, the agent
      // loses the discovery on its next turn, calls find_capability
      // AGAIN, and loops forever.
      val c1 = Id[Event]("fc-old-1")
      val c2 = Id[Event]("fc-old-2")
      val c3 = Id[Event]("fc-latest")
      val frames = Vector[ContextFrame](
        ContextFrame.ToolCall(
          ToolName("find_capability"),
          "{\"keywords\":[\"a\"]}",
          c1,
          TestAgent,
          c1,
          state = ToolCallState.Complete("old result 1")),
        ContextFrame.ToolCall(
          ToolName("find_capability"),
          "{\"keywords\":[\"b\"]}",
          c2,
          TestAgent,
          c2,
          state = ToolCallState.Complete("old result 2")),
        ContextFrame.ToolCall(
          ToolName("find_capability"),
          "{\"keywords\":[\"c\"]}",
          c3,
          TestAgent,
          c3,
          state = ToolCallState.Complete("latest result")),
        textFrame("next ask")
      )
      val out = opt.optimize(frames, Set("find_capability"))
      out.collect { case tc: ContextFrame.ToolCall => tc.callId } shouldBe Vector(c3)
    }

    "preserve the latest pair PER tool name independently" in {
      // find_capability and change_mode interleaved in a prior turn —
      // the elider must keep the latest of each, not just the latest
      // of all.
      val fc1 = Id[Event]("fc-old")
      val cm1 = Id[Event]("cm-old")
      val fc2 = Id[Event]("fc-latest")
      val cm2 = Id[Event]("cm-latest")
      val frames = Vector[ContextFrame](
        ContextFrame.ToolCall(
          ToolName("find_capability"),
          "{}",
          fc1,
          TestAgent,
          fc1,
          state = ToolCallState.Complete("old")),
        ContextFrame.ToolCall(
          ToolName("change_mode"),
          "{}",
          cm1,
          TestAgent,
          cm1,
          state = ToolCallState.Complete("old mode")),
        ContextFrame.ToolCall(
          ToolName("find_capability"),
          "{}",
          fc2,
          TestAgent,
          fc2,
          state = ToolCallState.Complete("latest")),
        ContextFrame.ToolCall(
          ToolName("change_mode"),
          "{}",
          cm2,
          TestAgent,
          cm2,
          state = ToolCallState.Complete("latest mode")),
        textFrame("next ask")
      )
      val out = opt.optimize(frames, Set("find_capability", "change_mode"))
      out.collect { case tc: ContextFrame.ToolCall => tc.callId }.toSet shouldBe Set(fc2, cm2)
    }

    "leave tool calls alone when no elide-set is passed (default behavior is no stripping)" in {
      val callId = Id[Event]("noop-1")
      val frames = Vector[ContextFrame](
        ContextFrame.ToolCall(
          ToolName("find_capability"),
          "{}",
          callId,
          TestUser,
          callId,
          state = ToolCallState.Complete("results"))
      )
      val out = opt.optimize(frames)
      out.collect { case tc: ContextFrame.ToolCall => tc } should have size 1
    }

    "preserve the most-recent single call for additional tool names via stripStaleTools" in {
      // Same preserve-latest semantics for app-defined tools that
      // opt into elision via the explicit stripStaleTools knob.
      val optExtra = StandardContextOptimizer(stripStaleTools = Set("my_app_tool"))
      val callId = Id[Event]("app-1")
      val frames = Vector[ContextFrame](
        ContextFrame.ToolCall(
          ToolName("my_app_tool"),
          "{}",
          callId,
          TestUser,
          callId,
          state = ToolCallState.Complete("ok"))
      )
      val out = optExtra.optimize(frames)
      out.collect { case tc: ContextFrame.ToolCall => tc.callId } shouldBe Vector(callId)
    }
  }

  /**
   * Bug #73 — within-turn preservation. Pre-fix the optimizer elided
   * earlier `find_capability` / `change_mode` pairs every iteration
   * of the agent loop, including iterations within a single user
   * turn. The model couldn't see its own prior calls within the
   * turn, so each iteration looked like a fresh start and the model
   * called the same tool again. Loop until `maxAgentIterations`.
   *
   * Post-fix: the optimizer takes a `currentTurnSource` participant.
   * The most-recent Text frame from that participant marks the
   * "current turn started here" boundary. Pairs at or after the
   * boundary stay regardless of read freshness; only pairs from prior
   * turns are eligible for the legacy "keep latest per name" elision.
   */
  "StandardContextOptimizer.collapseToolPairs (bug #73 — within-turn preservation)" should {
    val opt = StandardContextOptimizer()

    "preserve EVERY find_capability call within the current user turn (the loop fix)" in {
      // User says one thing; agent iterates find_capability twice.
      // BOTH within-turn calls survive — pre-fix the second one
      // would have stripped the first, hiding the loop.
      val userMsg = Id[Event]("user-msg-1")
      val fc1 = Id[Event]("fc-1")
      val fc2 = Id[Event]("fc-2")
      val frames = Vector[ContextFrame](
        ContextFrame.Text("please find a tool", TestUser, userMsg),
        ContextFrame.ToolCall(
          ToolName("find_capability"),
          "{\"keywords\":\"x\"}",
          fc1,
          TestAgent,
          fc1,
          state = ToolCallState.Complete("result 1")),
        ContextFrame.ToolCall(
          ToolName("find_capability"),
          "{\"keywords\":\"x\"}",
          fc2,
          TestAgent,
          fc2,
          state = ToolCallState.Complete("result 2"))
      )
      val out = opt.optimize(frames, Set("find_capability"), Some(TestUser))
      out.collect { case tc: ContextFrame.ToolCall => tc.callId }.toSet shouldBe Set(fc1, fc2)
    }

    "elide pairs from a PRIOR turn while preserving within-turn pairs" in {
      // User1 → fc-prior (elided eligible) → User2 → fc-current.
      // Prior is dropped (not the latest in prior turns since there's
      // only one — under existing #44 logic the latest-per-name rule
      // applies WITHIN prior turns). Current stays.
      val userMsg1 = Id[Event]("user-msg-1")
      val fcPrior = Id[Event]("fc-prior")
      val userMsg2 = Id[Event]("user-msg-2")
      val fcCurrent = Id[Event]("fc-current")
      val frames = Vector[ContextFrame](
        ContextFrame.Text("first ask", TestUser, userMsg1),
        ContextFrame.ToolCall(
          ToolName("find_capability"),
          "{}",
          fcPrior,
          TestAgent,
          fcPrior,
          state = ToolCallState.Complete("prior")),
        ContextFrame.Text("answer", TestAgent, Id[Event]("answer")),
        ContextFrame.Text("second ask", TestUser, userMsg2),
        ContextFrame.ToolCall(
          ToolName("find_capability"),
          "{}",
          fcCurrent,
          TestAgent,
          fcCurrent,
          state = ToolCallState.Complete("current"))
      )
      val out = opt.optimize(frames, Set("find_capability"), Some(TestUser))
      // The current-turn call MUST survive (it's after the boundary).
      // The prior-turn call also survives because it's the latest pair
      // in the prior-turn segment (per the bug-#44 "give the agent its
      // one shot" rule).
      out.collect { case tc: ContextFrame.ToolCall => tc.callId }.toSet shouldBe Set(fcPrior, fcCurrent)
    }

    "elide MIDDLE prior-turn pairs but keep the latest prior + every within-turn" in {
      // Two prior-turn find_capability calls — the EARLIER one drops
      // (legacy bug-#44: keep latest per name in prior segment); the
      // LATER prior-turn one survives. Then the user starts a new
      // turn and the agent calls find_capability twice within it —
      // both within-turn calls survive (bug #73).
      val u1 = Id[Event]("u1")
      val fcOldA = Id[Event]("fc-old-a")
      val fcOldB = Id[Event]("fc-old-b") // latest of prior turn
      val u2 = Id[Event]("u2")
      val fcCur1 = Id[Event]("fc-cur-1")
      val fcCur2 = Id[Event]("fc-cur-2")
      val frames = Vector[ContextFrame](
        ContextFrame.Text("turn 1", TestUser, u1),
        ContextFrame.ToolCall(
          ToolName("find_capability"),
          "{}",
          fcOldA,
          TestAgent,
          fcOldA,
          state = ToolCallState.Complete("a")),
        ContextFrame.ToolCall(
          ToolName("find_capability"),
          "{}",
          fcOldB,
          TestAgent,
          fcOldB,
          state = ToolCallState.Complete("b")),
        ContextFrame.Text("turn 2", TestUser, u2),
        ContextFrame.ToolCall(
          ToolName("find_capability"),
          "{}",
          fcCur1,
          TestAgent,
          fcCur1,
          state = ToolCallState.Complete("c1")),
        ContextFrame.ToolCall(
          ToolName("find_capability"),
          "{}",
          fcCur2,
          TestAgent,
          fcCur2,
          state = ToolCallState.Complete("c2"))
      )
      val out = opt.optimize(frames, Set("find_capability"), Some(TestUser))
      val kept = out.collect { case tc: ContextFrame.ToolCall => tc.callId }.toSet
      // fcOldA dropped; fcOldB kept (latest of prior turn);
      // both fcCur1 and fcCur2 kept (within current turn).
      kept shouldBe Set(fcOldB, fcCur1, fcCur2)
    }

    "keep every pair when no turn boundary exists to prove one is stale" in {
      // No turn-opening frame anywhere — an agent-only conversation
      // (a delegated worker's scratchpad) or a caller handing over a
      // bare tool trail. Nothing here can be shown to belong to a
      // finished turn, so nothing is dropped: eliding on a boundary
      // that was never found is how a turn's own results disappear.
      val c1 = Id[Event]("fc-1")
      val c2 = Id[Event]("fc-2")
      val c3 = Id[Event]("fc-3")
      val frames = Vector[ContextFrame](
        ContextFrame.ToolCall(
          ToolName("find_capability"),
          "{}",
          c1,
          TestAgent,
          c1,
          state = ToolCallState.Complete("1")),
        ContextFrame.ToolCall(
          ToolName("find_capability"),
          "{}",
          c2,
          TestAgent,
          c2,
          state = ToolCallState.Complete("2")),
        ContextFrame.ToolCall(
          ToolName("find_capability"),
          "{}",
          c3,
          TestAgent,
          c3,
          state = ToolCallState.Complete("3"))
      )
      val out = opt.optimize(frames, Set("find_capability"), None)
      out.collect { case tc: ContextFrame.ToolCall => tc.callId } shouldBe Vector(c1, c2, c3)
    }

    "keep every within-turn pair when the running agent heads the chain" in {
      // The chain's head is the AGENT on every iteration whose
      // triggers are the agent's own tool results — which is every
      // iteration after the first. An agent id can never mark where
      // the turn began: its prose is emitted mid-turn, and taking it
      // as the boundary drops the turn's own tool pairs.
      val userMsg = Id[Event]("user-msg")
      val a = Id[Event]("call-a")
      val b = Id[Event]("call-b")
      val c = Id[Event]("call-c")
      val frames = Vector[ContextFrame](
        ContextFrame.Text("run the probes", TestUser, userMsg),
        ContextFrame.ToolCall(
          ToolName("find_capability"),
          "{\"keywords\":\"a\"}",
          a,
          TestAgent,
          a,
          state = ToolCallState.Complete("a")),
        ContextFrame.ToolCall(
          ToolName("find_capability"),
          "{\"keywords\":\"b\"}",
          b,
          TestAgent,
          b,
          state = ToolCallState.Complete("b")),
        ContextFrame.ToolCall(
          ToolName("find_capability"),
          "{\"keywords\":\"c\"}",
          c,
          TestAgent,
          c,
          state = ToolCallState.Complete("c"))
      )
      val out = opt.optimize(frames, Set("find_capability"), Some(TestAgent))
      out.collect { case tc: ContextFrame.ToolCall => tc.callId } shouldBe Vector(a, b, c)
    }

    "preserve within-turn calls even when other turn participants speak (multi-agent case)" in {
      // currentTurnSource = TestUser. After the user's message, an
      // agent (TestAgent) speaks via Text, then makes find_capability
      // calls. Those calls are within-turn from the user's perspective
      // — preserved.
      val userMsg = Id[Event]("user-msg")
      val fcInner = Id[Event]("fc-inner")
      val frames = Vector[ContextFrame](
        ContextFrame.Text("ask", TestUser, userMsg),
        ContextFrame.Text("(agent thinking)", TestAgent, Id[Event]("agent-text")),
        ContextFrame.ToolCall(
          ToolName("find_capability"),
          "{}",
          fcInner,
          TestAgent,
          fcInner,
          state = ToolCallState.Complete("ok"))
      )
      val out = opt.optimize(frames, Set("find_capability"), Some(TestUser))
      out.collect { case tc: ContextFrame.ToolCall => tc.callId } shouldBe Vector(fcInner)
    }
  }
}
