package sigil.provider.debug

import fabric.*
import fabric.io.{JsonFormatter, JsonParser}
import fabric.rw.*
import lightdb.time.Timestamp
import rapid.Task
import spice.http.client.intercept.Interceptor
import spice.http.content.StringContent
import spice.http.{HttpRequest, HttpResponse}

import java.nio.file.{Files, OpenOption, Path, StandardOpenOption}
import scala.collection.mutable
import scala.util.Try

/**
 * Spice [[spice.http.client.intercept.Interceptor]] that records every HTTP round-trip as JSON
 * lines appended to a file — good for post-hoc diagnostic walking of
 * a full conversation (provider requests, tool round-trips, and
 * anything else that goes through `HttpClient`).
 *
 * Two lines per round-trip:
 *
 * {{{
 *   {"kind":"request","ts":"...","method":"POST","url":"...","body":"..."}
 *   {"kind":"response","ts":"...","status":200,"body":"..."}
 * }}}
 *
 * Bodies are captured verbatim when they're [[StringContent]] and
 * summarized otherwise (e.g. streamed or binary content). For SSE
 * endpoints, the response body comes through after the stream has
 * been fully consumed, so the recorded payload is the accumulated
 * stream text.
 *
 * Thread-safe: writes are synchronized on the file path. Each append
 * flushes immediately so a crashed process preserves everything
 * written so far.
 */
case class JsonLinesInterceptor(path: Path) extends Interceptor {
  // Ensure the parent dir exists on first write.
  private val parent = Option(path.getParent)
  private val writeOpts: Array[OpenOption] = Array(StandardOpenOption.CREATE, StandardOpenOption.APPEND)

  override def before(request: HttpRequest): Task[HttpRequest] = Task {
    appendLine(obj(
      "kind" -> str("request"),
      "ts" -> str(Timestamp().toString),
      "method" -> str(request.method.value),
      "url" -> str(request.url.toString),
      "body" -> bodyToJson(request.content)
    ))
    request
  }

  override def after(request: HttpRequest, result: Try[HttpResponse]): Task[Try[HttpResponse]] = Task {
    result match {
      case scala.util.Success(response) =>
        appendLine(obj(
          "kind" -> str("response"),
          "ts" -> str(Timestamp().toString),
          "url" -> str(request.url.toString),
          "status" -> num(response.status.code),
          "body" -> bodyToJson(response.content)
        ))
      case scala.util.Failure(t) =>
        appendLine(obj(
          "kind" -> str("response"),
          "ts" -> str(Timestamp().toString),
          "url" -> str(request.url.toString),
          "error" -> str(t.getMessage)
        ))
    }
    result
  }

  /** Convert a content payload to a JSON field. Reassembles a streamed
    * SSE body into its final message + complete tool calls (#322) so a
    * streamed-provider response is readable straight from the log;
    * parses ordinary JSON bodies as structured JSON; wraps non-JSON
    * strings as plain text; summarizes anything else. */
  private def bodyToJson(content: Option[spice.http.content.Content]): Json = content match {
    case Some(s: StringContent) =>
      if (JsonLinesInterceptor.looksLikeSse(s.value)) JsonLinesInterceptor.reassembleSse(s.value)
      else Try(JsonParser(s.value)).toOption.getOrElse(str(s.value))
    case Some(other) =>
      str(s"<${other.contentType} ${other.length} bytes>")
    case None => Null
  }

  private def appendLine(line: Json): Unit = synchronized {
    parent.foreach { p => if (!Files.exists(p)) Files.createDirectories(p) }
    val serialized = JsonFormatter.Compact(line) + "\n"
    Files.writeString(path, serialized, writeOpts*)
  }
}

object JsonLinesInterceptor {
  // Minimal OpenAI-style streaming-chunk shapes. Decoding is lenient
  // (extra fields ignored) and wrapped in Try, so a chunk we don't
  // recognise is simply skipped rather than aborting reassembly.
  private case class SseFn(name: Option[String] = None, arguments: Option[String] = None) derives RW
  private case class SseToolCall(index: Option[Int] = None, id: Option[String] = None, function: Option[SseFn] = None) derives RW
  private case class SseDelta(content: Option[String] = None, tool_calls: Option[List[SseToolCall]] = None) derives RW
  private case class SseChoice(delta: Option[SseDelta] = None) derives RW
  private case class SseChunk(choices: Option[List[SseChoice]] = None) derives RW

  /** True when the body is an SSE stream (any `data:`-prefixed line). */
  def looksLikeSse(body: String): Boolean =
    body.linesIterator.exists(_.trim.startsWith("data:"))

  /** Reassemble OpenAI-style streaming deltas: concatenate
    * `choices[].delta.content` and each tool call's `function.arguments`
    * (accumulated by `index`) into the final message + complete tool
    * calls. Falls back to the raw body for an unrecognised SSE shape so
    * nothing is lost (#322). */
  def reassembleSse(body: String): Json = {
    val contentSb = new StringBuilder
    // index -> (id, name, accumulated-arguments)
    val toolCalls = mutable.LinkedHashMap.empty[Int, (Option[String], Option[String], StringBuilder)]
    body.linesIterator.map(_.trim).filter(_.startsWith("data:")).foreach { line =>
      val payload = line.stripPrefix("data:").trim
      if (payload.nonEmpty && payload != "[DONE]") {
        Try(JsonParser(payload).as[SseChunk]).toOption.foreach { chunk =>
          chunk.choices.flatMap(_.headOption).flatMap(_.delta).foreach { delta =>
            delta.content.foreach(contentSb.append)
            delta.tool_calls.foreach(_.foreach { tc =>
              val idx = tc.index.getOrElse(0)
              val entry = toolCalls.getOrElseUpdate(idx, (None, None, new StringBuilder))
              tc.function.flatMap(_.arguments).foreach(entry._3.append)
              toolCalls.update(idx, (entry._1.orElse(tc.id), entry._2.orElse(tc.function.flatMap(_.name)), entry._3))
            })
          }
        }
      }
    }
    if (contentSb.isEmpty && toolCalls.isEmpty) str(body)
    else obj(
      "_format"    -> str("sse-reassembled"),
      "content"    -> str(contentSb.toString),
      "tool_calls" -> arr(toolCalls.toList.map { case (idx, (id, name, args)) =>
        obj(
          "index"     -> num(idx),
          "id"        -> id.map(str).getOrElse(Null),
          "name"      -> name.map(str).getOrElse(Null),
          "arguments" -> str(args.toString)
        )
      }*)
    )
  }
}
