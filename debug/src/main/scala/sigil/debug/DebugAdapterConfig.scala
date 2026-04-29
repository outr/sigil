package sigil.debug

import fabric.rw.*
import lightdb.doc.{JsonConversion, RecordDocument, RecordDocumentModel}
import lightdb.id.Id
import lightdb.time.Timestamp
import sigil.{GlobalSpace, SpaceId}

/**
 * Persisted configuration for a DAP (Debug Adapter Protocol)
 * adapter binding. Stored in [[DebugCollections.debugAdapters]] and
 * loaded by [[DapManager]] when a debug session for the matching
 * language is requested.
 *
 * One config = one language / runtime. The adapter subprocess is
 * spawned per agent-initiated session — DAP sessions are typically
 * shorter-lived than LSP sessions (minutes, not hours) so per-session
 * spawn is fine.
 *
 * Common adapters:
 *
 *   - Scala / Java (sbt's BSP debug bridge) — usually spawned via
 *     a BSP `debugSession/start` call rather than a stand-alone
 *     adapter; apps wire that path through their own glue
 *   - Python: `python -m debugpy --adapter`
 *   - Node.js: bundled `node --inspect`
 *   - Go: `dlv dap`
 *   - Rust: `lldb-dap` / `vscode-lldb`
 *
 * @param languageId    canonical language id (`"scala"`, `"python"`,
 *                      `"go"`, …). Tools resolve a session by this id.
 * @param command       executable to spawn.
 * @param args          command arguments.
 * @param env           extra environment variables.
 * @param launchType    DAP launch type — `"launch"` (start a fresh
 *                      process) or `"attach"` (connect to a running
 *                      one). Each language adapter defines additional
 *                      `launch.json`-style fields the agent supplies
 *                      via [[DapLaunchInput]].
 * @param idleTimeoutMs inactivity period before an established
 *                      session is torn down. Default 30 min.
 * @param space         scope for tools that resolve through this
 *                      config. Defaults to [[GlobalSpace]].
 */
case class DebugAdapterConfig(languageId: String,
                              command: String,
                              args: List[String] = Nil,
                              env: Map[String, String] = Map.empty,
                              launchType: String = "launch",
                              idleTimeoutMs: Long = 30L * 60L * 1000L,
                              space: SpaceId = GlobalSpace,
                              created: Timestamp = Timestamp(),
                              modified: Timestamp = Timestamp(),
                              _id: Id[DebugAdapterConfig] = DebugAdapterConfig.id())
  extends RecordDocument[DebugAdapterConfig]

object DebugAdapterConfig extends RecordDocumentModel[DebugAdapterConfig] with JsonConversion[DebugAdapterConfig] {
  implicit override def rw: RW[DebugAdapterConfig] = RW.gen

  override def id(value: String = rapid.Unique()): Id[DebugAdapterConfig] = Id(value)

  /** Stable id derived from `languageId` — one record per language. */
  def idFor(languageId: String): Id[DebugAdapterConfig] = Id(languageId)
}
