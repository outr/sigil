package sigil.tool.model

import fabric.rw.*
import sigil.tool.ToolInput

/**
 * Input for `random_int` — generate a uniformly random integer in
 * `[min, max]` (inclusive on both ends). Optional `seed` for
 * reproducible draws.
 */
case class RandomIntInput(min: Long,
                          max: Long,
                          seed: Option[Long] = None)
  extends ToolInput derives RW
