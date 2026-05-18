package sigil.tool.model

import fabric.rw.*

case class RandomDoubleOutput(value: Double,
                              min: Double,
                              max: Double,
                              seed: Option[Long])
  derives RW
