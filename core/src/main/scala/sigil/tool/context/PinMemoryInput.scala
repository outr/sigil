package sigil.tool.context

import fabric.rw.*
import sigil.SpaceId
import sigil.SpaceId.given
import sigil.tool.ToolInput

/**
 * Input for the `pin_memory` tool. Flips
 * [[sigil.conversation.ContextMemory.pinned]] from `false` to `true`
 * so the memory starts rendering every turn — useful when an
 * existing memory turns out to be more important than initially
 * classified ("oh actually that's a hard rule, please always follow
 * it"). The record stays on disk; only its rendering policy changes.
 *
 * @param key   the memory's `key` (preferred) or `_id` value if no key.
 * @param space optional disambiguator when the same key exists in
 *              multiple accessible spaces.
 */
case class PinMemoryInput(key: String,
                          space: Option[SpaceId] = None) extends ToolInput derives RW
