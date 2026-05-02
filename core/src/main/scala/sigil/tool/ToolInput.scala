package sigil.tool

import fabric.rw.PolyType

trait ToolInput

object ToolInput extends PolyType[ToolInput]()(using scala.reflect.ClassTag(classOf[ToolInput]))
