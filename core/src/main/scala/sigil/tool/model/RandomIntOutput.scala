package sigil.tool.model

import fabric.rw.*

/**
 * Result of a `random_int` draw. `seed` echoes the request's seed
 * (when supplied) so reproducible runs round-trip cleanly through
 * the tool boundary.
 */
case class RandomIntOutput(value: Long,
                           min: Long,
                           max: Long,
                           seed: Option[Long])
  derives RW
