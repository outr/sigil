package sigil.tool.consult

import sigil.tool.{ToolName, TypedTool}

/**
 * Internal-only tool the framework forces the agent to call as
 * part of a periodic progress checkpoint. The tool's typed input
 * IS the checkpoint payload — the framework reads it directly
 * (no `execute` body), persists a [[sigil.event.ProgressCheckpoint]]
 * Event, and decides whether to continue the agent loop or to
 * intervene.
 */
case object ProgressReflectionTool extends TypedTool[ProgressReflectionInput](
  name = ToolName("report_progress"),
  description =
    """Report your progress checkpoint relative to the prior status anchor in the system prompt.
      |
      |Pick a `currentStatus` (one line summary of where things stand RIGHT NOW), set
      |`meaningfulProgress = true` only when you're in a substantively different place than the
      |prior status (NOT just because you ran a tool). `remainingSteps` is one line on what's
      |left. `stuckOn` is empty string (or the field unset) when making progress; otherwise one
      |line on what's blocking. Set `shouldAskUser = true` only if you genuinely need the user
      |to clarify something to proceed.
      |
      |Be honest — if your status looks identical to the prior status or you're cycling through
      |the same searches, say so (`meaningfulProgress = false`) so the framework can intervene.""".stripMargin
) {
  override protected def executeTyped(input: ProgressReflectionInput,
                                      context: sigil.TurnContext): rapid.Stream[sigil.event.Event] =
    rapid.Stream.empty
}
