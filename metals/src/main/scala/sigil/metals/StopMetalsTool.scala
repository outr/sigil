package sigil.metals

import fabric.rw.*
import rapid.Task
import sigil.tool.ToolContext
import sigil.tool.{TextToolOutput, Tool, ToolExample, ToolInput, ToolName, ToolResult}

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
final class StopMetalsTool extends Tool {
  type Input = StopMetalsInput
  type Output = TextToolOutput
  val inputRW = summon[RW[StopMetalsInput]]
  val outputRW = summon[RW[TextToolOutput]]

  val name = ToolName("stop_metals")
  val description =
    """Stop the Metals (Scala LSP) MCP server for this conversation's workspace.
      |
      |DESTRUCTIVE to in-flight semantic services: all diagnostics, navigation, and any
      |validation depending on the running server stop immediately, and the next start pays the
      |full build re-import (often minutes of indexing before semantic answers return). Never
      |part of a normal workflow — do NOT call this to "refresh", "restart", or "prepare"
      |anything; a running server is already the prepared state. Appropriate ONLY when the user
      |explicitly asks to stop Metals or to free its resources.
      |
      |Tears down the subprocess and removes its McpServerConfig.
      |No-op if Metals isn't running for the workspace.""".stripMargin
  override val examples = List(ToolExample("stop metals", StopMetalsInput()))
  override val keywords = Set(
    "metals",
    "stop",
    "scala",
    "lsp",
    "shutdown",
    "kill",
    "terminate",
    "disable",
    "teardown",
    "tooling"
  )

  import MetalsToolSupport.*

  override def executeResult(input: StopMetalsInput, context: ToolContext): Task[ToolResult[TextToolOutput]] = {
    val sigil = context.sigil
    workspaceFor(sigil, context).flatMap {
      case Left(msg) =>
        Task.pure(ToolResult.failure(msg))
      case Right(workspace) =>
        manager(sigil) match {
          case None =>
            Task.pure(ToolResult.failure(
              "stop_metals: this Sigil instance doesn't include sigil-metals."
            ))
          case Some(mm) =>
            mm.stop(workspace).flatMap { stopped =>
              sigil.removeConversationToolOverlay(
                context.conversation.id,
                MetalsBoostedToolNames.OverlaySource
              ).handleError { t =>
                Task(scribe.warn(s"stop_metals: ConversationToolOverlay remove failed: ${t.getMessage}"))
              }.map { _ =>
                val msg =
                  if (stopped) s"Metals stopped for $workspace."
                  else s"No Metals running for $workspace — nothing to stop."
                ToolResult.Success(TextToolOutput(msg))
              }
            }
        }
    }
  }
}
