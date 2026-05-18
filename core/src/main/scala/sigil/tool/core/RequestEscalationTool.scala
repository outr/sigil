package sigil.tool.core

import fabric.rw.*
import rapid.Task
import sigil.TurnContext
import sigil.provider.Complexity
import sigil.tool.{ToolExample, ToolInput, ToolName, ToolResult, TypedOutputTool}

/**
 * Bug #128 — agent-callable escalation. Bumps the cached complexity
 * tier one step up (Low → Medium → High) for subsequent iterations
 * of the current user turn, so the agent re-runs against a more
 * capable model when it realises the task is harder than the
 * classifier's initial assessment.
 *
 * Opt-in: NOT in `CoreTools.all` by default. Apps that wire a
 * routing strategy with `inferComplexity` register
 * [[RequestEscalationTool]] explicitly via their `staticTools`. Apps
 * that don't route by complexity don't see the tool in the catalog.
 *
 * The current iteration's response (this tool call) still runs on
 * the original model — the bump takes effect on the NEXT iteration.
 * Once `High`, further escalation calls are no-ops (the bump_up
 * arithmetic clamps).
 */
final case class RequestEscalationInput(@description("Why escalation is needed — e.g. 'task spans 4 files; current model keeps producing inconsistent edits'. Stored on the conversation for transparency; rendered on the next checkpoint.")
reason: String)
  extends ToolInput derives RW

final case class RequestEscalationOutput(
  @description("The new complexity tier after the bump. Same as the previous tier when already at High (clamped).")
  tier: Complexity,
  @description("True when the call actually bumped the tier; false when already at High.")
  bumped: Boolean)
  derives RW

case object RequestEscalationTool
  extends TypedOutputTool[RequestEscalationInput, RequestEscalationOutput](
    name = ToolName("request_escalation"),
    description =
      """Escalate this turn's complexity tier so subsequent iterations route to a more capable
        |model. Use when you realize the task is harder than the classifier's initial assessment —
        |e.g. a question that looked simple has cross-file implications, or your initial reasoning
        |kept hitting dead ends.
        |
        |Effect: bumps the cached complexity for the current user turn one tier up (Low → Medium
        |→ High). Your NEXT iteration runs against whichever model in the chain supports the
        |elevated tier. The current iteration's response (this tool call) still runs on the
        |original model. Once at `High`, further calls are no-ops.
        |
        |Don't escalate just because a tool returned empty results — that's gathering progress,
        |not complexity. Escalate when the SHAPE of the answer needs more reasoning than you
        |have headroom for.""".stripMargin,
    examples = List(
      ToolExample(
        "Realize mid-turn the task is harder than expected",
        RequestEscalationInput(reason = "task touches 4 files; current edits keep contradicting each other")
      )
    ),
    keywords = Set("escalate", "tier", "complexity", "harder", "smarter model", "frontier")
  ) {
  override def paginate: Boolean = false

  override protected def executeTypedResult(input: RequestEscalationInput,
                                            context: TurnContext): Task[ToolResult[RequestEscalationOutput]] =
    context.sigil.requestEscalation(context.conversation._id, input.reason).map {
      case (newTier, true) => ToolResult.success(RequestEscalationOutput(tier = newTier, bumped = true))
      case (newTier, false) => ToolResult.success(RequestEscalationOutput(tier = newTier, bumped = false))
    }
}
