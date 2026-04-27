package sigil.mcp

import fabric.rw.RW
import rapid.Task
import sigil.tool.{DiscoveryFilter, DiscoveryRequest, Tool, ToolFinder, ToolInput, ToolName}

/**
 * [[ToolFinder]] surfacing every MCP-advertised tool across all
 * configured [[McpServerConfig]]s. Apps compose this with the
 * framework's local finder via a chained / merged finder if they
 * have one, or use it standalone.
 */
final class McpToolFinder(manager: McpManager) extends ToolFinder {

  /** All MCP tools surface via [[JsonInput]] — the only ToolInput
    * subclass the finder produces. */
  override def toolInputRWs: List[RW[? <: ToolInput]] = List(summon[RW[JsonInput]])

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
