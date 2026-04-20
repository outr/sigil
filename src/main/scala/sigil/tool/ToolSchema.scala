package sigil.tool

import fabric.define.Definition
import fabric.rw.*
import lightdb.id.Id

case class ToolSchema[Input <: ToolInput](id: Id[ToolSchema[Input]],
                                          name: String,
                                          description: String,
                                          input: Definition,
                                          examples: List[ToolExample[Input]]) derives RW

object ToolSchema {
  import ToolInput.given

  /**
   * Existential RW used by carriers like `sigil.event.ToolResults` that hold
   * `List[ToolSchema[? <: ToolInput]]`. Delegates to the macro-derived
   * `RW[ToolSchema[ToolInput]]`, serializing each example's input via the
   * `ToolInput` poly. Concrete `ToolSchema[Input]` RWs continue to come from
   * this class's own `derives RW`.
   */
  given erasedRW: RW[ToolSchema[? <: ToolInput]] =
    summon[RW[ToolSchema[ToolInput]]].asInstanceOf[RW[ToolSchema[? <: ToolInput]]]
}
