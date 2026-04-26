package sigil.provider

/**
 * The framework's only default [[Mode]]. General chat, Q&A, free-form
 * conversation. Ships with `ToolPolicy.Standard` — the baseline
 * participant roster and full discovery catalog apply unchanged.
 *
 * Apps that want other modes (Coding, Workflow, WebNavigation, etc.)
 * define their own case objects and register them via
 * `Sigil.modeRegistrations` (paired with `Sigil.modes`). Use
 * `fabric.rw.RW.static(MyMode)` to obtain the RW for registration.
 */
case object ConversationMode extends Mode {
  override val name: String = "conversation"
  override val description: String = "General chat, Q&A, conversation."
}
