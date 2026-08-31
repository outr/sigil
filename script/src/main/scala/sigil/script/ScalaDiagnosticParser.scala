package sigil.script

/**
 * Parses the Scala 3 REPL `ConsoleReporter` diagnostic output into
 * typed [[CompileError]] records.
 *
 * Scala 3 formats each error with a header line of the shape
 * `-- [E007] Type Mismatch Error: <line>:<col> ----...` (the
 * `ScriptEngine`'s in-memory source has no file name, so the position
 * is a bare `line:col` pair) followed by the offending source and an
 * explanation. This parser keys off the `-- [E` header to split the
 * block into per-error chunks, lifts the `line:col` position from the
 * header, and takes the explanation text as the message.
 */
object ScalaDiagnosticParser {

  private val HeaderPattern =
    """--\s*\[E\d+\]\s*[^:]*:\s*(?:[^:]*:)?\s*(\d+):(\d+)""".r

  /**
   * Parse `diagnostics` into typed [[CompileError]]s. When the output
   * contains error markers the parser could not position-decode, it
   * still emits one [[CompileError]] per recognised header (line /
   * column `0` when no position parsed). When no header is found at
   * all but the output looks like an error, a single positionless
   * [[CompileError]] carrying the whole text is returned so callers
   * never get an empty list for a genuine failure.
   */
  def parse(diagnostics: String): List[CompileError] = {
    if (diagnostics.trim.isEmpty) return Nil
    val lines = diagnostics.linesIterator.toList
    val headerIndices = lines.zipWithIndex.collect {
      case (l, i) if l.contains("-- [E") => i
    }
    if (headerIndices.isEmpty) {
      if (diagnostics.linesIterator.exists(_.contains(" error:")))
        List(CompileError(0, 0, diagnostics.trim))
      else Nil
    } else {
      val bounds = headerIndices :+ lines.length
      bounds.sliding(2).toList.collect { case List(start, end) =>
        val block = lines.slice(start, end)
        val header = block.headOption.getOrElse("")
        val (line, column) = header match {
          case HeaderPattern(l, c) => (l.toInt, c.toInt)
          case _ => (0, 0)
        }
        val message = block.mkString("\n").trim
        CompileError(line, column, if (message.isEmpty) header.trim else message)
      }
    }
  }
}
