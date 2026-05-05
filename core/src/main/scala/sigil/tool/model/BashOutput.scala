package sigil.tool.model

import fabric.rw.*

/**
 * Typed result for [[sigil.tool.fs.BashTool]]. Agents that compose
 * Bash with other tools pattern-match on `exitCode`, slice
 * `stdout` / `stderr`, etc. without parsing JSON. Streams are
 * truncated to ~100 KB by the underlying
 * [[sigil.tool.fs.FileSystemContext.executeCommand]] before they
 * land here, so the case class always carries finite payloads.
 */
case class BashOutput(stdout: String, stderr: String, exitCode: Int) derives RW
