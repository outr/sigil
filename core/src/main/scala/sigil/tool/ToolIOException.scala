package sigil.tool

/**
 * Raised by [[ToolIO]]'s construction gates — the schema-ergonomics
 * lint, the [[ToolIO.withSchema]] probe round-trip, and per-example
 * validation in `withExamples`. Carries every violation so a failing
 * tool reports its whole problem at once.
 */
class ToolIOException(val context: String, val violations: List[String])
  extends RuntimeException(s"ToolIO for $context is invalid:\n${violations.map(v => s"  - $v").mkString("\n")}")
