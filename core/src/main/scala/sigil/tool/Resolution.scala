package sigil.tool

import rapid.Task

/**
 * The single body shape a [[Tool]] implements — exactly one required
 * member of a sealed type, so "neither body implemented" and "both
 * bodies implemented" are unrepresentable:
 *
 *   - [[Simple]] — return the typed output; a thrown error is caught
 *     by the framework and surfaced as a recoverable
 *     [[ToolResult.Failure]].
 *   - [[Explicit]] — full control over success vs. logical failure
 *     (file not found, validator rejection, missing precondition);
 *     failures ride the [[ToolResult]] envelope in-band.
 *
 * [[ToolExecutor]] is the only code that unwraps a `Resolution` — it
 * owns gating, the emit-buffer drain, output bounding, and the paired
 * result event.
 */
enum Resolution[I <: ToolInput, O <: ToolOutput] {
  case Simple(run: (I, ToolContext) => Task[O])
  case Explicit(run: (I, ToolContext) => Task[ToolResult[O]])
}
