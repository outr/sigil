package sigil.metals

import fabric.rw.*
import rapid.{Stream, Task}
import sigil.TurnContext
import sigil.event.Event
import sigil.tool.{ToolExample, ToolInput, ToolName, TypedTool}

case class StopMetalsInput() extends ToolInput derives RW

/**
 * Stop the Metals subprocess for the current conversation's
 * workspace. Removes the matching `McpServerConfig` so
 * [[sigil.mcp.McpManager]] tears down its connection on the next
 * idle sweep.
 *
 * No-op when no Metals is running for the workspace. Use
 * `metals_status` first to see what's live.
 */
final class StopMetalsTool extends TypedTool[StopMetalsInput](
  name = ToolName("stop_metals"),
  description =
    """Stop the Metals (Scala LSP) MCP server for this conversation's workspace.
      |Tears down the subprocess and removes its McpServerConfig.
      |No-op if Metals isn't running for the workspace.""".stripMargin,
  examples = List(ToolExample("stop metals", StopMetalsInput()))
) {
  import MetalsToolSupport.*

  override protected def executeTyped(input: StopMetalsInput, context: TurnContext): Stream[Event] = {
    val sigil = context.sigil
    Stream.force(workspaceFor(sigil, context).flatMap {
      case Left(msg) =>
        Task.pure(Stream.emit[Event](reply(context, msg, isError = true)))
      case Right(workspace) =>
        manager(sigil) match {
          case None =>
            Task.pure(Stream.emit[Event](reply(
              context,
              "stop_metals: this Sigil instance doesn't include sigil-metals.",
              isError = true
            )))
          case Some(mm) =>
            mm.stop(workspace).map { stopped =>
              val msg =
                if (stopped) s"Metals stopped for $workspace."
                else s"No Metals running for $workspace — nothing to stop."
              Stream.emit[Event](reply(context, msg))
            }
        }
    })
  }
}
