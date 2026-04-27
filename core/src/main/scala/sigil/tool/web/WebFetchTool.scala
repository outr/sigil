package sigil.tool.web

import fabric.io.JsonFormatter
import fabric.{num, obj, str}
import rapid.Stream
import sigil.TurnContext
import sigil.event.{Event, Message, MessageRole}
import sigil.signal.EventState
import sigil.tool.model.{ResponseContent, WebFetchInput}
import sigil.tool.{ToolExample, ToolName, TypedTool}
import spice.http.client.HttpClient
import spice.net.URL

import scala.concurrent.duration.*

/**
 * Fetch a URL via HTTP GET. HTML responses are converted to
 * markdown via [[HtmlToMarkdown]]; other content is returned as-is.
 * Result is truncated to `maxLength` (default 100 KB).
 */
final class WebFetchTool(timeout: FiniteDuration = 30.seconds)
  extends TypedTool[WebFetchInput](
    name = ToolName("web_fetch"),
    description =
      """Fetch the contents of a URL via HTTP GET. HTML responses are converted to a markdown-ish rendering;
        |other content types are returned verbatim. Use `maxLength` to cap response size (default 100 KB).
        |Returns a JSON object with `content`, `contentType`, and `statusCode`.""".stripMargin,
    examples = List(
      ToolExample("Read a documentation page", WebFetchInput(url = "https://example.com/docs/intro")),
      ToolExample("Fetch a small JSON endpoint", WebFetchInput(url = "https://api.example.com/status", maxLength = Some(8192)))
    ),
    keywords = Set("web", "fetch", "http", "url", "download", "page", "browse")
  ) {
  override protected def executeTyped(input: WebFetchInput, ctx: TurnContext): Stream[Event] = Stream.force(
    HttpClient
      .url(URL.parse(input.url))
      .timeout(timeout)
      .noFailOnHttpStatus
      .send()
      .flatMap { response =>
        val statusCode = response.status.code
        val ct         = response.content.map(_.contentType.outputString).getOrElse("text/plain")
        val maxLen     = input.maxLength.getOrElse(WebFetchTool.DefaultMaxLength)
        response.content match {
          case Some(content) =>
            content.asString.map { raw =>
              val processed = if (ct.contains("text/html")) HtmlToMarkdown.convert(raw) else raw
              val truncated = if (processed.length > maxLen) processed.take(maxLen) else processed
              val payload   = obj("content" -> str(truncated), "contentType" -> str(ct), "statusCode" -> num(statusCode))
              Stream.emit[Event](emit(payload, ctx))
            }
          case None =>
            rapid.Task {
              val payload = obj("content" -> str(""), "contentType" -> str(ct), "statusCode" -> num(statusCode))
              Stream.emit[Event](emit(payload, ctx))
            }
        }
      }
  )

  private def emit(payload: fabric.Json, ctx: TurnContext): Message = Message(
    participantId  = ctx.caller,
    conversationId = ctx.conversation.id,
    topicId        = ctx.conversation.currentTopicId,
    content        = Vector(ResponseContent.Text(JsonFormatter.Compact(payload))),
    state          = EventState.Complete,
    role           = MessageRole.Tool
  )
}

object WebFetchTool {
  val DefaultMaxLength: Int = 100000
}
