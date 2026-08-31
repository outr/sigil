package sigil.tool.discovery

import fabric.rw.*

enum CapabilityStatus derives RW {

  /**
   * Available and usable on the next turn — call by name.
   */
  case Ready

  /**
   * Requires a setup step before invocation. `hint` describes the
   * exact step (e.g. `change_mode("script-authoring")` for a Mode).
   * Rendered into the result so the agent has an actionable next
   * call, not just a "this exists" notice.
   */
  case RequiresSetup(hint: String)
}
