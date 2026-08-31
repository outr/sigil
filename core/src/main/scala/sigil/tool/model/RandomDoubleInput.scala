package sigil.tool.model

import fabric.rw.*
import sigil.tool.ToolInput

/**
 * Input for `random_double` — generate a uniformly random double in
 * `[min, max)` (max exclusive, conventional for double ranges).
 * Defaults to the unit interval `[0.0, 1.0)`.
 */
case class RandomDoubleInput(min: Double = 0.0,
                             max: Double = 1.0,
                             seed: Option[Long] = None)
  extends ToolInput derives RW
