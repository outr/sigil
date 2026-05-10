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
  examples = List(ToolExample("start metals here", StartMetalsInput())),
  keywords = Set(
    "metals", "start", "scala", "lsp", "language",
    "compile", "indexing", "spawn", "boot", "enable",
    "code", "tooling", "ide"
  )
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
            // Bug #69 — fan Metals' stdout into the chat chip via
            // `ToolLog` events. The callback runs on the drainer
            // thread; `context.toolLog` pairs each line to this
            // tool's ToolInvoke via `currentToolInvokeId`.
            val onLogLine: String => Task[Unit] =
              line => context.toolLog(line)
            mm.ensureRunning(workspace, onLogLine = Some(onLogLine)).flatMap { name =>
              // Bug #88 — also write LspServerConfig("scala") so
              // the framework's generic lsp_* tools find Metals as
              // their backend. No-op for apps without
              // ToolingCollections in their DB.
              val lspConfigWrite = metalsHost(sigil).map(_.writeLspServerConfigForMetals(workspace))
                .getOrElse(Task.unit)
                .handleError { t =>
                  Task(scribe.warn(s"start_metals: LspServerConfig upsert for 'scala' failed: ${t.getMessage}"))
                }
              // Bug #97 — pin the LSP/BSP/metals_* tool family to
              // this conversation so subsequent turns can call
              // them directly without a `find_capability` round-
              // trip. `stop_metals` removes the overlay.
              val overlayInstall = sigil.addConversationToolOverlay(
                _root_.sigil.conversation.ConversationToolOverlay(
                  conversationId = context.conversation.id,
                  source         = MetalsBoostedToolNames.OverlaySource,
                  policy         = _root_.sigil.provider.ToolPolicy.Active(MetalsBoostedToolNames.all)
                )
              ).handleError { t =>
                Task(scribe.warn(s"start_metals: ConversationToolOverlay install failed: ${t.getMessage}"))
              }
              lspConfigWrite
                .flatMap(_ => overlayInstall)
                .map(_ => Stream.emit[Event](reply(
                  context,
                  s"Metals running for $workspace (server name: $name)"
                )))
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

  /** Returns the [[MetalsSigil]] handle when the host mixes it in. */
  private def metalsHost(host: _root_.sigil.Sigil): Option[MetalsSigil] = host match {
    case ms: MetalsSigil => Some(ms)
    case _               => None
  }
}
