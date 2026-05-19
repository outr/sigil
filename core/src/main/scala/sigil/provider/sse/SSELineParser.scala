package sigil.provider.sse

import fabric.Json
import fabric.io.JsonParser
import sigil.provider.ProviderEvent

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

  /** Classify `line` and dispatch it to the right provider callback.
    *
    * Every provider's `parseLine` matches the same five-way shape:
    * a clean `data:` payload routes to `onData`, the `[DONE]`
    * terminator routes to `onDone`, a malformed `data:` payload
    * routes to `onMalformed`, and blank / comment / other lines
    * produce no events. This helper owns that skeleton so providers
    * only supply their chunk-parsing logic.
    *
    * `onMalformed` defaults to a generic `ProviderEvent.Error`;
    * providers with a bespoke error-message format override it. */
  def dispatch(line: String)(
      onData: Json => Vector[ProviderEvent],
      onDone: => Vector[ProviderEvent],
      onMalformed: String => Vector[ProviderEvent] = reason => Vector(ProviderEvent.Error(s"parse: $reason"))
  ): Vector[ProviderEvent] = parse(line) match {
    case SSELine.Data(json)               => onData(json)
    case SSELine.Done                     => onDone
    case SSELine.MalformedData(_, reason) => onMalformed(reason)
    case SSELine.Blank | SSELine.Comment | _: SSELine.Other => Vector.empty
  }
}
