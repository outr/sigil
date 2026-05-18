package sigil.tooling.dispatch

import fabric.rw.*
import sigil.tool.ToolName

/**
 * Optional script step in a [[WorkerPipeline]]. Runs after the
 * pipeline's [[LlmStep]] (if any) and receives the LLM's parsed
 * output as the `input: fabric.Json` binding (or the raw worker
 * item, if no LLM step ran).
 *
 *   - `code`         — script source, evaluated against the host's
 *                       `ScriptExecutor`. The script can call any
 *                       tool listed in `allowedTools` via the
 *                       framework's `tools.callTool[...]` binding.
 *   - `language`     — language hint (default `"scala"`); routers
 *                       that wire multiple executors dispatch on this.
 *   - `allowedTools` — explicit whitelist of tool names the script
 *                       can invoke from inside. Default empty (no
 *                       tool calls allowed — script is a pure
 *                       computation).
 *
 * Requires the host Sigil to mix in `ScriptSigil`. When the host
 * doesn't, the dispatch tool surfaces a structured error per item
 * instead of attempting script execution.
 */
case class ScriptStep(code: String,
                      language: String = "scala",
                      allowedTools: List[ToolName] = Nil) derives RW
