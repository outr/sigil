package sigil.tool.model

import fabric.rw.*
import sigil.tool.ToolInput

/** Input for `http_request` — issue an HTTP request to an arbitrary
  * URL with a configurable method, headers, and body. Use for ad-hoc
  * API calls when an agent needs to talk to a service that doesn't
  * have a dedicated tool wrapper.
  *
  *   - `url` — the target URL.
  *   - `method` — HTTP method (`GET`, `POST`, `PUT`, `PATCH`, `DELETE`, `HEAD`, `OPTIONS`).
  *   - `headers` — request headers as a flat key→value map. `Content-Type`
  *     defaults to `application/json` when `body` is supplied and no
  *     content-type header is present.
  *   - `body` — optional request body as a UTF-8 string. Senders that
  *     need binary payloads should base64-encode and set the appropriate
  *     `Content-Type`.
  *   - `timeoutMs` — total request timeout (default 30 s).
  *   - `maxResponseBytes` — cap on the captured response body to keep
  *     the agent's context bounded (default 1 MB). Larger responses
  *     are truncated; the result flags `bodyTruncated = true`.
  */
case class HttpRequestInput(url: String,
                             method: String = "GET",
                             headers: Map[String, String] = Map.empty,
                             body: Option[String] = None,
                             timeoutMs: Long = 30000L,
                             maxResponseBytes: Int = 1_000_000) extends ToolInput derives RW
