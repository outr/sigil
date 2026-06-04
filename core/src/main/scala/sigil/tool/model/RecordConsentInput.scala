package sigil.tool.model

import fabric.rw.*
import sigil.tool.ToolInput

/**
 * Input for [[sigil.tool.core.RecordConsentTool]] (`record_consent`).
 * The agent emits this after observing the user's consent decision
 * for a tool whose `requiresUserConsent` flag is set.
 *
 * `toolName` is the exact name of the consent-gated tool — must match
 * the [[sigil.tool.ToolName]] of the target. Wrong / mistyped names
 * persist a useless record and the gate keeps refusing.
 *
 * `approved = true` clears the gate; the framework dispatches matching
 * tool calls in this conversation from now until a `false` record is
 * written or the conversation is cleared.
 *
 * `approved = false` is sticky — declines persist; subsequent calls
 * are refused with the recorded `reason` until a fresh `true` record
 * lands.
 *
 * `reason` carries the agent's narrative — "user picked metals from
 * setup options", "user typed 'go ahead'", "user explicitly declined
 * import in respond_options". Audit / replay value; renders in the
 * refusal Tool-result so future agents understand the prior decision.
 */
case class RecordConsentInput(toolName: String,
                              approved: Boolean,
                              reason: Option[String] = None)
  extends ToolInput derives RW
