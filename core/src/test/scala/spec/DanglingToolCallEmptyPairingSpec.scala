package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 * The orchestrator must keep the durable event log well-formed:
 * every published `ToolInvoke` is paired with a Tool-role Message
 * (origin = invokeId) before the turn settles, even on error paths.
 * Without this, subsequent turns' `Provider.renderInput` finds
 * dangling ToolInvokes and falls into its defensive synthesis path —
 * which stacked identical retry-prompt frames in the agent's context
 * across past turns until the model gave up.
 *
 * Architectural rule: render-time synthesis is a safety net, not a
 * primary mechanism. Pairing happens at the orchestrator's runtime
 * guard / orphan-settle paths so the durable log carries the result
 * for every call_id.
 */
class DanglingToolCallEmptyPairingSpec extends AnyWordSpec with Matchers {

  private def orchestratorSrc: String =
    scala.io.Source.fromFile(
      "core/src/main/scala/sigil/orchestrator/Orchestrator.scala"
    ).getLines().mkString("\n")

  private def providerSrc: String =
    scala.io.Source.fromFile(
      "core/src/main/scala/sigil/provider/Provider.scala"
    ).getLines().mkString("\n")

  "Orchestrator settleOrphanToolInvoke" should {

    "pair every orphan ToolInvoke with a Tool-role failure Message in the durable log" in {
      val src = orchestratorSrc
      // The settle helper now constructs Tool-role Messages with
      // origin = invokeId paired to each orphan.
      src should include("private def settleOrphanToolInvoke")
      src should include("role           = MessageRole.Tool")
      src should include("origin         = Some(active.invokeId)")
      // The caller-side wiring threads caller + topicId so the
      // synthesized failure can be addressed to a real participant.
      src should include("settleOrphanToolInvoke(state, convId, caller, topicId,")
    }

    "expose a per-orphan reason builder so callers customize the failure phrasing" in {
      val src = orchestratorSrc
      src should include("reasonFor: ActiveCall => String")
      // MaxTokens path still surfaces its specific truncation
      // guidance via the per-call reason builder.
      src should include("truncated at max_tokens")
    }
  }

  "Provider.renderInput dangling-call synthesis" should {

    "remain a defensive safety net with no prose directive in the agent's context" in {
      val src = providerSrc
      // The prose retry-prompts that stacked across past turns are gone.
      src should not include "The previous tool call did not return a result"
      src should not include "transient internal error"
      src should not include "Please report it"
      src should not include "framework error: tool emitted no MessageRole.Tool"
      // The "tool failed: no result emitted" text was itself a prose
      // directive that poisoned agent reasoning (sigil bug #189
      // family). Replaced with a short non-directive marker — keeps
      // wire shape valid (function_call ↔ function_call_output
      // pairing) without telling the agent how to react. The
      // scribe.error is the actionable surface for the
      // framework-bug-detection path; with sigil bug #190's
      // corruption-resistance invariant in place, this fallback
      // should be unreachable in well-formed operation.
      src should not include "tool failed: no result emitted"
      src should include("\"(orphan)\"")
      src should include("renderInput: dangling tool_call")
    }
  }

  "Orchestrator runtime guard for tools that emit no result" should {

    "emit a brief structured failure, not a verbose retry directive" in {
      val src = orchestratorSrc
      src should not include "This is typically a tool-side bug"
      src should not include "executeTyped swallowed an error"
      src should not include "synthetic placeholder fills it"
      src should include("Pick a different tool or refine the approach")
    }
  }
}
