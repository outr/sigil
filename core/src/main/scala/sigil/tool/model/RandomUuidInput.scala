package sigil.tool.model

import fabric.rw.*
import sigil.tool.ToolInput

/**
 * Input for `random_uuid` — generate a v4 (random) UUID. No
 * parameters; the empty-args case class exists to satisfy the
 * `ToolInput` contract.
 */
case class RandomUuidInput() extends ToolInput derives RW
