package sigil.tool.model

import fabric.rw.*
import sigil.tool.ToolInput
import sigil.tool.discovery.CapabilityType

/**
 * Input for the `lookup` tool — the agent calls this to retrieve the
 * full record behind a [[sigil.tool.discovery.CapabilityMatch]] (a
 * memory's full fact, an information record's body, etc.).
 *
 * `capabilityType` selects the resolver — `Memory`, `Information`, or
 * (when Phase 4 lands) `Skill`. `name` is the type-specific stable
 * identifier the match surfaced (memory `key`, information `id` as
 * string, skill `name`).
 *
 * `Tool` and `Mode` capability types are not retrievable — tools are
 * called, modes are switched-to via `change_mode`. Looking those up
 * returns a not-supported error message rather than silently doing
 * nothing.
 */
case class LookupInput(capabilityType: CapabilityType,
                       name: String) extends ToolInput derives RW
