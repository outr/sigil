package sigil.tool.model

import fabric.rw.*
import sigil.tool.ToolInput

/**
 * Input for the `respond_failure` tool — emit a typed failure block as
 * part of the agent's reply. Use when the agent could not complete the
 * task. The orchestrator can pattern-match on `ResponseContent.Failure`
 * to decide whether to retry, alert, or surface the message as an error
 * UI.
 *
 *   - `reason` — short user-facing explanation of what went wrong.
 *   - `recoverable` — `true` indicates the failure may succeed on
 *     retry (transient — network blips, rate limits, etc.); `false`
 *     indicates a permanent failure for this request (missing
 *     permissions, unsupported input, etc.).
 */
case class RespondFailureInput(reason: String,
                               recoverable: Boolean = false) extends ToolInput derives RW
