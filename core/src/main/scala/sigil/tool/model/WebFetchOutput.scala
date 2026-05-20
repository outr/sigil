package sigil.tool.model

import fabric.rw.*

/**
 * Typed result for [[sigil.tool.web.WebFetchTool]].
 *
 *   - `content` — the fetched body. HTML responses are converted to a
 *     markdown-ish rendering; other content types are returned
 *     verbatim. Truncated to the caller's `maxLength`.
 *   - `contentType` — the response's `Content-Type`.
 *   - `statusCode` — the numeric HTTP status code.
 *   - `truncated` — `true` when the body exceeded `maxLength` and was
 *     cut off.
 */
case class WebFetchOutput(content: String,
                          contentType: String,
                          statusCode: Int,
                          truncated: Boolean) derives RW
