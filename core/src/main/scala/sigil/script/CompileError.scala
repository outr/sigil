package sigil.script

import fabric.rw.*

/**
 * A single compiler diagnostic produced by [[ScriptExecutor.compile]].
 *
 * Pre-flight compilation parses the underlying compiler's error
 * output into these typed records so consumers (the agent's next
 * turn, a dispatch tool's `CompileFailure` result) get line / column
 * / message structure instead of an opaque diagnostic blob.
 *
 *   - `line`    — 1-based source line of the diagnostic, or 0 when
 *     the compiler reported no position.
 *   - `column`  — 1-based column of the diagnostic, or 0 when
 *     unknown.
 *   - `message` — the human-readable diagnostic text.
 */
case class CompileError(line: Int, column: Int, message: String) derives RW
