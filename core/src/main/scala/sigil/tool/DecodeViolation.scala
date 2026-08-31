package sigil.tool

/**
 * One failure surfaced during [[WireSurface.decode]]. Carries the
 * field path (empty for root-level failures), a human-readable
 * reason, and the structured [[ViolationKind]] so downstream
 * phrasing decisions read a typed field instead of re-parsing the
 * rendered string. Multiple violations land in one [[DecodeError]] —
 * the agent sees every failed field at once on a single retry, not
 * one per round-trip.
 */
final case class DecodeViolation(path: List[String], reason: String, kind: ViolationKind = ViolationKind.Constraint) {

  /**
   * Render as `path: reason` or just `reason` for root-level failures.
   */
  def render: String =
    if (path.isEmpty) reason else s"${path.mkString(".")}: $reason"
}
