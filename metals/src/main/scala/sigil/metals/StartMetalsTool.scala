package sigil.metals

import fabric.rw.*
import rapid.Task
import sigil.tool.ToolContext
import sigil.tool.{TextToolOutput, Tool, ToolExample, ToolInput, ToolName, ToolResult}

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
final class StartMetalsTool extends Tool {
  type Input  = StartMetalsInput
  type Output = TextToolOutput
  val inputRW  = summon[RW[StartMetalsInput]]
  val outputRW = summon[RW[TextToolOutput]]

  val name = ToolName("start_metals")
  val description =
    """Start the Metals (Scala LSP) MCP server for this conversation's workspace. Once running,
      |Metals' tools (find-symbol, compile, test, etc.) become discoverable via find_capability.
      |Idempotent — calling a second time just keeps the existing subprocess alive.""".stripMargin
  override val examples = List(ToolExample("start metals here", StartMetalsInput()))
  override val keywords = Set(
    "metals", "start", "scala", "lsp", "language",
    "compile", "indexing", "spawn", "boot", "enable",
    "code", "tooling", "ide"
  )

  import MetalsToolSupport.*

  override def executeResult(input: StartMetalsInput, context: ToolContext): Task[ToolResult[TextToolOutput]] = {
    val sigil = context.sigil
    workspaceFor(sigil, context).flatMap {
      case Left(msg) =>
        Task.pure(ToolResult.failure(msg))
      case Right(workspace) =>
        manager(sigil) match {
          case None =>
            Task.pure(ToolResult.failure(
              "start_metals: this Sigil instance doesn't include sigil-metals — mix in MetalsSigil to enable."
            ))
          case Some(mm) =>
            // Fan Metals' stdout into the chat chip via `ToolLog` events.
            // The callback runs on the drainer thread; `context.toolLog`
            // pairs each line to this tool's ToolInvoke via `currentToolInvokeId`.
            val onLogLine: String => Task[Unit] =
              line => context.toolLog(line)
            val onStatus: String => Task[Unit] =
              text => context.reportProgress(text)
            // Initial pulse so the chip isn't blank during the
            // 1-3 second window before Metals' first
            // `metals/status` notification arrives.
            context.reportProgress(s"Spawning Metals for ${workspace.getFileName}…").flatMap { _ =>
            mm.ensureRunning(workspace, onLogLine = Some(onLogLine), onStatus = Some(onStatus)).flatMap { name =>
              val lspConfigWrite = metalsHost(sigil).map(_.writeLspServerConfigForMetals(workspace))
                .getOrElse(Task.unit)
                .handleError { t =>
                  Task(scribe.warn(s"start_metals: LspServerConfig upsert for 'scala' failed: ${t.getMessage}"))
                }
              // Make the LSP/BSP/metals_* tool family DISCOVERABLE for
              // this conversation — surfaced by `find_capability` on
              // intent, NOT pinned into the per-turn roster. Pinning all
              // ~41 (the earlier `Active` policy) defeated the lean-roster
              // design, bloated every request, and on reasoning models
              // over some gateways the oversized tool payload tipped the
              // stream into truncation. Discovery-only keeps the roster at
              // the 4-tool baseline; the agent pays one `find_capability`
              // hop before first use of a family. `stop_metals` removes
              // the overlay.
              val overlayInstall = sigil.addConversationToolOverlay(
                _root_.sigil.conversation.ConversationToolOverlay(
                  conversationId = context.conversation.id,
                  source         = MetalsBoostedToolNames.OverlaySource,
                  policy         = _root_.sigil.provider.ToolPolicy.Discoverable(MetalsBoostedToolNames.all)
                )
              ).handleError { t =>
                Task(scribe.warn(s"start_metals: ConversationToolOverlay install failed: ${t.getMessage}"))
              }
              lspConfigWrite
                .flatMap(_ => overlayInstall)
                .map(_ => ToolResult.Success(TextToolOutput(
                  s"Metals running for $workspace (server name: $name)"
                )))
            }.handleError { t =>
              Task.pure(ToolResult.failure(s"start_metals failed: ${t.getMessage}"))
            }
            }
        }
    }
  }

  /** Returns the [[MetalsSigil]] handle when the host mixes it in. */
  private def metalsHost(host: _root_.sigil.Sigil): Option[MetalsSigil] = host match {
    case ms: MetalsSigil => Some(ms)
    case _               => None
  }
}
