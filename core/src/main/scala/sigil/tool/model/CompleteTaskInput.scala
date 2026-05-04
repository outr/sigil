package sigil.tool.model

import fabric.rw.*
import sigil.tool.ToolInput

/**
 * Input for `complete_task` — the worker's typed terminator.
 * Carries the worker's `summary` (a one-paragraph result the parent
 * agent can surface to the user). Companion to the `Complete:`
 * line marker; tool-calling-capable models prefer this typed form,
 * the marker stays as a fallback for providers that don't surface
 * tool calls cleanly.
 */
case class CompleteTaskInput(summary: String) extends ToolInput derives RW
