package sigil.tool

/**
 * One field-scoped failure surfaced during [[WireSurface.decode]]. Carries
 * the field path (empty for root-level failures) and a human-readable
 * reason. Multiple violations land in one [[DecodeError]] — the agent
 * sees every failed field at once on a single retry, not one per round-trip.
 */
final case class DecodeViolation(path: List[String], reason: String) {
  /** Render as `path: reason` or just `reason` for root-level failures. */
  def render: String =
    if (path.isEmpty) reason else s"${path.mkString(".")}: $reason"
}

object DecodeViolation {
  /** Reconstruct a violation from a `ToolInputValidator.validate` line.
    * The validator emits `path.dotted.form: reason` or `<root>: reason`;
    * we split on the first `:` and trim. Parser is lossy by design —
    * the line is already human-readable; this is just for structural
    * surfacing. */
  def parse(line: String): DecodeViolation = {
    val idx = line.indexOf(':')
    if (idx <= 0) DecodeViolation(Nil, line.trim)
    else {
      val rawPath = line.substring(0, idx).trim
      val reason  = line.substring(idx + 1).trim
      val path =
        if (rawPath == "<root>" || rawPath.isEmpty) Nil
        else rawPath.split('.').toList
      DecodeViolation(path, reason)
    }
  }
}
