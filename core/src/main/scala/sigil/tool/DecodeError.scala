package sigil.tool

import fabric.Json
import fabric.io.JsonFormatter

/**
 * The structured failure surfaced by [[WireSurface.decode]]. Carries
 * every violation found in one pass — the historical "one mismatch
 * per round-trip" path that burned several agent iterations is
 * structurally impossible here: callers that need the line-separated
 * string render call [[render]].
 *
 * `raw` is the JSON that came off the wire (post-normalisation it
 * could be different; we keep the raw form for the refusal body's
 * "you sent" section).
 */
final case class DecodeError(violations: List[DecodeViolation], raw: Json) {

  /**
   * Line-separated render — the shape every existing string-based
   * error path expects. Plugs into
   * `RefusalPayload.enrichRule(tool, rule = error.render, …)`
   * unchanged.
   */
  def render: String = violations.map(_.render).mkString("; ")

  /**
   * Compact JSON of the raw payload, suitable for the refusal body's
   * "you sent" block.
   */
  def renderRaw: String = JsonFormatter.Compact(raw)
}
