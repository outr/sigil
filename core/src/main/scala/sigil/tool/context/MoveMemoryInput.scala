package sigil.tool.context

import fabric.rw.*
import sigil.SpaceId
import sigil.SpaceId.given
import sigil.tool.ToolInput

/**
 * Input for the `move_memory` tool. Re-scopes an existing memory to a
 * different accessible [[sigil.SpaceId]] — useful when a memory was
 * classified into the wrong space initially, or when the right scope
 * changes (e.g. a project-specific rule turns out to apply to the
 * whole user across projects).
 *
 * The record's `_id` and `key` stay the same; only `spaceId` (and
 * `modified`) change. Accessibility is enforced: the caller must be
 * able to access both the source and target spaces.
 *
 * @param key      the memory's `key` (preferred) or `_id` value if no key.
 * @param newSpace the target space (must be in the caller's
 *                 accessible-spaces set).
 * @param fromSpace optional disambiguator when the same key exists in
 *                  multiple spaces.
 */
case class MoveMemoryInput(key: String,
                           newSpace: SpaceId,
                           fromSpace: Option[SpaceId] = None) extends ToolInput derives RW
