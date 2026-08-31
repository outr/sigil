package sigil.tool.core

import fabric.rw.*
import rapid.Task
import sigil.provider.Complexity
import sigil.tool.ToolContext
import sigil.tool.{
  DiscoverySpec, Effect, MutationTargeting, Resolution, Tool, ToolExample, ToolIO, ToolInput, ToolName, ToolOutput, ToolProfile, ToolResult,
  ToolSpec
}

final case class RequestDeescalationInput(@description("Why the cheaper tier suffices now — e.g. 'the hard restoration is done; remaining work is mechanical error fixes'. Stored for transparency.")
reason: String)
  extends ToolInput derives RW

final case class RequestDeescalationOutput(
  @description("The complexity tier after the step down. Unchanged when there was no escalation to unwind.")
  tier: Complexity,
  @description("True when the call actually lowered the tier; false when the turn was already at its classified base tier.")
  lowered: Boolean)
  extends ToolOutput derives RW

/**
 * The symmetric counterpart to `request_escalation`: step the current
 * turn's escalation count back down one level when the remaining work
 * no longer needs the expensive tier. Escalation is per-turn and
 * resets on every new user message, so this matters mid-turn — a long
 * turn whose hard phase is done should not grind its mechanical tail
 * at frontier prices.
 */
case object RequestDeescalationTool extends Tool {
  type Input = RequestDeescalationInput
  type Output = RequestDeescalationOutput
  val io: ToolIO[RequestDeescalationInput, RequestDeescalationOutput] =
    ToolIO.derived[RequestDeescalationInput, RequestDeescalationOutput].withExamples(
      ToolExample(
        "Step down after the hard phase completes",
        RequestDeescalationInput(reason = "definitions restored from git history; remaining work is fixing mechanical compile errors")
      )
    )
  override val name = ToolName("request_deescalation")
  override val description =
    """Step this turn's complexity tier back DOWN one level so subsequent iterations route to a
      |cheaper model. Use when the hard part of the task is done and the remaining work is
      |mechanical — repetitive edits, running checks, applying a pattern you've already worked
      |out. Your NEXT iteration runs on the cheaper tier; if the work turns out harder than
      |expected, `request_escalation` steps back up.
      |
      |No-op when the turn hasn't been escalated: this unwinds `request_escalation` (and
      |automatic escalations), never below the turn's classified base tier. Every new user
      |message re-classifies from scratch anyway — this tool is for stepping down MID-turn.""".stripMargin

  val spec: ToolSpec = ToolSpec(
    name = name,
    description = description,
    profile = ToolProfile(effect = Effect.Mutating(MutationTargeting.none)),
    discovery = DiscoverySpec(keywords = Set("deescalate", "de-escalate", "tier", "complexity", "cheaper model", "step down", "cost"))
  )

  protected def resolve: Resolution[Input, Output] = Resolution.Explicit(executeResult)

  private def executeResult(input: RequestDeescalationInput,
                            context: ToolContext): Task[ToolResult[RequestDeescalationOutput]] =
    context.sigil.requestDeescalation(context.conversation._id, input.reason).map {
      case (tier, lowered) => ToolResult.success(RequestDeescalationOutput(tier = tier, lowered = lowered))
    }
}
