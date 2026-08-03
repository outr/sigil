package sigil.mcp

import fabric.define.{DefType, Definition}
import rapid.Task
import sigil.tool.{DiscoveryFilter, DiscoveryRequest, JsonInput, Tool, ToolFinder, ToolIO, ToolName, ToolOutput}

/**
 * [[ToolFinder]] surfacing every MCP-advertised tool across all
 * configured [[McpServerConfig]]s. Apps compose this with the
 * framework's local finder via a chained / merged finder if they
 * have one, or use it standalone.
 */
final class McpToolFinder(manager: McpManager) extends ToolFinder {

  /** All MCP tools surface via [[JsonInput]] and the open
    * [[ToolOutput]] (a server result may be text OR an image) — one
    * representative IO covers the codecs this finder's tools use. */
  override val toolIO: List[ToolIO[?, ?]] =
    List(ToolIO.dynamicAs[ToolOutput](Definition(DefType.Json)))

  /** Materialize every advertised tool with collision-resolved names —
    * two raw names that sanitize to one would otherwise silently
    * shadow, dropping a tool from the roster. */
  private def allTools: Task[List[McpTool]] =
    manager.allToolsByDisplayName.map { all =>
      val names = McpTool.resolveNames(all.keys)
      all.toList.map { case (raw, (cfg, td)) => new McpTool(manager, cfg, td, names.get(raw)) }
    }

  override def byName(name: ToolName): Task[Option[Tool]] =
    allTools.map(_.find(_.name == name))

  override def apply(request: DiscoveryRequest): Task[List[Tool]] =
    allTools.map(_.filter(t => DiscoveryFilter.matches(t, request)))
}
