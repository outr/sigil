package sigil.tool.fs

import fabric.rw.*

/**
 * One row of paginated `bash` output. The framework emits
 * stdout / stderr lines in arrival order followed by a single
 * `Exit` row carrying the process's exit code.
 *
 * Agents reading the first page typically see the head of
 * stdout/stderr plus the exit row when the command was short;
 * long outputs paginate through `next_page`.
 */
sealed trait BashLine derives RW

object BashLine {
  case class Stdout(line: String) extends BashLine
  case class Stderr(line: String) extends BashLine
  case class Exit(code: Int) extends BashLine
}
