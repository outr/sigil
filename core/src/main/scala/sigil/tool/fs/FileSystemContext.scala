package sigil.tool.fs

import rapid.Task

/**
 * Abstract filesystem / shell operations consumed by the
 * `sigil.tool.fs` tool family. Apps inject the implementation:
 *
 *   - [[LocalFileSystemContext]] — host-local execution; optional
 *     basePath sandboxes paths to a subtree.
 *   - app-specific impls for routing through other backends (e.g.
 *     a docker container, a remote worker — though for "remote
 *     execution" the recommended path is wrapping the local tool
 *     with [[sigil.tool.proxy.ProxyTool]] rather than swapping the
 *     context).
 *
 * The contract is intentionally minimal — every method maps 1:1
 * onto a single filesystem call so apps that override are not
 * forced to re-implement higher-level conveniences.
 */
trait FileSystemContext {

  /** Execute `command` (typically via `bash -c`) with optional
    * working directory and timeout. Return stdout/stderr/exit. */
  def executeCommand(command: String,
                     workingDir: Option[String] = None,
                     timeoutMs: Long = 120000L): Task[CommandResult]

  /** Read the entire file as UTF-8 text. */
  def readFile(filePath: String): Task[String]

  /** Read a window of lines (zero-indexed offset, exclusive limit).
    * Returns `(selectedLines, totalLines)`. */
  def readFileLines(filePath: String, offset: Int, limit: Int): Task[(List[String], Int)]

  /** Write `content` (UTF-8). Creates parent directories. Returns
    * bytes written. */
  def writeFile(filePath: String, content: String): Task[Long]

  /** List paths under `basePath` matching `pattern` (glob syntax).
    * Returns paths relative to `basePath`. */
  def listFiles(basePath: String, pattern: String, maxResults: Int = 1000): Task[List[String]]

  /** Search files for a regex pattern. `glob` optionally restricts
    * the file set; `contextLines` controls before/after lines per
    * match. */
  def searchFiles(basePath: String,
                  pattern: String,
                  glob: Option[String] = None,
                  maxMatches: Int = 500,
                  contextLines: Int = 0): Task[List[GrepMatch]]

  /** Delete a single file. Returns true if the file existed and was
    * deleted; false if it did not exist. */
  def deleteFile(filePath: String): Task[Boolean]
}
