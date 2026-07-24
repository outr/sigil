package sigil.mcp

import fabric.rw.*
import rapid.Task
import sigil.tool.ToolContext
import sigil.tool.{TextToolOutput, Tool, ToolInput, ToolName, ToolResult}

case class ListMcpServersInput() extends ToolInput derives RW

/**
 * Return the names + transport summary of every registered MCP server.
 */
final class ListMcpServersTool(manager: McpManager) extends Tool {
  type Input = ListMcpServersInput
  type Output = TextToolOutput
  val inputRW = summon[RW[ListMcpServersInput]]
  val outputRW = summon[RW[TextToolOutput]]

  val name = ToolName("list_mcp_servers")
  val description = "List the registered MCP servers — name, transport, prefix, and the count of tools each currently exposes."

  override def executeResult(input: ListMcpServersInput, context: ToolContext): Task[ToolResult[TextToolOutput]] =
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
