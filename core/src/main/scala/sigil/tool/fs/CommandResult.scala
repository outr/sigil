package sigil.tool.fs

import fabric.rw.*

/**
 * Result of a shell command — stdout, stderr, and exit code.
 * Returned by [[FileSystemContext.executeCommand]] and surfaced by
 * [[BashTool]].
 */
case class CommandResult(stdout: String, stderr: String, exitCode: Int) derives RW
