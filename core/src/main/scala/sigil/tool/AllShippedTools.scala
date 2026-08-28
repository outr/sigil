package sigil.tool

import sigil.SpaceId
import sigil.tool.consult.{ConsultTool, ExtractMemoriesTool, RerankTool, SummarizationTool}
import sigil.tool.core.{CancelFrameworkWorkflowTool, ChangeModeTool}
import sigil.tool.fs.{BashTool, DeleteFileTool, EditAtRangeTool, EditFileTool, FileSystemContext, GlobTool, GrepTool, ReadFileTool, WriteFileTool}
import sigil.tool.git.{GitBranchTool, GitDiffTool, GitLogTool, GitShowTool, GitStatusTool}
import sigil.tool.memory.{ForgetMemoryTool, MemoryHistoryTool}
import sigil.tool.process.{ProcessListTool, ProcessOutputTool, ProcessRegistry, ProcessSignalTool, ProcessSpawnTool}
import sigil.tool.util.{RelayMessageTool, SaveMemoryTool, SearchConversationTool, SemanticSearchTool, SleepTool, SystemStatsTool}
import sigil.tool.web.{HttpRequestTool, WebFetchTool}

import scala.concurrent.duration.*

/**
 * Every framework-shipped tool that can be instantiated with no
 * consumer-supplied configuration beyond a [[FileSystemContext]] and
 * the [[SpaceId]] for `save_memory`. Drop-in for `Sigil.staticTools`
 * overrides:
 *
 * {{{
 *   private lazy val processRegistry = new ProcessRegistry()
 *
 *   override def staticTools: List[Tool] =
 *     super.staticTools ++
 *       AllShippedTools(LocalFileSystemContext(), MySpace, Some(processRegistry))
 * }}}
 *
 * `super.staticTools` already supplies [[sigil.tool.core.CoreTools.all]]
 * — the framework essentials (`respond`, `change_mode`, `stop`, …) —
 * so the typical chain is `super.staticTools ++ AllShippedTools(...)`.
 *
 * `processRegistry` is REQUIRED (not defaulted) because the registry's
 * lifetime is a real decision the call site owns: the `process_*`
 * tools only round-trip handles when all four share ONE in-memory map,
 * and that map must outlive any single `staticTools` evaluation.
 * Forcing the parameter puts a `lazy val` at the call site instead of
 * a `new ProcessRegistry()` buried in a helper. Apps that don't want
 * the `process_*` family pass `None`.
 *
 * Excluded (need consumer-supplied configuration that has no sensible
 * default):
 *   - `WebSearchTool` — requires a [[sigil.tool.web.SearchProvider]];
 *     framework ships no concrete search provider.
 *   - `TopicClassifierTool` — constructed per-call with the prior
 *     topic labels for the conversation; not a static singleton.
 *   - `ProxyTool` — a wrapper that reroutes another tool's execution
 *     via a `ToolProxyTransport`; only meaningful around an existing
 *     concrete tool.
 *   - `GitCommitTool` / `GitPushTool` — write external state; opt-in
 *     like `delete_file` is opt-out. Apps that want commit / push
 *     authorship register `new GitCommitTool(fs)` / `new
 *     GitPushTool(fs)` explicitly. Their input RWs are pre-registered
 *     in `CoreTools.inputRWs` so persisted events round-trip even
 *     when the tool itself isn't in the roster.
 *
 * Apps wanting to opt OUT of any individual tool filter the result:
 *
 * {{{
 *   override def staticTools: List[Tool] =
 *     super.staticTools ++
 *       AllShippedTools(LocalFileSystemContext(), MySpace, Some(processRegistry))
 *         .filterNot(_.name == ToolName("bash"))
 * }}}
 *
 * When Sigil adds (or removes) a shipped tool, this list is the
 * canonical place to keep up to date — every consumer using the helper
 * picks up the change for free.
 */
object AllShippedTools {

  /** All non-core shipped tools, instantiated with reasonable
    * defaults. Pair with `super.staticTools` in your `Sigil.staticTools`
    * override; the framework's [[sigil.tool.core.CoreTools]] essentials
    * come from `super`.
    *
    * @param fs              filesystem context for tools that touch disk
    *                        (`bash`, `read_file`, `write_file`, `edit_file`,
    *                        `delete_file`, `glob`, `grep`, `system_stats`)
    * @param space           memory-space discriminator for `save_memory`
    * @param processRegistry shared in-memory subprocess registry for the
    *                        `process_*` tools; pass `Some(reg)` with `reg`
    *                        hoisted to a `lazy val` on the calling Sigil
    *                        so all four tools share one handle map.
    *                        `None` omits the four `process_*` tools from
    *                        the result.
    * @param webFetchTimeout HTTP timeout for `web_fetch`
    */
  def apply(fs: FileSystemContext,
            space: SpaceId,
            processRegistry: Option[ProcessRegistry],
            webFetchTimeout: FiniteDuration = 30.seconds): List[Tool] = List(
    // Mode switching — opt-in for multi-mode apps. Single-mode apps
    // omit this list or filter it out.
    ChangeModeTool,
    // Consult / classifier helpers (per-turn one-shot agents).
    ConsultTool,
    ExtractMemoriesTool,
    RerankTool,
    SummarizationTool,
    // Memory-store CRUD.
    ForgetMemoryTool,
    MemoryHistoryTool,
    // Lookup / search / housekeeping. (`lookup` itself moved to
    // `CoreTools.all` — always registered, conditionally advertised —
    // so it is not repeated here.)
    new SaveMemoryTool(space),
    SearchConversationTool,
    RelayMessageTool,
    // Worker delegation (sigil #327) — spawn a worker agent in a
    // sub-conversation and supervise it; pure conversations + agents,
    // no workflow runtime required.
    sigil.tool.util.DelegateTaskTool,
    SemanticSearchTool,
    SleepTool,
    new SystemStatsTool(fs),
    // Framework-workflow control — cancel an in-flight framework
    // operation (pre-flight, compress, …) by id.
    CancelFrameworkWorkflowTool,
    // Filesystem.
    new BashTool(fs),
    new DeleteFileTool(fs),
    new EditFileTool(fs),
    new EditAtRangeTool(fs),
    new GlobTool(fs),
    new GrepTool(fs),
    new ReadFileTool(fs),
    new WriteFileTool(fs),
    // Git — read-only family. `git_commit` / `git_push` write and ship
    // separately; apps that want commit / push authorship register
    // `new GitCommitTool(fs)` / `new GitPushTool(fs)` explicitly,
    // mirroring how `delete_file` is gated.
    new GitStatusTool(fs),
    new GitDiffTool(fs),
    new GitLogTool(fs),
    new GitBranchTool(fs),
    new GitShowTool(fs),
    // Web.
    new WebFetchTool(webFetchTimeout),
    HttpRequestTool
  ) ++ processRegistry.toList.flatMap(reg => List(
    new ProcessSpawnTool(reg),
    new ProcessOutputTool(reg),
    new ProcessSignalTool(reg),
    new ProcessListTool(reg)
  ))
}
