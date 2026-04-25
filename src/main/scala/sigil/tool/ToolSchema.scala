package sigil.tool

import fabric.define.Definition
import fabric.rw.*
import lightdb.id.Id

/**
 * Render-ready descriptor of a [[Tool]]: the data providers serialize into
 * each LLM request's tool list. Computed by `Tool.schema` from a tool's
 * name, description, input RW definition, and examples.
 */
case class ToolSchema(id: Id[ToolSchema],
                     name: ToolName,
                     description: String,
                     input: Definition,
                     examples: List[ToolExample])
  derives RW
