package sigil.mcp

import fabric.rw.*
import rapid.Task
import sigil.tool.ToolContext
import sigil.tool.{
  DiscoverySpec,
  Effect,
  Freshness,
  Resolution,
  TextToolOutput,
  Tool,
  ToolIO,
  ToolInput,
  ToolName,
  ToolProfile,
  ToolResult,
  ToolSpec
}

case class ListMcpServersInput() extends ToolInput derives RW

/**
 * Return the names + transport summary of every registered MCP server.
 */
final class ListMcpServersTool(manager: McpManager) extends Tool {
  type Input = ListMcpServersInput
  type Output = TextToolOutput
  val io: ToolIO[ListMcpServersInput, TextToolOutput] = ToolIO.derived[ListMcpServersInput, TextToolOutput]

  override val name = ToolName("list_mcp_servers")
  override val description = "List the registered MCP servers — name, transport, prefix, and the count of tools each currently exposes."
  val spec: ToolSpec = ToolSpec(
    name = name,
    description = description,
    profile = ToolProfile(effect = Effect.ReadOnly(Freshness.Stable)),
    discovery = DiscoverySpec(keywords = Set("mcp", "server", "servers", "list", "registered"))
  )

  protected def resolve: Resolution[Input, Output] = Resolution.Explicit(executeResult)

  private def executeResult(input: ListMcpServersInput, context: ToolContext): Task[ToolResult[TextToolOutput]] =
    manager.listConfigs().flatMap { configs =>
      Task.sequence(configs.map { cfg =>
        manager.listTools(cfg.name).map(_.size).handleError(_ => Task.pure(-1)).map { count =>
          val transport = cfg.transport match {
            case McpTransport.Stdio(cmd, args) => s"stdio: $cmd ${args.mkString(" ")}".trim
            case McpTransport.HttpSse(url, _) => s"http: $url"
          }
          val countStr = if (count >= 0) s"$count tools" else "(unreachable)"
          val prefixDisplay = cfg.prefix.fold("(no prefix)")(p => s"$p*")
          s"- ${cfg.name} ($prefixDisplay) — $transport — $countStr"
        }
      }).map { lines =>
        val text = if (lines.isEmpty) "(no MCP servers registered)" else lines.mkString("\n")
        ToolResult.Success(TextToolOutput(text))
      }
    }
}
