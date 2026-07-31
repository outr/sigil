package sigil.tool.web

import fabric.rw.*
import rapid.Task
import sigil.tool.ToolContext
import sigil.tool.model.{WebFetchInput, WebFetchOutput}
import sigil.tool.{DiscoverySpec, Effect, Freshness, Resolution, Tool, ToolExample, ToolIO, ToolName, ToolProfile, ToolSpec}
import spice.http.client.HttpClient
import spice.net.URL

import scala.concurrent.duration.*

/**
 * Fetch a URL via HTTP GET. HTML responses are converted to
 * markdown via [[HtmlToMarkdown]]; other content is returned as-is.
 * Result is truncated to `maxLength` (default 100 KB) and emitted as
 * a typed [[WebFetchOutput]].
 */
final class WebFetchTool(timeout: FiniteDuration = 30.seconds) extends Tool {
  type Input  = WebFetchInput
  type Output = WebFetchOutput
  val io: ToolIO[WebFetchInput, WebFetchOutput] = ToolIO.derived[WebFetchInput, WebFetchOutput].withExamples(
    ToolExample("Read a documentation page", WebFetchInput(url = "https://example.com/docs/intro")),
    ToolExample("Fetch a small JSON endpoint", WebFetchInput(url = "https://api.example.com/status", maxLength = Some(8192)))
  )
  override val name = ToolName("web_fetch")
  override val description =
    """Fetch the contents of a URL via HTTP GET. HTML responses are converted to a markdown-ish rendering;
      |other content types are returned verbatim. Use `maxLength` to cap response size (default 100 KB).
      |Returns the fetched content with its content type, HTTP status code, and a truncation flag.""".stripMargin
  val spec: ToolSpec = ToolSpec(
    name = name,
    description = description,
    profile = ToolProfile(effect = Effect.ReadOnly(Freshness.Stable)),
    discovery = DiscoverySpec(keywords = Set("web", "fetch", "http", "url", "download", "page", "browse"))
  )

  protected def resolve: Resolution[Input, Output] = Resolution.Simple(executeOutput)

  private def executeOutput(input: WebFetchInput, ctx: ToolContext): Task[WebFetchOutput] =
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
              val truncated = processed.length > maxLen
              WebFetchOutput(
                content     = if (truncated) processed.take(maxLen) else processed,
                contentType = ct,
                statusCode  = statusCode,
                truncated   = truncated
              )
            }
          case None =>
            Task.pure(WebFetchOutput(content = "", contentType = ct, statusCode = statusCode, truncated = false))
        }
      }
}

object WebFetchTool {
  val DefaultMaxLength: Int = 100000
}
