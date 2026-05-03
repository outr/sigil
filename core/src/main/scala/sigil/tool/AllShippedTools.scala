package sigil.tool

import sigil.SpaceId
import sigil.tool.consult.{ConsultTool, ExtractMemoriesTool, RerankTool, SummarizationTool}
import sigil.tool.core.ChangeModeTool
import sigil.tool.fs.{BashTool, DeleteFileTool, EditFileTool, FileSystemContext, GlobTool, GrepTool, ReadFileTool, WriteFileTool}
import sigil.tool.memory.{ForgetMemoryTool, MemoryHistoryTool}
import sigil.tool.util.{LookupTool, SaveMemoryTool, SearchConversationTool, SemanticSearchTool, SleepTool, SystemStatsTool}
import sigil.tool.web.WebFetchTool

import scala.concurrent.duration.*

/**
 * Every framework-shipped tool that can be instantiated with no
 * consumer-supplied configuration beyond a [[FileSystemContext]] and
 * the [[SpaceId]] for `save_memory`. Drop-in for `Sigil.staticTools`
 * overrides:
 *
 * {{{
 *   override def staticTools: List[Tool] =
 *     super.staticTools ++ AllShippedTools(LocalFileSystemContext(), space = MySpace)
 * }}}
 *
 * `super.staticTools` already supplies [[sigil.tool.core.CoreTools.all]]
 * — the framework essentials (`respond`, `change_mode`, `stop`, …) —
 * so the typical chain is `super.staticTools ++ AllShippedTools(...)`.
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
 *
 * Apps wanting to opt OUT of any individual tool filter the result:
 *
 * {{{
 *   override def staticTools: List[Tool] =
 *     super.staticTools ++
 *       AllShippedTools(LocalFileSystemContext(), MySpace).filterNot(_.name == ToolName("bash"))
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
    * @param webFetchTimeout HTTP timeout for `web_fetch`
    */
  def apply(fs: FileSystemContext,
            space: SpaceId,
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
    // Lookup / search / housekeeping.
    LookupTool,
    new SaveMemoryTool(space),
    SearchConversationTool,
    SemanticSearchTool,
    SleepTool,
    new SystemStatsTool(fs),
    // Filesystem.
    new BashTool(fs),
    new DeleteFileTool(fs),
    new EditFileTool(fs),
    new GlobTool(fs),
    new GrepTool(fs),
    new ReadFileTool(fs),
    new WriteFileTool(fs),
    // Web.
    new WebFetchTool(webFetchTimeout)
  )
}
