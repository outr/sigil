package sigil.tool.web

import fabric.rw.*
import rapid.Task
import sigil.tool.ToolContext
import sigil.tool.model.{HttpRequestInput, HttpRequestMethod, HttpRequestOutput}
import sigil.tool.{DiscoverySpec, Effect, MutationTargeting, Tool, ToolExample, ToolName, ToolProfile, ToolSpec}
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
case object HttpRequestTool extends Tool {
  type Input  = HttpRequestInput
  type Output = HttpRequestOutput
  val inputRW  = summon[RW[HttpRequestInput]]
  val outputRW = summon[RW[HttpRequestOutput]]
  override val name = ToolName("http_request")
  override val description =
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
      |Returns `{status, statusText, headers, body, bodyTruncated, contentType}`.""".stripMargin
  val spec: ToolSpec = ToolSpec(
    name = name,
    description = description,
    profile = ToolProfile(effect = Effect.Mutating(MutationTargeting.none)),
    discovery = DiscoverySpec(keywords = Set("http", "request", "api", "rest", "fetch", "curl", "post", "put", "patch", "delete"))
  )
  override val examples = List(
    ToolExample(
      "GET a JSON endpoint",
      HttpRequestInput(url = "https://api.example.com/v1/status")
    ),
    ToolExample(
      "POST a JSON body",
      HttpRequestInput(
        url     = "https://api.example.com/v1/items",
        method  = HttpRequestMethod.Post,
        headers = Map("Authorization" -> "Bearer ..."),
        body    = Some("""{"name":"thing"}""")
      )
    )
  )

  override def executeOutput(input: HttpRequestInput, context: ToolContext): Task[HttpRequestOutput] = Task.defer {
    val timeout = input.timeoutMs.millis
    val parsedUrl = URL.parse(input.url)
    val httpMethod = methodFor(input.method)
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

  /** Map the typed [[HttpRequestMethod]] onto spice's `HttpMethod`. */
  private def methodFor(method: HttpRequestMethod): HttpMethod = method match {
    case HttpRequestMethod.Get     => HttpMethod.Get
    case HttpRequestMethod.Post    => HttpMethod.Post
    case HttpRequestMethod.Put     => HttpMethod.Put
    case HttpRequestMethod.Patch   => HttpMethod.Patch
    case HttpRequestMethod.Delete  => HttpMethod.Delete
    case HttpRequestMethod.Head    => HttpMethod.Head
    case HttpRequestMethod.Options => HttpMethod.Options
  }
}
