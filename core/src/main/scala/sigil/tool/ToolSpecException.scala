package sigil.tool

/**
 * Raised by [[ToolSpec.apply]] when a spec violates the construction
 * contract. Carries every violation, not just the first, so an
 * author fixes the spec in one pass.
 */
final class ToolSpecException(val toolName: String, val violations: List[String])
  extends RuntimeException(
    s"Invalid ToolSpec for '$toolName':\n${violations.map(v => s"  - $v").mkString("\n")}"
  )
