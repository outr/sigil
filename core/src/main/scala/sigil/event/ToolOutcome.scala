package sigil.event

import fabric.rw.*

/**
 * Top-level discriminator on a [[ToolResults]] event — did this tool
 * call succeed or fail?
 *
 * Distinct from `MessageDisposition.Failure`-shaped content blocks
 * (which encode a user-facing "agent couldn't complete the task" via
 * `respond_failure`); `ToolOutcome` is the framework-level "did this
 * tool's invocation produce a usable result?" answer. Lets consumers
 * pattern-match on tool-call success without inspecting individual
 * content blocks.
 *
 * Tools authoring against the framework set this directly when they
 * emit a [[ToolResults]] event; the framework's own dispatchers
 * default to `Success` on clean returns and `Failure` when the
 * tool's `execute` raised (Bug #50 / Bug #69 paths).
 */
enum ToolOutcome derives RW {
  case Success

  /**
   * The tool call did not produce a usable result.
   *
   * @param reason       REQUIRED. Always present. The text the agent
   *                     reads on its next iteration to understand what
   *                     went wrong. Authoring contract:
   *                     - If `recoverable = false`: explain *why* the
   *                       call failed — the diagnostic the agent reads
   *                       so it can give the user an honest answer or
   *                       pick a different approach.
   *                     - If `recoverable = true`: explain *why* it
   *                       failed AND *how to fix it* — the agent reads
   *                       this on its next turn and is expected to
   *                       retry with the correction. An empty or
   *                       useless `reason` strands the agent ("the
   *                       call failed; try again how?") so this field
   *                       must always carry actionable content. Sigil
   *                       #263.
   * @param recoverable  Whether the agent should attempt to retry the
   *                     call after reading `reason`. `false` (default)
   *                     means terminal for this approach; the agent
   *                     should report or change strategy. `true` means
   *                     the failure is something the agent can fix
   *                     from the diagnostic — malformed args, missing
   *                     required field, validator rejection.
   */
  case Failure(reason: String, recoverable: Boolean = false)
}
