package sigil.provider.cache

import fabric.io.{JsonFormatter, JsonParser}
import fabric.rw.*
import lightdb.time.Timestamp
import sigil.provider.ProviderEvent

/**
 * A recorded provider response — the request hash that produced it,
 * the wall-clock at recording time (informational; not part of the
 * key), and every [[ProviderEvent]] the underlying provider emitted
 * before the stream completed cleanly.
 *
 * Serialized as JSONL: one compact JSON object per event line. The
 * format is diff-friendly so committed fixtures show meaningful
 * changes when a re-record happens, and round-trips through fabric's
 * polymorphic [[ProviderEvent]] RW so every event subtype is
 * preserved.
 */
case class CachedResponse(requestHash: String,
                          recordedAt: Timestamp,
                          events: List[ProviderEvent]) derives RW

object CachedResponse {

  private val rw: RW[ProviderEvent] = summon[RW[ProviderEvent]]

  /** Render `response` as JSONL — one event per line, compact JSON.
    * The first line is a header carrying `requestHash` + `recordedAt`
    * so a cache file is self-describing without an external manifest. */
  def toJsonl(response: CachedResponse): String = {
    val header = fabric.obj(
      "requestHash" -> fabric.str(response.requestHash),
      "recordedAt"  -> fabric.num(response.recordedAt.value)
    )
    val body = response.events.iterator.map(event => JsonFormatter.Compact(rw.read(event)))
    (Iterator.single(JsonFormatter.Compact(header)) ++ body).mkString("\n") + "\n"
  }

  /** Parse a JSONL payload produced by [[toJsonl]] back into a
    * [[CachedResponse]]. The first non-blank line is the header; every
    * subsequent non-blank line is a [[ProviderEvent]]. */
  def fromJsonl(jsonl: String): CachedResponse = {
    val lines = jsonl.linesIterator.filter(_.trim.nonEmpty).toList
    lines match {
      case headerLine :: eventLines =>
        val header = JsonParser(headerLine)
        val events = eventLines.map(line => rw.write(JsonParser(line)))
        CachedResponse(
          requestHash = header("requestHash").asString,
          recordedAt = Timestamp(header("recordedAt").asLong),
          events = events
        )
      case Nil =>
        throw new RuntimeException("CachedResponse.fromJsonl: empty payload — expected header line followed by event lines")
    }
  }
}
