package sigil.provider.sse

import fabric.Json

/**
 * Classification of a single SSE-stream line. Producers (providers) get
 * a small enum to pattern-match on instead of repeating prefix
 * bookkeeping inline.
 */
sealed trait SSELine

object SSELine {

  /** A `data: <json>` line whose payload parsed cleanly. */
  case class Data(json: Json) extends SSELine

  /** The literal `data: [DONE]` terminator. */
  case object Done extends SSELine

  /** A blank line (event separator per SSE spec). */
  case object Blank extends SSELine

  /** A `:`-prefixed line (SSE heartbeat / comment). */
  case object Comment extends SSELine

  /** A `data:` line whose payload failed to parse as JSON. The provider
    * decides whether to surface this as a `ProviderEvent.Error`. */
  case class MalformedData(payload: String, reason: String) extends SSELine

  /** Any other unrecognized line (e.g. an `event: <name>` SSE field).
    * Most providers can ignore these. */
  case class Other(content: String) extends SSELine
}
