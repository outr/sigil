package sigil.tool

import rapid.Task
import sigil.TurnContext

/**
 * Declarable pre-execution gate for a [[Tool]]. Examples:
 *
 *   - "this tool requires an active Slack OAuth token"
 *   - "this tool needs Docker to be running"
 *   - "this tool requires the user to have completed onboarding"
 *
 * The orchestrator runs every tool's preconditions before calling
 * `tool.execute`. If any precondition returns
 * [[ToolPreconditionResult.Unsatisfied]], the tool is NOT invoked —
 * instead the orchestrator emits a `Role.Tool` Message describing
 * what needs to happen first, the agent reads it on its next turn,
 * and (typically) calls the suggested setup tool / asks the user to
 * complete the missing step.
 *
 * Preconditions are pure descriptive: they don't fix the gap, they
 * only identify it. Apps with auto-fix flows define a separate "setup"
 * tool and surface its name via [[ToolPreconditionResult.Unsatisfied.suggestedFix]].
 */
trait ToolPrecondition {
  /** Short human-readable identifier — surfaced in the Unsatisfied
   * message so the agent can pattern-match across multiple failed
   * preconditions ("the slack-oauth and the rate-limit are blocking"). */
  def name: String

  /** Run the check. Implementations should be fast — preconditions
   * fire on every tool call. Long checks (network round-trips, DB
   * scans) should cache and refresh out-of-band. */
  def check(context: TurnContext): Task[ToolPreconditionResult]
}
