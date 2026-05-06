package sigil.tool.web

import rapid.Task
import sigil.TurnContext
import sigil.tool.model.{HttpRequestInput, HttpRequestOutput}
import sigil.tool.{ToolExample, ToolName, TypedOutputTool}
import spice.http.HttpMethod
import spice.http.content.Content
import spice.net.{ContentType, URL}

import scala.concurrent.duration.*

/**
 * Issue an arbitrary HTTP request — full method / headers / body
 * surface for ad-hoc API calls. Distinct from [[WebFetchTool]]:
 * `web_fetch` is GET-only, HTML→markdown, optimized for "read this
 * page". `http_request` is the raw escape hatch — POST/PUT/PATCH/DELETE,
 * arbitrary headers, arbitrary body, raw response.
 *
 * Backed by spice's `HttpClient`. The response body is captured as
 * UTF-8 and truncated to `maxResponseBytes` so a large response
 * doesn't blow the agent's context window.
 */
case object HttpRequestTool extends TypedOutputTool[HttpRequestInput, HttpRequestOutput](
  name = ToolName("http_request"),
  description =
    """Issue an HTTP request to an arbitrary URL.
      |
      |`url` is the target. `method` is `GET` (default) / `POST` / `PUT` / `PATCH` / `DELETE` /
      |`HEAD` / `OPTIONS`. `headers` is a flat key→value map; `Content-Type` defaults to
      |`application/json` when `body` is supplied without an explicit content-type.
      |`body` is an optional UTF-8 request body (binary payloads should be base64-encoded).
      |`timeoutMs` (default 30000) bounds the whole request. `maxResponseBytes` (default 1 MB)
      |caps the captured response body — larger payloads are truncated and `bodyTruncated`
      |is set in the result.
      |
      |Returns `{status, statusText, headers, body, bodyTruncated, contentType}`.""".stripMargin,
  examples = List(
    ToolExample(
      "GET a JSON endpoint",
      HttpRequestInput(url = "https://api.example.com/v1/status")
    ),
    ToolExample(
      "POST a JSON body",
      HttpRequestInput(
        url     = "https://api.example.com/v1/items",
        method  = "POST",
        headers = Map("Authorization" -> "Bearer ..."),
        body    = Some("""{"name":"thing"}""")
      )
    )
  ),
  keywords = Set("http", "request", "api", "rest", "fetch", "curl", "post", "put", "patch", "delete")
) {
  override protected def executeTyped(input: HttpRequestInput, context: TurnContext): Task[HttpRequestOutput] = Task.defer {
    val timeout = input.timeoutMs.millis
    val parsedUrl = URL.parse(input.url)
    val httpMethod = HttpMethod.get(input.method.toUpperCase).getOrElse(
      throw new IllegalArgumentException(s"http_request: unsupported method '${input.method}'")
    )
    val client0 = spice.http.client.HttpClient
      .url(parsedUrl)
      .method(httpMethod)
      .timeout(timeout)
      .noFailOnHttpStatus

    val client1 = input.headers.foldLeft(client0) { case (c, (k, v)) => c.header(k, v) }

    val client2 = input.body match {
      case Some(rawBody) =>
        val ctHeader = input.headers.find { case (k, _) => k.equalsIgnoreCase("Content-Type") }.map(_._2)
        val contentType = ctHeader.flatMap(parseContentType).getOrElse(ContentType.`application/json`)
        client1.content(Content.string(rawBody, contentType))
      case None => client1
    }

    client2.send().flatMap { response =>
      val responseHeaders: Map[String, String] = response.headers.map.iterator.map { case (k, vs) =>
        k -> vs.mkString(", ")
      }.toMap
      val contentTypeHdr = responseHeaders.find { case (k, _) => k.equalsIgnoreCase("Content-Type") }.map(_._2)

      response.content match {
        case None =>
          Task.pure(HttpRequestOutput(
            status        = response.status.code,
            statusText    = response.status.message,
            headers       = responseHeaders,
            body          = "",
            bodyTruncated = false,
            contentType   = contentTypeHdr
          ))
        case Some(content) =>
          content.asString.map { raw =>
            val truncated = raw.length > input.maxResponseBytes
            HttpRequestOutput(
              status        = response.status.code,
              statusText    = response.status.message,
              headers       = responseHeaders,
              body          = if (truncated) raw.take(input.maxResponseBytes) else raw,
              bodyTruncated = truncated,
              contentType   = contentTypeHdr
            )
          }
      }
    }
  }

  private def parseContentType(raw: String): Option[ContentType] =
    scala.util.Try(ContentType.parse(raw)).toOption
}
