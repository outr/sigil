package sigil.tooling.container

import fabric.rw.*

/**
 * Policy for parsing a file into a `List[Json]` of container items
 * via [[LoadFileAsContainerTool]].
 *
 *   - [[LinesParser]]      — each non-empty line becomes one item
 *     with payload `{"line": "<text>", "lineNumber": <n>}`.
 *   - [[JsonArrayParser]]  — the whole file is parsed as a JSON
 *     array; each element becomes one item verbatim.
 *   - [[JsonLinesParser]]  — each non-empty line is parsed as one
 *     JSON value (typically a JSON object) — JSONL / NDJSON.
 *   - [[CsvParser]]        — comma-delimited; first line is
 *     headers; each subsequent row becomes a JSON object keyed by
 *     header. Rough — no quoted-comma support.
 *   - [[RegexSplitParser]] — split the file on the supplied regex
 *     pattern; each non-empty segment becomes one item with
 *     payload `{"segment": "<text>", "index": <n>}`.
 */
sealed trait ItemParser derives RW

object ItemParser {

  case object LinesParser extends ItemParser

  case object JsonArrayParser extends ItemParser

  case object JsonLinesParser extends ItemParser

  case object CsvParser extends ItemParser

  case class RegexSplitParser(pattern: String) extends ItemParser derives RW
}
