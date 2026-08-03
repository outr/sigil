package spec.collidea

import fabric.rw.*
import sigil.tool.ToolInput

/** Shares its SIMPLE class name with `spec.collideb.CollidingProbeInput`.
  * Fabric's polymorphic dispatch keys on the lowercased simple name, so
  * registering both silently shadows one — the collision
  * [[sigil.tool.BootCompletenessCheck]] must report. */
case class CollidingProbeInput(value: String) extends ToolInput derives RW
