package sigil.tool

/**
 * Raised by [[BootCompletenessCheck]] when the registered tool roster
 * fails its startup verification — an input or output that does not
 * round-trip through the polymorphic RWs, a duplicate tool name, or a
 * `suggestedNextTools` reference that resolves to nothing. Carries
 * every violation so one startup failure names the whole gap, not just
 * the first symptom.
 */
final class ToolRegistrationException(val violations: List[String])
  extends RuntimeException(
    s"Tool registration completeness check failed with ${violations.size} violation(s):\n" +
      violations.map(v => s"  - $v").mkString("\n")
  )
