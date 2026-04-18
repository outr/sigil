package sigil.tool

import fabric.define.Definition
import fabric.rw.*
import lightdb.id.Id

case class ToolSchema[Input <: ToolInput](id: Id[ToolSchema[Input]],
                                          name: String,
                                          description: String,
                                          input: Definition,
                                          examples: List[ToolExample[Input]]) derives RW
