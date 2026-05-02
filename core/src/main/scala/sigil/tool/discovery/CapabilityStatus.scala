package sigil.tool.discovery

import fabric.rw.*

/**
 * Availability semantics for a [[CapabilityMatch]]. Bug #66 — mirrors
 * the Scalagentic shape: tells the agent whether the capability is
 * usable on the next turn or whether it has to take a step first.
 *
 * Today the framework emits exactly two cases:
 *   - `Ready` — Tool matches (the agent calls them directly).
 *   - `RequiresSetup(hint)` — Mode matches (`hint` is the
 *     `change_mode("…")` invocation that unlocks the mode's roster).
 *
 * The case set is open by intent — future categories (marketplace
 * tools that need an `install`, agents that need a `@mention` hop)
 * extend the enum without changing the result shape.
 */
enum CapabilityStatus derives RW {
  /** Available and usable on the next turn — call by name. */
  case Ready

  /** Requires a setup step before invocation. `hint` describes the
    * exact step (e.g. `change_mode("script-authoring")` for a Mode).
    * Rendered into the result so the agent has an actionable next
    * call, not just a "this exists" notice. */
  case RequiresSetup(hint: String)
}
