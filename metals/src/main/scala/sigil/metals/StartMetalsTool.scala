package sigil.metals

import fabric.rw.*
import rapid.{Stream, Task}
import sigil.TurnContext
import sigil.event.Event
import sigil.tool.{ToolExample, ToolInput, ToolName, TypedTool}

case class StartMetalsInput() extends ToolInput derives RW

/**
 * Start Metals for the current conversation's workspace. Looks the
 * workspace up via [[MetalsSigil.metalsWorkspace]]; replies with an
 * explanatory message when the conversation has no mapping rather
 * than spawning a detached subprocess.
 *
 * Idempotent — calling against an already-running workspace just
 * touches the last-used clock so the reaper postpones teardown.
 *
 * Metals' MCP tools (`find-symbol`, `compile`, `test`, …) become
 * discoverable via `find_capability` once the subprocess is up; the
 * agent doesn't call them through this tool — it discovers them on
 * the next `find_capability` keyed against whatever it needs.
 */
final class StartMetalsTool extends TypedTool[StartMetalsInput](
  name = ToolName("start_metals"),
  description =
    """Start the Metals (Scala LSP) MCP server for this conversation's workspace. Once running,
      |Metals' tools (find-symbol, compile, test, etc.) become discoverable via find_capability.
      |Idempotent — calling a second time just keeps the existing subprocess alive.""".stripMargin,
  examples = List(ToolExample("start metals here", StartMetalsInput()))
) {
  import MetalsToolSupport.*

  override protected def executeTyped(input: StartMetalsInput, context: TurnContext): Stream[Event] = {
    val sigil = context.sigil
    Stream.force(workspaceFor(sigil, context).flatMap {
      case Left(msg) =>
        Task.pure(Stream.emit[Event](reply(context, msg, isError = true)))
      case Right(workspace) =>
        manager(sigil) match {
          case None =>
            Task.pure(Stream.emit[Event](reply(
              context,
              "start_metals: this Sigil instance doesn't include sigil-metals — mix in MetalsSigil to enable.",
              isError = true
            )))
          case Some(mm) =>
            mm.ensureRunning(workspace).map { name =>
              Stream.emit[Event](reply(
                context,
                s"Metals running for $workspace (server name: $name)"
              ))
            }.handleError { t =>
              Task.pure(Stream.emit[Event](reply(
                context,
                s"start_metals failed: ${t.getMessage}",
                isError = true
              )))
            }
        }
    })
  }
}
