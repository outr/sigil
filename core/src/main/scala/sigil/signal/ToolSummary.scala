package sigil.signal

import fabric.rw.*
import lightdb.id.Id
import sigil.SpaceId
import sigil.tool.{Tool, ToolKind}

/**
 * Wire-friendly projection of a [[Tool]] — the metadata a UI needs
 * to render a "your tools" panel without loading the tool's full
 * record (which carries an [[Tool.execute]] closure and an opaque
 * [[fabric.define.Definition]] for the input schema).
 *
 * Used by [[ToolListSnapshot]] in response to [[RequestToolList]].
 *
 * `kind` is the open [[ToolKind]] PolyType the tool declares; UI
 * filters off this to show "just script tools" or "just MCP tools."
 * `space` is the single space the tool is visible under (per the
 * framework's single-assignment rule). `createdBy` is the
 * participant id of whoever authored the tool — `None` for
 * framework-shipped statics, populated for agent-authored tools
 * (e.g. `create_script_tool` writes the calling agent's id).
 */
case class ToolSummary(toolId: Id[Tool],
                       name: String,
                       description: String,
                       kind: ToolKind,
                       space: SpaceId,
                       createdBy: Option[String],
                       createdMs: Long,
                       modifiedMs: Long) derives RW

object ToolSummary {
  def fromTool(t: Tool): ToolSummary = ToolSummary(
    toolId      = t._id,
    name        = t.name.value,
    description = t.description,
    kind        = t.kind,
    space       = t.space,
    createdBy   = t.createdBy.map(_.value),
    createdMs   = t.created.value,
    modifiedMs  = t.modified.value
  )
}
