package sigil.event

import fabric.rw.*

/**
 * Top-level discriminator on a [[ToolResults]] event — did this tool
 * call succeed or fail?
 *
 * Distinct from `ResponseContent.Failure`-shaped content blocks
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
  case Failure(reason: String, recoverable: Boolean = false)
}
