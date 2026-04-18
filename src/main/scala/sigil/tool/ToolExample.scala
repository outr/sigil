package sigil.tool

import fabric.rw.*

case class ToolExample[Input <: ToolInput](description: String, input: Input) derives RW
