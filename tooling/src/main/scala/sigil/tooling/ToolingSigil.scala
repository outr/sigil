package sigil.tooling

import rapid.Task
import sigil.Sigil
import sigil.db.SigilDB
import sigil.tool.Tool
import sigil.tool.fs.{FileSystemContext, LocalFileSystemContext}
import sigil.tooling.dispatch.{DispatchWorkersTool, WorkerItemSourceAdapter}

import scala.concurrent.duration.*

/**
 * Sigil refinement for apps that include the `sigil-tooling` module.
 *
 * Constrains `type DB` to a [[SigilDB]] subclass mixing in
 * [[ToolingCollections]] (so `db.lspServers` / `db.bspBuilds` are
 * reachable), exposes [[lspManager]] / [[bspManager]] hooks, and —
 * when [[toolingToolsEnabled]] is true — adds the LSP + BSP
 * proof-of-concept tools to `staticTools`:
 *
 *   - `lsp_diagnostics` — current diagnostics for an open file
 *   - `lsp_goto_definition` — locate a symbol's definition
 *   - `lsp_hover` — type signature + docs at a position
 *   - `bsp_compile` — compile a build target via sbt / Bloop
 *
 * This is the foundation for "Sage as IDE" — agents get the same
 * structured grounding human developers get from Metals,
 * rust-analyzer, etc.
 */
trait ToolingSigil extends Sigil {
  type DB <: SigilDB & ToolingCollections

  /** Whether the framework's LSP + BSP tools are appended to
    * `staticTools`. Default true. Apps that want a locked-down
    * agent surface override to false and register a curated subset. */
  def toolingToolsEnabled: Boolean = true

  /** How often [[lspManager]] / [[bspManager]] sweep idle sessions.
    * Default once per minute — sessions whose idle time exceeds
    * their config's `idleTimeoutMs` are torn down. */
  def toolingIdleSweepInterval: FiniteDuration = 1.minute

  final lazy val lspManager: LspManager =
    new LspManager(this.asInstanceOf[Sigil { type DB <: SigilDB & ToolingCollections }])

  final lazy val bspManager: BspManager =
    new BspManager(this.asInstanceOf[Sigil { type DB <: SigilDB & ToolingCollections }])

  /** Filesystem context for tools the mixin wires that touch disk
    * (currently `dispatch_workers`'s `FromFile` adapter). Defaults to
    * a sandbox-less local filesystem; apps with a workspace root
    * override to scope the agent's reach. */
  def fileSystemContext: FileSystemContext = new LocalFileSystemContext(basePath = None)

  override def staticTools: List[Tool] = {
    val base = super.staticTools
    if (toolingToolsEnabled) base ++ toolingTools else base
  }

  protected def toolingTools: List[Tool] =
    lspTools ++ bspTools ++ dispatchTools

  /** Every LSP-side tool the framework ships. Apps that want a
    * subset override this and pick. */
  protected def lspTools: List[Tool] = List(
    // Phase 0 — base
    new LspDiagnosticsTool(lspManager),
    new LspGotoDefinitionTool(lspManager),
    new LspHoverTool(lspManager),
    // Phase 1 — live editing
    new LspDidChangeTool(lspManager),
    new LspCompletionTool(lspManager),
    new LspSignatureHelpTool(lspManager),
    new LspCodeActionTool(lspManager),
    new LspApplyCodeActionTool(lspManager),
    new LspFormatTool(lspManager),
    new LspFormatRangeTool(lspManager),
    new LspRenameTool(lspManager),
    new LspRenameSymbolTool(lspManager),
    new LspPrepareRenameTool(lspManager),
    // Phase 2 — navigation
    new LspFindReferencesTool(lspManager),
    new LspTypeDefinitionTool(lspManager),
    new LspImplementationTool(lspManager),
    new LspDocumentSymbolsTool(lspManager),
    new LspWorkspaceSymbolsTool(lspManager),
    new LspFoldingRangeTool(lspManager),
    new LspSelectionRangeTool(lspManager),
    // Phase 4 — pull diagnostics + extras
    new LspPullDiagnosticsTool(lspManager),
    new LspInlayHintsTool(lspManager),
    new LspCodeLensTool(lspManager),
    new LspDocumentLinkTool(lspManager)
  )

  /** Every BSP-side tool the framework ships. */
  protected def bspTools: List[Tool] = List(
    new BspListTargetsTool(bspManager),
    new BspCompileTool(bspManager),
    new BspTestTool(bspManager),
    new BspRunTool(bspManager),
    new BspCleanTool(bspManager),
    new BspReloadTool(bspManager),
    new BspSourcesTool(bspManager),
    new BspInverseSourcesTool(bspManager),
    new BspDependencySourcesTool(bspManager),
    new BspDependencyModulesTool(bspManager),
    new BspResourcesTool(bspManager),
    new BspOutputPathsTool(bspManager),
    new BspScalacOptionsTool(bspManager),
    new BspScalaTestClassesTool(bspManager),
    new BspScalaMainClassesTool(bspManager)
  )

  /** Every dispatch-shaped tool the framework ships. The generic
    * [[DispatchWorkersTool]] subsumes the prior three-tool refactor
    * session — per-item LLM-or-script pipelines compose naturally
    * with `grep`, LSP queries, inline lists, files, and conversation
    * extracts. */
  protected def dispatchTools: List[Tool] = List(
    new DispatchWorkersTool()
  )

  /** Periodic idle sweep — runs forever on a daemon fiber. */
  private def sweepLoop(): Task[Unit] = Task.defer {
    Task.sleep(toolingIdleSweepInterval)
      .flatMap(_ => lspManager.sweepIdle().handleError(_ => Task.unit))
      .flatMap(_ => bspManager.sweepIdle().handleError(_ => Task.unit))
      .flatMap(_ => sweepLoop())
  }

  protected def startToolingIdleSweep(): Task[Unit] = Task {
    sweepLoop().startUnit()
    ()
  }

  startToolingIdleSweep().sync()

  // Bug #230 — register the framework-shipped WorkerItemSource
  // adapters for grep / lsp_find_references / lsp_workspace_symbols
  // so DispatchWorkersTool's FromCall variant projects their persisted
  // outputs into worker items without app-side wiring. Apps add their
  // own adapters via `WorkerItemSourceAdapter.register`.
  WorkerItemSourceAdapter.registerShipped()
}
