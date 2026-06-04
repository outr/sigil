package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.conversation.{Conversation, ContextFrame, FrameBuilder, ToolCallState, Topic}
import sigil.event.{MessageDisposition, ToolInvoke, ToolOutcome}
import sigil.orchestrator.SyntheticDiagnostic

/**
 * Sigil #341 — a synthetic diagnostic (`_repeated_query_intercept`,
 * `_refusal_challenge`, …) must convey its `reason` to the agent's frame
 * even when the paired Tool-role guidance Message doesn't reach it (which
 * it didn't on the orchestrator's execute-stream emit path, leaving the
 * invoke rendered as a content-free `(pending)` that stranded the agent).
 * The fix makes the invoke self-describing — the reason rides its own
 * `outcome` + `summary` — so `FrameBuilder` renders it directly.
 */
class SyntheticDiagnosticFrameSpec extends AnyWordSpec with Matchers {

  private val caller = TestAgent
  private val convId = Conversation.id("synth-diag")
  private val topicId = Topic.id("synth-diag-topic")
  private val reason =
    "You already called `find_capability` with keywords `bug references`. Pick a different " +
      "tool from the prior results or search with different keywords."

  private def frameText(f: ContextFrame): String = f match {
    case tc: ContextFrame.ToolCall => tc.state match {
        case ToolCallState.Complete(content, _) => content
        case _ => ""
      }
    case _ => ""
  }

  "SyntheticDiagnostic.apply" should {
    "stamp the reason onto the invoke's own outcome + summary (self-describing, #341)" in {
      val invoke = SyntheticDiagnostic(
        "_repeated_query_intercept",
        caller,
        convId,
        topicId,
        reason = reason,
        disposition = MessageDisposition.Failure(recoverable = true))
        .collectFirst { case ti: ToolInvoke => ti }.getOrElse(fail("no invoke produced"))
      invoke.outcome shouldBe ToolOutcome.Failure(reason, recoverable = true)
      invoke.summary shouldBe reason
    }

    "render the reason in the agent's frame from the invoke ALONE — no paired Message (#341)" in {
      val invoke = SyntheticDiagnostic(
        "_repeated_query_intercept",
        caller,
        convId,
        topicId,
        reason = reason,
        disposition = MessageDisposition.Failure(recoverable = true))
        .collectFirst { case ti: ToolInvoke => ti }.get
      // Frame the invoke WITHOUT its paired Message — the bug's scenario.
      val text = frameText(FrameBuilder.computeFrame(invoke).getOrElse(fail("no frame")))
      text should include("find_capability")
      text should not be "(pending)"
    }

    "carry a Success outcome (with the reason as summary) for a non-failure disposition" in {
      val invoke = SyntheticDiagnostic("_provider_error", caller, convId, topicId, reason = reason)
        .collectFirst { case ti: ToolInvoke => ti }.get
      invoke.outcome shouldBe ToolOutcome.Success
      frameText(FrameBuilder.computeFrame(invoke).get) should include("find_capability")
    }
  }

  "FrameBuilder backstop (#341)" should {
    "never render a Complete invoke with an unsettled outcome as a bare (pending)" in {
      // The `invoke`-only path (e.g. `_provider_error`) yields a Complete
      // invoke whose outcome stays Pending until a paired result settles
      // it; if that never reaches the frame, it must not strand the agent.
      val bare = SyntheticDiagnostic.invoke("_provider_error", caller, convId, topicId)
      bare.outcome shouldBe ToolOutcome.Pending
      val text = frameText(FrameBuilder.computeFrame(bare).get)
      text should not be "(pending)"
      text should include("_provider_error")
    }
  }
}
