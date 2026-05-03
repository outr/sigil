package sigil.tool

import fabric.rw.*

/**
 * Outcome of a [[ToolPrecondition.check]].
 *
 *   - [[Satisfied]] — the precondition holds; tool execution proceeds.
 *   - [[Unsatisfied]] — the precondition is violated. `reason` is shown
 *     to the agent so it knows what to do next; `suggestedFix` is an
 *     optional pointer to a setup tool the agent can call to remediate
 *     (e.g. `"slack_connect"`, `"complete_onboarding"`, `"start_docker"`).
 */
enum ToolPreconditionResult derives RW {
  case Satisfied
  case Unsatisfied(reason: String, suggestedFix: Option[String] = None)
}
