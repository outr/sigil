package sigil.debug

import rapid.Task
import sigil.Sigil
import sigil.db.SigilDB
import sigil.tool.Tool

import scala.concurrent.duration.*

/**
 * Sigil refinement for apps that include the `sigil-debug` module.
 *
 * Constrains `type DB` to a [[SigilDB]] subclass mixing in
 * [[DebugCollections]] (so `db.debugAdapters` is reachable),
 * exposes [[dapManager]], and — when [[debugToolsEnabled]] is true
 * — adds the DAP tools to `staticTools`:
 *
 *   - `dap_launch` — start a fresh debug session
 *   - `dap_attach` — attach to a running process
 *   - `dap_set_breakpoints` — configure source breakpoints
 *   - `dap_set_exception_breakpoints` — configure exception filters
 *   - `dap_continue` / `dap_step_over` / `dap_step_in` / `dap_step_out` / `dap_pause`
 *   - `dap_threads` / `dap_stack_trace` / `dap_scopes` / `dap_variables`
 *   - `dap_evaluate` — REPL-style expression evaluation
 *   - `dap_session_status` — current session state (running / stopped / output / etc.)
 *   - `dap_disconnect` / `dap_list_sessions`
 *
 * Together with the LSP / BSP tools in `sigil-tooling`, this rounds
 * out "Sage as IDE" — agents can edit, compile, test, AND debug.
 */
trait DebugSigil extends Sigil {
  type DB <: SigilDB & DebugCollections

  def debugToolsEnabled: Boolean = true

  /** Idle-sweep cadence — sessions whose idle time exceeds their
    * config's `idleTimeoutMs` are torn down. */
  def debugIdleSweepInterval: FiniteDuration = 1.minute

  final lazy val dapManager: DapManager =
    new DapManager(this.asInstanceOf[Sigil { type DB <: SigilDB & DebugCollections }])

  override def staticTools: List[Tool] = {
    val base = super.staticTools
    if (debugToolsEnabled) base ++ debugTools else base
  }

  protected def debugTools: List[Tool] = List(
    new DapLaunchTool(dapManager),
    new DapAttachTool(dapManager),
    new DapSetBreakpointsTool(dapManager),
    new DapSetExceptionBreakpointsTool(dapManager),
    new DapContinueTool(dapManager),
    new DapStepOverTool(dapManager),
    new DapStepInTool(dapManager),
    new DapStepOutTool(dapManager),
    new DapPauseTool(dapManager),
    new DapThreadsTool(dapManager),
    new DapStackTraceTool(dapManager),
    new DapScopesTool(dapManager),
    new DapVariablesTool(dapManager),
    new DapEvaluateTool(dapManager),
    new DapSessionStatusTool(dapManager),
    new DapListSessionsTool(dapManager),
    new DapDisconnectTool(dapManager)
  )

  private def sweepLoop(): Task[Unit] = Task.defer {
    Task.sleep(debugIdleSweepInterval)
      .flatMap(_ => dapManager.sweepIdle().handleError(_ => Task.unit))
      .flatMap(_ => sweepLoop())
  }

  protected def startDebugIdleSweep(): Task[Unit] = Task {
    sweepLoop().startUnit()
    ()
  }

  startDebugIdleSweep().sync()
}
