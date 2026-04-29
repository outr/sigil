package sigil.tooling

import fabric.rw.*
import lightdb.doc.{JsonConversion, RecordDocument, RecordDocumentModel}
import lightdb.id.Id
import lightdb.time.Timestamp
import sigil.{GlobalSpace, SpaceId}

/**
 * Persisted configuration for a language-server binding. Stored in
 * [[ToolingCollections.lspServers]] and loaded by [[LspManager]] when
 * a session for the matching language is requested.
 *
 * One config = one language. The actual subprocess is spawned per
 * `(languageId, projectRoot)` pair — multiple Scala projects share
 * the same `LspServerConfig("scala", ...)` config but each gets its
 * own warmed Metals instance keyed off its build root.
 *
 * Default lifecycle: idle servers shut down after `idleTimeoutMs` of
 * no calls. The next request lazily restarts. This bounds memory for
 * agents jumping across many projects (Metals can hold ~1GB warmed)
 * without paying the warm-up cost on every call.
 *
 * @param languageId        canonical language identifier — `"scala"`,
 *                          `"rust"`, `"python"`, `"typescript"`, etc.
 *                          Tools resolve a session by this id; one
 *                          server config per id.
 * @param command           executable to spawn (e.g. `"metals"`,
 *                          `"rust-analyzer"`). The framework's spawn
 *                          path uses `ProcessBuilder.command(...)`
 *                          directly — apps wrap shell flags into
 *                          [[args]] when needed.
 * @param args              command arguments. Some servers need flags
 *                          (e.g. `pyright-langserver` needs `--stdio`).
 * @param fileGlobs         globs the language server should be
 *                          consulted for. Tools matching files outside
 *                          these globs skip the server. Conservative
 *                          defaults if empty.
 * @param rootMarkers       file names that mark a project root when
 *                          walking up from a working file (e.g.
 *                          `build.sbt`, `Cargo.toml`, `package.json`).
 *                          The first marker found from the file's
 *                          directory upward is the session's project
 *                          root.
 * @param env               extra environment variables to set on the
 *                          spawned subprocess. Inherits the parent
 *                          env when empty.
 * @param idleTimeoutMs     inactivity window before the session
 *                          shuts down. Default 10 min — language
 *                          servers are expensive to warm; don't churn.
 * @param space             scope for [[sigil.tool.Tool.space]] on
 *                          tools that resolve through this config.
 *                          Defaults to [[GlobalSpace]].
 */
case class LspServerConfig(languageId: String,
                           command: String,
                           args: List[String] = Nil,
                           fileGlobs: List[String] = Nil,
                           rootMarkers: List[String] = Nil,
                           env: Map[String, String] = Map.empty,
                           idleTimeoutMs: Long = 10L * 60L * 1000L,
                           space: SpaceId = GlobalSpace,
                           created: Timestamp = Timestamp(),
                           modified: Timestamp = Timestamp(),
                           _id: Id[LspServerConfig] = LspServerConfig.id())
  extends RecordDocument[LspServerConfig]

object LspServerConfig extends RecordDocumentModel[LspServerConfig] with JsonConversion[LspServerConfig] {
  implicit override def rw: RW[LspServerConfig] = RW.gen

  override def id(value: String = rapid.Unique()): Id[LspServerConfig] = Id(value)

  /** Stable id derived from `languageId` — one record per language. */
  def idFor(languageId: String): Id[LspServerConfig] = Id(languageId)
}
