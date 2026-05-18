package sigil.tooling.dispatch

import fabric.rw.*

/**
 * Policy for parsing a file into a `List[Json]` of worker items in
 * [[WorkerItemSource.FromFile]].
 *
 *   - [[ItemParser.Lines]] — each non-empty line becomes a worker
 *     item with payload `{"line": "<text>", "lineNumber": <n>}`.
 *     Useful for plain-text lists.
 *   - [[ItemParser.JsonArray]] — the whole file is parsed as a JSON
 *     array; each element becomes one worker item verbatim.
 *   - [[ItemParser.JsonLines]] — each non-empty line is parsed as a
 *     JSON value (typically a JSON object) — JSONL / NDJSON.
 *   - [[ItemParser.CsvHeaders]] — read as CSV with the first line as
 *     headers; each subsequent row becomes a JSON object keyed by
 *     header. Comma-delimited; rough parser (no quote escaping).
 *     Useful for spreadsheet-shaped lists.
 */
enum ItemParser derives RW {
  case Lines
  case JsonArray
  case JsonLines
  case CsvHeaders
}
