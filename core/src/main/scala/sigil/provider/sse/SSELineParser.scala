package sigil.provider.sse

import fabric.Json
import fabric.io.JsonParser

/**
 * Pure SSE line classifier shared across providers.
 *
 * SSE wire format (per OpenAI / Anthropic / llama.cpp's OpenAI-compatible
 * surface): each event arrives as `data: <payload>` lines, terminated by
 * blank lines. The terminator `data: [DONE]` (literal) signals the
 * server has closed the stream.
 *
 * This parser is agnostic about what the JSON inside means — that's the
 * provider's concern. It only classifies the line shape so the provider
 * doesn't repeat the prefix/heartbeat/done bookkeeping inline.
 */
object SSELineParser {

  /** Classify a single line from the SSE stream. */
  def parse(line: String): SSELine = {
    val trimmed = line.trim
    if (trimmed.isEmpty) SSELine.Blank
    else if (trimmed.startsWith(":")) SSELine.Comment    // SSE heartbeat / comment
    else if (trimmed == "data: [DONE]") SSELine.Done
    else if (trimmed.startsWith("data: ")) {
      val payload = trimmed.drop("data: ".length)
      try SSELine.Data(JsonParser(payload))
      catch { case t: Throwable => SSELine.MalformedData(payload, t.getMessage) }
    }
    else SSELine.Other(trimmed)
  }

  /** Convenience for callers that only care about parseable JSON
    * payloads. Returns None for blank, comment, done, malformed, or
    * other lines. */
  def parseDataLine(line: String): Option[Json] = parse(line) match {
    case SSELine.Data(json) => Some(json)
    case _                  => None
  }
}
