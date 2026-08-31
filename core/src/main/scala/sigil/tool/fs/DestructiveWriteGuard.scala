package sigil.tool.fs

import scala.util.matching.Regex

/**
 * Guard against silently destroying an existing non-empty file by overwriting
 * it with content that is self-evidently not a real rewrite. A model will
 * occasionally emit garbage — a tool-call / pagination placeholder it meant to
 * "execute", a lone unresolved `{{var}}`, or a collapse of a real source file
 * to a line or two — and a write tool that irreversibly destroys source on that
 * garbage is the actual hazard (observed: a 200-line file replaced with the
 * single line `[read_file: offset=152 limit=200]`).
 *
 * Detection is intentionally conservative — it fires only on OVERWRITES of an
 * existing non-empty file, never on creating a new file, and the caller can opt
 * out with `force` for the rare intentional truncate-to-nothing. The two signals
 * mirror the bug's two failure shapes:
 *
 *   1. **Placeholder** — the whole replacement is a single tool-call / pagination
 *      token (`[read_file: …]`, `[… to read more …]`, `offset=N limit=M`) or a
 *      lone unresolved `{{var}}`. These are never legitimate file contents.
 *   2. **Drastic collapse** — a substantial file (≥ 10 lines) replaced by ≤ 2
 *      lines or by under 10% of its original bytes. A real edit of a large file
 *      does not shrink it to almost nothing without the caller meaning it
 *      (`force`).
 */
object DestructiveWriteGuard {

  /**
   * Whole-content tool-call / pagination placeholder, e.g.
   * `[read_file: offset=152 limit=200]` or `[… to read more …]`.
   */
  private val BracketPlaceholder: Regex =
    """(?i)^\[[^\]]*(read_file|write_file|read more|offset=\s*\d+\s*,?\s*limit=\s*\d+)[^\]]*\]$""".r

  /**
   * Whole content is a single unresolved template variable, e.g. `{{edited}}`.
   */
  private val LoneTemplate: Regex = """^\{\{[^{}]+\}\}$""".r

  /**
   * A file is "substantial" — and therefore protected from collapse — at or
   * above this many lines.
   */
  private val SubstantialLines = 10

  /**
   * Inspect a proposed overwrite. `current` is the file's existing contents
   * (the caller invokes this only when the file exists and is non-empty);
   * `replacement` is the new content. Returns `Some(reason)` when the write
   * is self-evidently destructive and should be refused, `None` when it looks
   * like a legitimate rewrite.
   */
  def check(current: String, replacement: String): Option[String] = {
    val trimmed = replacement.trim
    if (LoneTemplate.matches(trimmed))
      Some(s"the new content is a single unresolved template variable ($trimmed) — not real file content")
    else if (BracketPlaceholder.matches(trimmed))
      Some(s"the new content is a tool-call / pagination placeholder ($trimmed) — not real file content")
    else {
      val origLines = current.count(_ == '\n') + 1
      val newLines = replacement.count(_ == '\n') + 1
      val origBytes = current.getBytes("UTF-8").length.toLong
      val newBytes = replacement.getBytes("UTF-8").length.toLong
      val collapses = origLines >= SubstantialLines && (newLines <= 2 || newBytes * 10 < origBytes)
      if (collapses)
        Some(s"the new content collapses the file from $origLines lines / $origBytes bytes to " +
          s"$newLines line(s) / $newBytes bytes — pass force=true if the truncation is intentional")
      else None
    }
  }
}
