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

  override def byName(name: ToolName): Task[Option[Tool]] =
    manager.allToolsByDisplayName.map { all =>
      all.get(name.value).map { case (cfg, td) => new McpTool(manager, cfg, td) }
    }

  override def apply(request: DiscoveryRequest): Task[List[Tool]] =
    manager.allToolsByDisplayName.map { all =>
      val candidates: List[Tool] = all.values.toList.map { case (cfg, td) =>
        new McpTool(manager, cfg, td)
      }
      candidates.filter(t => DiscoveryFilter.matches(t, request))
    }
}
