package sigil.tool

import sigil.event.ToolOutcome

/**
 * Everything a settled tool call folded onto its invoke.
 *
 * Captured when a call settles so that a later identical call the
 * framework answers from its own records — the turn-scoped read cache,
 * the same-completion duplicate inlining — settles with the very same
 * payload. Replaying the payload (rather than a re-rendering of it)
 * makes the served answer read identically to the original by
 * construction: a model that asks again gets what it got the first
 * time, which is what stops it asking a third.
 */
final case class ToolSettlePayload(output: ToolOutput,
                                   outcome: ToolOutcome,
                                   summary: Option[String],
                                   overflow: Option[OverflowPointer])
