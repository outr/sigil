package sigil.tooling.types

import fabric.rw.*

/** Tool-emission shape for `bsp_compile`. The agent inspects
  * `status` (OK / ERROR / CANCELLED) and iterates `diagnostics` per
  * target / file rather than regex-parsing rendered text.
  *
  * `cause` makes an ERROR actionable when `diagnostics` is empty: a
  * request-level failure (target resolution, build import, BSP
  * connection) carries the failing stage + underlying error text, and
  * a compile that failed without publishing structured diagnostics
  * carries a bounded tail of the build server's log output. A bare
  * `{status: ERROR, diagnostics: []}` leaves the agent blind — nothing
  * in its loop can surface WHAT failed, so it thrashes (re-compile,
  * re-list, restart the server) without converging. */
case class BspCompileResult(projectRoot: String,
                            status: String,
                            targetCount: Int,
                            diagnostics: List[BspDiagnostic],
                            cause: Option[String] = None) extends sigil.tool.ToolOutput derives RW {

  /** Line-oriented render — what the model reads (and what the
    * overflow file holds). Status + counts on line 1 so ANY
    * head-truncation still yields the verdict; one diagnostic per
    * line after it, errors first, grouped by file. A potentially
    * multi-line `cause` renders directly after the status when there
    * are no diagnostics (it IS the story then) and LAST when there
    * are (it must not push the error list below the truncation head).
    * The typed JSON stays on the output for clients. */
  override def modelText: Option[String] = Some {
    val statusLine =
      if (diagnostics.isEmpty) s"$status · $targetCount target(s)"
      else {
        val files = diagnostics.map(_.filePath).distinct.size
        s"$status — ${DiagnosticLines.countsSummary(diagnostics.map(_.severity))} across $files file(s) · $targetCount target(s)"
      }
    val causeBlock = cause.map(c => s"cause: $c").toList
    val lines =
      if (diagnostics.isEmpty) statusLine :: causeBlock
      else statusLine :: DiagnosticLines.renderBsp(diagnostics) ::: causeBlock
    lines.mkString("\n")
  }
}
