package sigil.tool.core

import sigil.tool.Tool

/**
 * Bug #131 — annotation mix-in for the respond-family tools
 * (`respond`, `respond_options`, `respond_field`, `respond_failure`,
 * `respond_card`, `respond_cards`, `no_response`). Each is
 * destructive (ends the turn), open-world (the user reads the
 * Message), not idempotent (calling twice produces two messages).
 *
 * Centralised so adding a future respond-shaped tool doesn't require
 * remembering four annotation overrides.
 */
trait RespondFamilyTool extends Tool {
  override def destructive: Boolean = true
  override def openWorld: Boolean = true
  override def idempotent: Boolean = false
  override def readOnly: Boolean = false

  /**
   * Terminality framing — `**ENDS YOUR TURN.**` beats the generic
   * destructive prefix because turn-end is the load-bearing
   * semantic (small models over-call respond when they think it's
   * just a content tool).
   */
  override protected def destructivePrefix: String = "**ENDS YOUR TURN.** "
}
