package sigil.provider.debug

import fabric.*
import fabric.io.{JsonFormatter, JsonParser}
import lightdb.time.Timestamp
import rapid.Task
import spice.http.client.intercept.Interceptor
import spice.http.content.StringContent
import spice.http.{HttpRequest, HttpResponse}

import java.nio.file.{Files, OpenOption, Path, StandardOpenOption}
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

  /** Convert a content payload to a JSON field. Parses JSON bodies
    * as structured JSON (so readers don't have to double-decode);
    * wraps non-JSON strings as plain text; summarizes anything
    * else. */
  private def bodyToJson(content: Option[spice.http.content.Content]): Json = content match {
    case Some(s: StringContent) =>
      Try(JsonParser(s.value)).toOption.getOrElse(str(s.value))
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
