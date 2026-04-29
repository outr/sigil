package sigil.tooling

import rapid.Task
import sigil.Sigil
import sigil.db.SigilDB
import sigil.tool.Tool

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

  override def staticTools: List[Tool] = {
    val base = super.staticTools
    if (toolingToolsEnabled) base ++ toolingTools else base
  }

  protected def toolingTools: List[Tool] = List(
    new LspDiagnosticsTool(lspManager),
    new LspGotoDefinitionTool(lspManager),
    new LspHoverTool(lspManager),
    new BspCompileTool(bspManager)
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
}
