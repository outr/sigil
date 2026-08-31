package sigil.tool.context

import fabric.rw.*
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
 * @param newSpace the target space's `value` string (as listed by the
 *                 caller's accessible spaces); resolved server-side —
 *                 the model never constructs a space record.
 * @param fromSpace optional disambiguator (a space `value` string)
 *                  when the same key exists in multiple spaces.
 */
case class MoveMemoryInput(key: String,
                           newSpace: String,
                           fromSpace: Option[String] = None)
  extends ToolInput derives RW
