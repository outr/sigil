package sigil.tool.model

import fabric.rw.*

/** Result of an `http_request` call.
  *
  *   - `status` — numeric HTTP status code (200, 404, etc.).
  *   - `statusText` — server-supplied status reason ("OK", "Not Found").
  *   - `headers` — response headers as a flat key→value map. Multi-value
  *     headers are joined with `, `.
  *   - `body` — UTF-8 response body, possibly truncated.
  *   - `bodyTruncated` — `true` when the response was larger than the
  *     caller's `maxResponseBytes` and got cut off mid-payload.
  *   - `contentType` — the `Content-Type` header from the response,
  *     surfaced as a top-level field for convenience.
  */
case class HttpRequestOutput(status: Int,
                              statusText: String,
                              headers: Map[String, String],
                              body: String,
                              bodyTruncated: Boolean,
                              contentType: Option[String]) derives RW
